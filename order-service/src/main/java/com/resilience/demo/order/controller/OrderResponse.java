package com.resilience.demo.order.controller;

public record OrderResponse(
        String orderId,
        String paymentStatus,
        int paymentHttpStatus,
        long responseTimeMs,
        String message,
        // which resilience behavior kicked in: NONE, RETRY, TIMEOUT, CIRCUIT_BREAKER or FALLBACK
        String mechanism,
        int attempts,               // how many retry attempts were made for this request
        String circuitState,        // paymentCircuitBreaker's state at the end of this call
        boolean paymentServiceCalled // false only when the circuit breaker rejected every attempt
) {
}
