package com.example.boot4ref.order.exception;

import com.example.boot4ref.common.exception.BusinessRuleException;
import com.example.boot4ref.product.ProductStatus;

/**
 * Thrown when an order references a product that is not ACTIVE.
 */
public final class UnorderableProductException extends BusinessRuleException {

    public UnorderableProductException(Long productId, ProductStatus status) {
        super("Product " + productId + " is " + status + " and cannot be ordered");
    }
}
