package com.example.boot4ref.product.rest;

import com.example.boot4ref.product.ProductConstraints;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

/**
 * Request DTO for partial product updates.
 *
 * <p>All fields are optional ({@code @Nullable}). Only non-null fields are applied;
 * null fields leave the existing product value unchanged.
 *
 * <p>Validation annotations apply only when the field is present (non-null).
 */
public record ProductPatchRequest(
        @Nullable
        @Size(min = ProductConstraints.NAME_MIN_LENGTH, max = ProductConstraints.NAME_MAX_LENGTH,
                message = "Product name must be between 3 and 100 characters")
        String name,

        @Nullable
        @Size(max = ProductConstraints.DESCRIPTION_MAX_LENGTH,
                message = "Description must not exceed 500 characters")
        String description,

        @Nullable
        @Positive(message = "Price must be positive")
        BigDecimal price,

        @Nullable
        @PositiveOrZero(message = "Stock cannot be negative")
        Integer stock
) {}
