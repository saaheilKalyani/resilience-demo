package com.resilience.demo.payment.controller;

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

    @GetMapping("/payments/{orderId}")
    public PaymentResponse pay(@PathVariable String orderId) {
        log.info("Processing payment for orderId={}", orderId);
        return new PaymentResponse(orderId, "SUCCESS");
    }

    // Simulates a downstream failure (HTTP 500) so we can later demonstrate
    // retry, fallback and circuit breaker behavior in Order Service.
    @GetMapping("/payments/failure/{orderId}")
    public ResponseEntity<PaymentResponse> payFailure(@PathVariable String orderId) {
        log.error("Simulating payment failure for orderId={}", orderId);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new PaymentResponse(orderId, "FAILED"));
    }

    // Simulates a slow downstream call so we can later demonstrate timeouts.
    @GetMapping("/payments/slow/{orderId}")
    public PaymentResponse paySlow(@PathVariable String orderId) throws InterruptedException {
        log.warn("Simulating slow payment for orderId={}", orderId);
        Thread.sleep(5000);
        return new PaymentResponse(orderId, "SUCCESS");
    }
}
