package com.example.boot4ref.order.rest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * Request DTO for creating an order.
 * Must contain at least one line item.
 */
public record OrderCreateRequest(
        @NotEmpty(message = "Order must have at least one line item")
        @Valid
        List<OrderLineRequest> items
) {}
