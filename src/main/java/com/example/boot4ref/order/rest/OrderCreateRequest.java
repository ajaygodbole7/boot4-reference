package com.example.boot4ref.order.rest;

import com.example.boot4ref.order.OrderConstraints;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Request DTO for creating an order.
 * Must contain at least one line item.
 */
public record OrderCreateRequest(
        @NotEmpty(message = "Order must have at least one line item")
        @Size(max = OrderConstraints.MAX_LINE_ITEMS, message = "Order must not exceed " + OrderConstraints.MAX_LINE_ITEMS + " line items")
        @Valid
        List<OrderLineRequest> items
) {}
