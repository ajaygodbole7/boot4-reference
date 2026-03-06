package com.example.boot4ref.product.exception;

import com.example.boot4ref.common.exception.ProblemType;
import com.example.boot4ref.common.exception.ResourceConflictException;

/**
 * Thrown when a product operation causes a conflict. Leaf exception -- no further extension.
 */
@ProblemType(slug = "product-conflict", title = "Product Conflict")
public final class ProductConflictException extends ResourceConflictException {

    public ProductConflictException(String message) {
        super(message);
    }
}
