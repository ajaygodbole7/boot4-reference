package com.example.boot4ref.order.exception;

import com.example.boot4ref.common.exception.BusinessRuleException;

/**
 * Thrown when order quantity exceeds available product stock.
 */
public final class InsufficientStockException extends BusinessRuleException {

    public InsufficientStockException(Long productId, int requested, int available) {
        super("Insufficient stock for product " + productId
                + ": requested " + requested + ", available " + available);
    }
}
