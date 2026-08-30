package com.resilience.demo.order.service;

import com.resilience.demo.order.controller.OrderRequest;
import com.resilience.demo.order.controller.OrderResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final RestClient paymentServiceClient;

    public OrderService(@Value("${payment.service.url}") String paymentServiceUrl) {
        this.paymentServiceClient = RestClient.builder()
                .baseUrl(paymentServiceUrl)
                .build();
    }

    public OrderResponse createOrder(OrderRequest request) {
        log.info("Order request received: orderId={}, paymentType={}", request.orderId(), request.paymentType());

        String path = paymentPath(request.paymentType(), request.orderId());
        long startTime = System.currentTimeMillis();

        try {
            log.info("Calling Payment Service: GET {}", path);

            // This is the actual microservice-to-microservice HTTP call.
            PaymentResponse payment = paymentServiceClient.get()
                    .uri(path)
                    .retrieve()
                    .body(PaymentResponse.class);

            long responseTime = System.currentTimeMillis() - startTime;
            log.info("Payment response received: orderId={}, status={}", request.orderId(), payment.status());
            log.info("Order completed: orderId={}", request.orderId());

            return new OrderResponse(request.orderId(), payment.status(), 200, responseTime,
                    "Order processed successfully");

        } catch (RestClientResponseException e) {
            // Payment Service responded, but with an error status (e.g. 500 from /payments/failure/{orderId}).
            long responseTime = System.currentTimeMillis() - startTime;
            log.error("Payment Service returned an error for orderId={}: {}", request.orderId(), e.getMessage());

            return new OrderResponse(request.orderId(), "FAILED", e.getStatusCode().value(), responseTime,
                    "Payment failed");

        } catch (ResourceAccessException e) {
            // Payment Service could not be reached at all (e.g. connection refused).
            long responseTime = System.currentTimeMillis() - startTime;
            log.error("Could not reach Payment Service for orderId={}: {}", request.orderId(), e.getMessage());

            return new OrderResponse(request.orderId(), "ERROR", 0, responseTime,
                    "Payment service unavailable");
        }
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
