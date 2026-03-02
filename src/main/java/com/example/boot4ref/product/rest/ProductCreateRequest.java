package com.example.boot4ref.product.rest;

import com.example.boot4ref.product.ProductConstraints;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

/**
 * Request DTO for creating a product.
 * Bean Validation annotations trigger MethodArgumentNotValidException
 * when invalid, handled by ExceptionTranslator.
 */
public record ProductCreateRequest(
        @NotBlank(message = "Product name is required")
        @Size(min = ProductConstraints.NAME_MIN_LENGTH, max = ProductConstraints.NAME_MAX_LENGTH,
                message = "Product name must be between 3 and 100 characters")
        String name,

        @Nullable
        @Size(max = ProductConstraints.DESCRIPTION_MAX_LENGTH,
                message = "Description must not exceed 500 characters")
        String description,

        @NotNull(message = "Price is required")
        @Positive(message = "Price must be positive")
        @Digits(integer = ProductConstraints.PRICE_INTEGER_DIGITS,
                fraction = ProductConstraints.PRICE_FRACTION_DIGITS,
                message = "Price must have at most 15 integer and 4 fraction digits")
        BigDecimal price,

        @NotNull(message = "Initial stock is required")
        @PositiveOrZero(message = "Stock cannot be negative")
        Integer stock
) {}
