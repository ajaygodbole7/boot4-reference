package com.example.boot4ref.product.exception;

import com.example.boot4ref.common.exception.ProblemType;
import com.example.boot4ref.common.exception.ResourceNotFoundException;

/**
 * Thrown when a product is not found. Leaf exception -- no further extension.
 */
@ProblemType(slug = "product-not-found", title = "Product Not Found")
public final class ProductNotFoundException extends ResourceNotFoundException {

    public ProductNotFoundException(Long id) {
        super("Product not found with id: " + id);
    }
}
