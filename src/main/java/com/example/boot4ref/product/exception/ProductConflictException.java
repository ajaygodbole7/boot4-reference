package com.example.boot4ref.product.exception;

import com.example.boot4ref.common.exception.ResourceConflictException;

/**
 * Thrown when a product operation causes a conflict. Leaf exception -- no further extension.
 */
public final class ProductConflictException extends ResourceConflictException {

    public ProductConflictException(String message) {
        super(message);
    }
}
