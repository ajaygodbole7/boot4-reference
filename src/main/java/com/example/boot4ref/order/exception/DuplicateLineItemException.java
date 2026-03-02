package com.example.boot4ref.order.exception;

import com.example.boot4ref.common.exception.BusinessRuleException;

/**
 * Thrown when an order contains duplicate product IDs in its line items.
 */
public final class DuplicateLineItemException extends BusinessRuleException {

    public DuplicateLineItemException(Long productId) {
        super("Duplicate product ID " + productId + " in order items");
    }
}
