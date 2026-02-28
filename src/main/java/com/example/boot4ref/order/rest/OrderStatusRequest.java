package com.example.boot4ref.order.rest;

import com.example.boot4ref.order.OrderStatus;
import jakarta.validation.constraints.NotNull;

/**
 * Request DTO for transitioning an order's status.
 */
public record OrderStatusRequest(
        @NotNull(message = "Status is required")
        OrderStatus status
) {}
