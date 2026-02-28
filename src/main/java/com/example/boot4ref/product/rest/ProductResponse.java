package com.example.boot4ref.product.rest;

import java.math.BigDecimal;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Response DTO for product data.
 */
public record ProductResponse(
        Long id,
        String name,
        @Nullable String description,
        BigDecimal price,
        Integer stock,
        String status,
        Instant createdAt,
        Instant updatedAt
) {}
