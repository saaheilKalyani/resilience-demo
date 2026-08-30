package com.resilience.demo.order.controller;

import com.resilience.demo.order.service.OrderService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

// Entry point that the browser UI talks to. It delegates straight to OrderService,
// which is where the actual call to Payment Service happens.
@RestController
@CrossOrigin(origins = "*") // allow the local static UI (served from a different origin) to call this API
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping("/orders")
    public OrderResponse createOrder(@RequestBody OrderRequest request) {
        return orderService.createOrder(request);
    }
}
