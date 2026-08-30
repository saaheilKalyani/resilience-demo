package com.resilience.demo.order.service;

import com.resilience.demo.order.controller.CircuitBreakerStatus;
import com.resilience.demo.order.controller.OrderRequest;
import com.resilience.demo.order.controller.OrderResponse;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final RestClient paymentServiceClient;
    private final Retry paymentRetry;
    private final TimeLimiter paymentTimeout;
    private final CircuitBreaker paymentCircuitBreaker;
    private final ExecutorService paymentExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public OrderService(
            @Value("${payment.service.url}") String paymentServiceUrl,
            @Value("${payment.retry.max-attempts:3}") int retryMaxAttempts,
            @Value("${payment.retry.wait-duration-ms:500}") long retryWaitMs,
            @Value("${payment.timeout.duration-ms:2000}") long timeoutMs,
            @Value("${payment.circuit-breaker.sliding-window-size:5}") int slidingWindowSize,
            @Value("${payment.circuit-breaker.minimum-number-of-calls:5}") int minimumNumberOfCalls,
            @Value("${payment.circuit-breaker.failure-rate-threshold:50}") float failureRateThreshold,
            @Value("${payment.circuit-breaker.wait-duration-in-open-state-ms:5000}") long openWaitMs,
            @Value("${payment.circuit-breaker.permitted-calls-in-half-open:2}") int permittedInHalfOpen) {

        this.paymentServiceClient = RestClient.builder()
                .baseUrl(paymentServiceUrl)
                .build();

        // Retry only on a Payment Service 5xx response - a real server-side failure.
        // Deliberately NOT retried: timeouts (handled by paymentTimeout below), connection
        // errors, and an open circuit breaker (CallNotPermittedException) - retrying against
        // an open circuit would just be rejected again instantly, so we go straight to fallback.
        this.paymentRetry = Retry.of("paymentRetry", RetryConfig.custom()
                .maxAttempts(retryMaxAttempts) // total attempts, so 3 = 1 initial call + 2 retries
                .waitDuration(Duration.ofMillis(retryWaitMs))
                .retryOnException(t -> t instanceof RestClientResponseException rex
                        && rex.getStatusCode().is5xxServerError())
                .build());

        // Gives Payment Service a fixed window to respond. Set clearly shorter than the
        // /payments/slow endpoint's ~5s delay so we can observe Order Service giving up and
        // falling back instead of waiting the full 5 seconds.
        this.paymentTimeout = TimeLimiter.of("paymentTimeout", TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofMillis(timeoutMs))
                .build());

        // Opens once at least minimumNumberOfCalls have happened and failureRateThreshold% of
        // the last slidingWindowSize calls failed. Each retry attempt counts as its own call,
        // so a couple of FAILURE requests (each retried 3 times) is enough to open it.
        this.paymentCircuitBreaker = CircuitBreaker.of("paymentCircuitBreaker", CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(slidingWindowSize)
                .minimumNumberOfCalls(minimumNumberOfCalls)
                .failureRateThreshold(failureRateThreshold)
                .waitDurationInOpenState(Duration.ofMillis(openWaitMs))
                .permittedNumberOfCallsInHalfOpenState(permittedInHalfOpen)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build());

        paymentCircuitBreaker.getEventPublisher().onStateTransition(event -> {
            CircuitBreaker.State toState = event.getStateTransition().getToState();
            if (toState == CircuitBreaker.State.CLOSED) {
                log.info("Circuit Breaker state: {}", toState);
            } else {
                log.warn("Circuit Breaker state: {}", toState);
            }
        });
    }

    @PreDestroy
    void shutdown() {
        paymentExecutor.shutdown();
    }

    public CircuitBreakerStatus getCircuitBreakerStatus() {
        return new CircuitBreakerStatus(paymentCircuitBreaker.getName(), paymentCircuitBreaker.getState().name());
    }

    public OrderResponse createOrder(OrderRequest request) {
        log.info("Order request received: orderId={}, paymentType={}", request.orderId(), request.paymentType());

        String path = paymentPath(request.paymentType(), request.orderId());
        long startTime = System.currentTimeMillis();
        AtomicInteger attempts = new AtomicInteger(0);
        AtomicBoolean paymentCalled = new AtomicBoolean(false);

        try {
            PaymentResponse payment = callWithResilience(request.orderId(), path, attempts, paymentCalled);

            long responseTime = System.currentTimeMillis() - startTime;
            String mechanism = attempts.get() > 1 ? "RETRY" : "NONE";
            log.info("Payment response received: orderId={}, status={}", request.orderId(), payment.status());
            log.info("Order completed: orderId={}", request.orderId());

            return new OrderResponse(request.orderId(), payment.status(), 200, responseTime,
                    "Order processed successfully", mechanism, attempts.get(), currentCircuitState(),
                    paymentCalled.get());

        } catch (CallNotPermittedException e) {
            long responseTime = System.currentTimeMillis() - startTime;
            log.error("Fallback executed: orderId={}, reason=circuit-breaker-open", request.orderId());

            return new OrderResponse(request.orderId(), "FALLBACK", 0, responseTime,
                    "Payment service temporarily unavailable", "CIRCUIT_BREAKER", attempts.get(),
                    currentCircuitState(), paymentCalled.get());

        } catch (TimeoutException e) {
            long responseTime = System.currentTimeMillis() - startTime;
            log.error("Fallback executed: orderId={}, reason=timeout", request.orderId());

            return new OrderResponse(request.orderId(), "FALLBACK", 0, responseTime,
                    "Payment service temporarily unavailable", "TIMEOUT", attempts.get(),
                    currentCircuitState(), paymentCalled.get());

        } catch (Exception e) {
            long responseTime = System.currentTimeMillis() - startTime;
            String mechanism = attempts.get() > 1 ? "RETRY" : "FALLBACK";
            log.error("Fallback executed: orderId={}, reason={}", request.orderId(), e.getClass().getSimpleName());

            return new OrderResponse(request.orderId(), "FALLBACK", 0, responseTime,
                    "Payment service temporarily unavailable", mechanism, attempts.get(),
                    currentCircuitState(), paymentCalled.get());
        }
    }

    // This is where Retry, Circuit Breaker and Timeout actually wrap the call, in that order:
    // paymentRetry (outer) -> paymentCircuitBreaker (middle) -> paymentTimeout (inner) -> RestClient.
    // Each retry attempt is its own circuit breaker call, and each circuit breaker call is its
    // own timeout-guarded RestClient request.
    private PaymentResponse callWithResilience(String orderId, String path, AtomicInteger attempts,
            AtomicBoolean paymentCalled) throws Exception {
        return paymentRetry.executeCallable(() -> {
            int attempt = attempts.incrementAndGet();
            if (attempt == 1) {
                log.info("Payment attempt: orderId={}, attempt={}", orderId, attempt);
            } else {
                log.warn("Retrying payment: orderId={}, attempt={}", orderId, attempt);
            }

            try {
                return paymentCircuitBreaker.executeCallable(() -> {
                    paymentCalled.set(true);

                    // Run the blocking RestClient call on a separate thread so paymentTimeout can
                    // enforce a time limit on it without waiting for the call itself to return.
                    CompletableFuture<PaymentResponse> future = CompletableFuture.supplyAsync(
                            () -> paymentServiceClient.get().uri(path).retrieve().body(PaymentResponse.class),
                            paymentExecutor);

                    try {
                        return paymentTimeout.executeFutureSupplier(() -> future);
                    } catch (TimeoutException e) {
                        log.warn("Payment call timed out: orderId={}, attempt={}", orderId, attempt);
                        throw e;
                    }
                });
            } catch (CallNotPermittedException e) {
                log.warn("Payment call rejected: circuit breaker OPEN, orderId={}, attempt={}", orderId, attempt);
                throw e;
            }
        });
    }

    private String currentCircuitState() {
        return paymentCircuitBreaker.getState().name();
    }

    // Chooses which Payment Service endpoint to hit based on the demo behavior picked in the UI.
    private String paymentPath(String paymentType, String orderId) {
        String type = paymentType == null ? "NORMAL" : paymentType.toUpperCase();
        return switch (type) {
            case "FAILURE" -> "/payments/failure/" + orderId;
            case "SLOW" -> "/payments/slow/" + orderId;
            default -> "/payments/" + orderId;
        };
    }
}
