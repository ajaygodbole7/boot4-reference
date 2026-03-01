package com.example.boot4ref.order.event;

/**
 * Event type string constants for the order domain.
 * Used in outbox event creation and event routing.
 */
public final class EventTypes {

    public static final String ORDER_PLACED = "Order::placed";
    public static final String ORDER_CONFIRMED = "Order::confirmed";
    public static final String ORDER_SHIPPED = "Order::shipped";
    public static final String ORDER_DELIVERED = "Order::delivered";
    public static final String ORDER_CANCELLED = "Order::cancelled";

    private EventTypes() {}
}
