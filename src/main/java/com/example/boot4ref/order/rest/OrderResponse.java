package com.example.boot4ref.order.rest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Response DTO for order data with nested line items.
 */
public record OrderResponse(
        Long id,
        String status,
        BigDecimal totalAmount,
        @Nullable String idempotencyKey,
        List<OrderLineResponse> items,
        Instant createdAt,
        Instant updatedAt
) {}
