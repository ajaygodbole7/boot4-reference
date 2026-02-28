package com.example.boot4ref.order.exception;

import com.example.boot4ref.common.exception.ResourceNotFoundException;

/**
 * Thrown when an order is not found. Leaf exception -- no further extension.
 */
public final class OrderNotFoundException extends ResourceNotFoundException {

    public OrderNotFoundException(Long id) {
        super("Order not found with id: " + id);
    }
}
