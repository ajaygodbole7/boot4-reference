package com.example.boot4ref.order.exception;

import com.example.boot4ref.common.exception.ResourceConflictException;

/**
 * Thrown when an order operation causes a conflict. Leaf exception -- no further extension.
 */
public final class OrderConflictException extends ResourceConflictException {

    public OrderConflictException(String message) {
        super(message);
    }
}
