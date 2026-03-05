package com.example.boot4ref.order.service;

import com.example.boot4ref.order.rest.OrderResponse;

/**
 * Result of an order creation attempt. Indicates whether a new order was
 * created or an existing one was returned via idempotency key match.
 */
public record OrderCreateResult(OrderResponse order, boolean newlyCreated) {}
