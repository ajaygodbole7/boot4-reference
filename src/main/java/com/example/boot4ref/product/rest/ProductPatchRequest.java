package com.example.boot4ref.product.rest;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

/**
 * Request DTO for JSON Merge Patch (RFC 7396) partial updates to a product.
 *
 * <p>All fields are optional ({@code @Nullable}). Only non-null fields are applied;
 * null fields leave the existing product value unchanged. This is the merge-patch
 * semantics: the absence of a field in the patch means "do not change this field".
 *
 * <p>Validation annotations apply only when the field is present (non-null).
 * Content-Type for PATCH requests must be {@code application/merge-patch+json}.
 */
public record ProductPatchRequest(
        @Nullable
        @Size(min = 3, max = 100, message = "Product name must be between 3 and 100 characters")
        String name,

        @Nullable
        @Size(max = 500, message = "Description must not exceed 500 characters")
        String description,

        @Nullable
        @Positive(message = "Price must be positive")
        BigDecimal price,

        @Nullable
        @PositiveOrZero(message = "Stock cannot be negative")
        Integer stock
) {}
