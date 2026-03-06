package com.example.boot4ref.order.exception;

import com.example.boot4ref.common.exception.BusinessRuleException;
import com.example.boot4ref.common.exception.ProblemPropertySource;
import com.example.boot4ref.common.exception.ProblemType;
import java.util.Map;

/**
 * Thrown when order quantity exceeds available product stock.
 */
@ProblemType(slug = "insufficient-stock", title = "Insufficient Stock")
public final class InsufficientStockException extends BusinessRuleException
        implements ProblemPropertySource {

    private final Long productId;
    private final int requested;
    private final int available;

    public InsufficientStockException(Long productId, int requested, int available) {
        super("Insufficient stock for product " + productId
                + ": requested " + requested + ", available " + available);
        this.productId = productId;
        this.requested = requested;
        this.available = available;
    }

    @Override
    public Map<String, Object> problemProperties() {
        return Map.of("productId", productId, "requested", requested, "available", available);
    }
}
