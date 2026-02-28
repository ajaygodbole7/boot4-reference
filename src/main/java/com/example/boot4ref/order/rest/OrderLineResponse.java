package com.example.boot4ref.order.rest;

import java.math.BigDecimal;

/**
 * Response DTO for a single order line item.
 */
public record OrderLineResponse(
        Long id,
        Long productId,
        String productName,
        Integer quantity,
        BigDecimal unitPrice
) {}
