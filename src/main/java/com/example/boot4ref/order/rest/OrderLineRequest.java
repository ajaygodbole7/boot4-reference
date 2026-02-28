package com.example.boot4ref.order.rest;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Request DTO for a single order line item within an order creation request.
 */
public record OrderLineRequest(
        @NotNull(message = "Product ID is required")
        Long productId,

        @NotNull(message = "Quantity is required")
        @Positive(message = "Quantity must be positive")
        Integer quantity
) {}
