package com.example.boot4ref.order;

/**
 * Compile-time constants for Order validation bounds.
 * Shared across request DTOs.
 */
public final class OrderConstraints {

    public static final int MAX_QUANTITY = 10_000;
    public static final int MAX_LINE_ITEMS = 50;

    private OrderConstraints() {}
}
