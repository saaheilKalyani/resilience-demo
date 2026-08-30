package com.resilience.demo.order.controller;

// paymentType selects which Payment Service behavior to call: NORMAL, FAILURE or SLOW.
// It is optional and defaults to NORMAL, so a plain {"orderId": "1001"} still works.
public record OrderRequest(String orderId, String paymentType) {
}
