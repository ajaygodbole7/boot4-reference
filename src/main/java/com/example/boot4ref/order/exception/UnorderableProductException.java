package com.example.boot4ref.order.exception;

import com.example.boot4ref.common.exception.BusinessRuleException;
import com.example.boot4ref.common.exception.ProblemPropertySource;
import com.example.boot4ref.common.exception.ProblemType;
import com.example.boot4ref.product.ProductStatus;
import java.util.Map;

/**
 * Thrown when an order references a product that is not ACTIVE.
 */
@ProblemType(slug = "unorderable-product", title = "Unorderable Product")
public final class UnorderableProductException extends BusinessRuleException
        implements ProblemPropertySource {

    private final Long productId;
    private final ProductStatus status;

    public UnorderableProductException(Long productId, ProductStatus status) {
        super("Product " + productId + " is " + status + " and cannot be ordered");
        this.productId = productId;
        this.status = status;
    }

    @Override
    public Map<String, Object> problemProperties() {
        return Map.of("productId", productId, "productStatus", status.name());
    }
}
