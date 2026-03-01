package com.example.boot4ref.product;

/**
 * Compile-time constants for Product validation bounds.
 * Shared across Create, Update, and Patch request DTOs.
 */
public final class ProductConstraints {

    public static final int NAME_MIN_LENGTH = 3;
    public static final int NAME_MAX_LENGTH = 100;
    public static final int DESCRIPTION_MAX_LENGTH = 500;

    private ProductConstraints() {}
}
