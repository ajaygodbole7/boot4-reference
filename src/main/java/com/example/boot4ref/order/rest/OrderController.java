package com.example.boot4ref.order.rest;

import com.example.boot4ref.order.OrderStatus;
import com.example.boot4ref.order.service.OrderCreateResult;
import com.example.boot4ref.order.service.OrderService;
import java.net.URI;
import java.util.List;
import org.hibernate.exception.ConstraintViolationException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

/**
 * Order REST controller. Delegates all logic to {@link OrderService}.
 *
 * <p>No Lombok -- manual Logger per CLAUDE.md (Lombok restricted to @Entity classes).
 * OpenAPI annotations live on {@link OrderApi} -- this class stays focused on HTTP mapping.
 *
 * <p>{@code @Validated} enables method-level constraint validation (e.g., {@code @Size}
 * on the Idempotency-Key header declared in {@link OrderApi}).
 */
@RestController
@Validated
public class OrderController implements OrderApi {

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);
    private static final String IDEMPOTENCY_KEY_CONSTRAINT = "idempotency_key";

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @Override
    public ResponseEntity<OrderResponse> createOrder(@Nullable String idempotencyKey, OrderCreateRequest request) {
        OrderCreateResult result;
        try {
            result = orderService.create(request, idempotencyKey);
        } catch (DataIntegrityViolationException ex) {
            // Only recover from idempotency key constraint violations.
            // Other constraint violations (FK, null) propagate to ExceptionTranslator (409).
            if (idempotencyKey != null && isIdempotencyKeyViolation(ex)) {
                log.warn("Idempotency key race detected for key={}: {}", idempotencyKey, ex.getMessage());
                return ResponseEntity.ok(orderService.findByIdempotencyKey(idempotencyKey));
            }
            throw ex;
        }
        if (result.newlyCreated()) {
            URI location = URI.create("/api/orders/" + result.order().id());
            return ResponseEntity.created(location).body(result.order());
        }
        return ResponseEntity.ok(result.order());
    }

    @Override
    public ResponseEntity<OrderResponse> getOrder(Long id) {
        return ResponseEntity.ok(orderService.findById(id));
    }

    @Override
    public ResponseEntity<List<OrderResponse>> listOrders(
            @Nullable OrderStatus status,
            @Nullable Long afterId,
            @Nullable Integer limit) {
        return ResponseEntity.ok(
                orderService.listFiltered(status, afterId, limit));
    }

    @Override
    public ResponseEntity<OrderResponse> transitionStatus(Long id, OrderStatusRequest request) {
        return ResponseEntity.ok(orderService.transition(id, request.status()));
    }

    private static boolean isIdempotencyKeyViolation(DataIntegrityViolationException ex) {
        if (ex.getCause() instanceof ConstraintViolationException cve) {
            String constraintName = cve.getConstraintName();
            return constraintName != null && constraintName.contains(IDEMPOTENCY_KEY_CONSTRAINT);
        }
        return false;
    }
}
