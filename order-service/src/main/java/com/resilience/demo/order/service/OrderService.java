package com.resilience.demo.order.service;

import com.resilience.demo.order.controller.OrderRequest;
import com.resilience.demo.order.controller.OrderResponse;
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
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final RestClient paymentServiceClient;
    private final Retry paymentRetry;
    private final TimeLimiter paymentTimeout;
    private final ExecutorService paymentExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public OrderService(
            @Value("${payment.service.url}") String paymentServiceUrl,
            @Value("${payment.retry.max-attempts:3}") int retryMaxAttempts,
            @Value("${payment.retry.wait-duration-ms:500}") long retryWaitMs,
            @Value("${payment.timeout.duration-ms:2000}") long timeoutMs) {

        this.paymentServiceClient = RestClient.builder()
                .baseUrl(paymentServiceUrl)
                .build();

        // Retry only on a Payment Service 5xx response - a real server-side failure.
        // Deliberately NOT retried: timeouts (handled by paymentTimeout below) and connection
        // errors, so we don't blindly retry every possible failure.
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
    }

    @PreDestroy
    void shutdown() {
        paymentExecutor.shutdown();
    }

    public OrderResponse createOrder(OrderRequest request) {
        log.info("Order request received: orderId={}, paymentType={}", request.orderId(), request.paymentType());

        String path = paymentPath(request.paymentType(), request.orderId());
        long startTime = System.currentTimeMillis();
        AtomicInteger attempts = new AtomicInteger(0);

        try {
            PaymentResponse payment = callWithResilience(request.orderId(), path, attempts);

            long responseTime = System.currentTimeMillis() - startTime;
            String mechanism = attempts.get() > 1 ? "RETRY" : "NONE";
            log.info("Payment response received: orderId={}, status={}", request.orderId(), payment.status());
            log.info("Order completed: orderId={}", request.orderId());

            return new OrderResponse(request.orderId(), payment.status(), 200, responseTime,
                    "Order processed successfully", mechanism, attempts.get());

        } catch (TimeoutException e) {
            long responseTime = System.currentTimeMillis() - startTime;
            log.error("Fallback executed: orderId={}, reason=timeout", request.orderId());

            return new OrderResponse(request.orderId(), "FALLBACK", 0, responseTime,
                    "Payment service temporarily unavailable", "TIMEOUT", attempts.get());

        } catch (Exception e) {
            long responseTime = System.currentTimeMillis() - startTime;
            String mechanism = attempts.get() > 1 ? "RETRY" : "FALLBACK";
            log.error("Fallback executed: orderId={}, reason={}", request.orderId(), e.getClass().getSimpleName());

            return new OrderResponse(request.orderId(), "FALLBACK", 0, responseTime,
                    "Payment service temporarily unavailable", mechanism, attempts.get());
        }
    }

    // This is where Retry (paymentRetry) and Timeout (paymentTimeout) actually wrap the call.
    private PaymentResponse callWithResilience(String orderId, String path, AtomicInteger attempts) throws Exception {
        return paymentRetry.executeCallable(() -> {
            int attempt = attempts.incrementAndGet();
            if (attempt == 1) {
                log.info("Payment attempt: orderId={}, attempt={}", orderId, attempt);
            } else {
                log.warn("Retrying payment: orderId={}, attempt={}", orderId, attempt);
            }

            // Run the blocking RestClient call on a separate thread so paymentTimeout can enforce
            // a time limit on it without waiting for the call itself to return.
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
