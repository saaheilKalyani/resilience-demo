package com.resilience.demo.order.service;

// Mirrors the JSON shape returned by Payment Service. Order Service is deployed
// independently, so it keeps its own small copy instead of sharing a module.
public record PaymentResponse(String orderId, String status) {
}
