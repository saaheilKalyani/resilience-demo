package com.resilience.demo.order.controller;

public record OrderResponse(
        String orderId,
        String paymentStatus,
        int paymentHttpStatus,
        long responseTimeMs,
        String message
) {
}
