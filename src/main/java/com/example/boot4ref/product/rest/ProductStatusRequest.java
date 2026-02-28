package com.example.boot4ref.product.rest;

import com.example.boot4ref.product.ProductStatus;
import jakarta.validation.constraints.NotNull;

/**
 * Request DTO for product status transition.
 * Used by POST /api/products/{id}/status to enforce lifecycle state machine.
 */
public record ProductStatusRequest(
        @NotNull(message = "Status is required")
        ProductStatus status
) {}
