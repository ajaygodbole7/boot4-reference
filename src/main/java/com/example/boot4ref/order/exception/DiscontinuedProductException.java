package com.example.boot4ref.order.exception;

import com.example.boot4ref.common.exception.BusinessRuleException;

/**
 * Thrown when an order references a DISCONTINUED product.
 */
public final class DiscontinuedProductException extends BusinessRuleException {

    public DiscontinuedProductException(Long productId) {
        super("Product " + productId + " is discontinued and cannot be ordered");
    }
}
