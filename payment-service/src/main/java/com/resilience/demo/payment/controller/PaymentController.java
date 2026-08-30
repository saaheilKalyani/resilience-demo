package com.resilience.demo.payment.controller;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

// Payment Service simulates three behaviors so Order Service has something
// interesting to call: a normal success, a hard failure, and a slow response.
@RestController
public class PaymentController {

    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);

    private final Counter paymentRequests;
    private final Counter paymentSuccessful;
    private final Counter paymentFailed;

    public PaymentController(MeterRegistry meterRegistry) {
        this.paymentRequests = Counter.builder("payment.requests")
                .description("Total payment requests received")
                .register(meterRegistry);
        this.paymentSuccessful = Counter.builder("payment.successful")
                .description("Payment requests that returned SUCCESS")
                .register(meterRegistry);
        this.paymentFailed = Counter.builder("payment.failed")
                .description("Payment requests that returned FAILED")
                .register(meterRegistry);
    }

    @GetMapping("/payments/{orderId}")
    public PaymentResponse pay(@PathVariable String orderId) {
        log.info("Payment request received: orderId={}", orderId);
        paymentRequests.increment();
        log.info("Payment successful: orderId={}", orderId);
        paymentSuccessful.increment();
        return new PaymentResponse(orderId, "SUCCESS");
    }

    // Simulates a downstream failure (HTTP 500) so we can later demonstrate
    // retry, fallback and circuit breaker behavior in Order Service.
    // ERROR because this represents a real failed payment, not just a slow one.
    @GetMapping("/payments/failure/{orderId}")
    public ResponseEntity<PaymentResponse> payFailure(@PathVariable String orderId) {
        paymentRequests.increment();
        log.error("Simulated payment failure: orderId={}", orderId);
        paymentFailed.increment();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new PaymentResponse(orderId, "FAILED"));
    }

    // Simulates a slow downstream call so we can later demonstrate timeouts.
    // INFO because a slow response isn't an error by itself, just a normal event worth tracking.
    @GetMapping("/payments/slow/{orderId}")
    public PaymentResponse paySlow(@PathVariable String orderId) throws InterruptedException {
        log.info("Slow payment request received: orderId={}", orderId);
        paymentRequests.increment();
        Thread.sleep(5000);
        log.info("Slow payment completed: orderId={}", orderId);
        paymentSuccessful.increment();
        return new PaymentResponse(orderId, "SUCCESS");
    }
}
