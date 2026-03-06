package com.example.boot4ref.order.exception;

import com.example.boot4ref.common.exception.BusinessRuleException;
import com.example.boot4ref.common.exception.ProblemPropertySource;
import com.example.boot4ref.common.exception.ProblemType;
import java.util.Map;

/**
 * Thrown when an order contains duplicate product IDs in its line items.
 */
@ProblemType(slug = "duplicate-line-item", title = "Duplicate Line Item")
public final class DuplicateLineItemException extends BusinessRuleException
        implements ProblemPropertySource {

    private final Long productId;

    public DuplicateLineItemException(Long productId) {
        super("Duplicate product ID " + productId + " in order items");
        this.productId = productId;
    }

    @Override
    public Map<String, Object> problemProperties() {
        return Map.of("productId", productId);
    }
}
