package com.resilience.demo.order.controller;

public record OrderResponse(
        String orderId,
        String paymentStatus,
        int paymentHttpStatus,
        long responseTimeMs,
        String message,
        String mechanism, // which resilience behavior kicked in: NONE, RETRY, TIMEOUT or FALLBACK
        int attempts       // how many times Payment Service was actually called
) {
}
