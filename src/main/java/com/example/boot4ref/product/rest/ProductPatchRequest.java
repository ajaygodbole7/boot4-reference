package com.example.boot4ref.product.rest;

import com.example.boot4ref.product.ProductConstraints;
import jakarta.validation.constraints.Digits;
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
 * <p>To explicitly clear the description to null, set {@code "clearDescription": true}.
 * This is necessary because JSON null is ambiguous (absent vs explicit null) in the
 * null-as-absent pattern used by the other fields.
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
        @Digits(integer = ProductConstraints.PRICE_INTEGER_DIGITS,
                fraction = ProductConstraints.PRICE_FRACTION_DIGITS,
                message = "Price must have at most 15 integer and 4 fraction digits")
        BigDecimal price,

        @Nullable
        @PositiveOrZero(message = "Stock cannot be negative")
        Integer stock,

        @Nullable
        Boolean clearDescription
) {}
