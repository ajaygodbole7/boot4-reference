package com.example.boot4ref.order.rest;

import com.example.boot4ref.order.OrderStatus;
import com.example.boot4ref.order.service.OrderService;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Order REST controller. Delegates all logic to {@link OrderService}.
 *
 * <p>No Lombok -- manual Logger per CLAUDE.md (Lombok restricted to @Entity classes).
 * OpenAPI annotations live on {@link OrderApi} -- this class stays focused on HTTP mapping.
 */
@RestController
public class OrderController implements OrderApi {

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @Override
    public ResponseEntity<OrderResponse> createOrder(@Nullable String idempotencyKey, OrderCreateRequest request) {
        OrderResponse created = orderService.create(request, idempotencyKey);
        URI location = URI.create("/api/orders/" + created.id());
        return ResponseEntity.created(location).body(created);
    }

    @Override
    public ResponseEntity<OrderResponse> getOrder(Long id) {
        return ResponseEntity.ok(orderService.findById(id));
    }

    @Override
    public ResponseEntity<List<OrderResponse>> listOrders(
            @Nullable OrderStatus status,
            @Nullable Instant afterCreatedAt,
            @Nullable Long afterId,
            @Nullable Integer limit) {
        return ResponseEntity.ok(
                orderService.listFiltered(status, afterCreatedAt, afterId, limit));
    }

    @Override
    public ResponseEntity<OrderResponse> transitionStatus(Long id, OrderStatusRequest request) {
        return ResponseEntity.ok(orderService.transition(id, request.status()));
    }
}
