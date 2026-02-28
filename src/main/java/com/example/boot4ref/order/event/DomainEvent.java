package com.example.boot4ref.order.event;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Sealed event hierarchy for order domain events.
 * Exhaustive switch in outbox publisher ensures all event types are handled.
 */
public sealed interface DomainEvent {

    Long orderId();
    Instant timestamp();

    record OrderPlaced(Long orderId, Instant timestamp, BigDecimal totalAmount, int lineCount)
            implements DomainEvent {}

    record OrderConfirmed(Long orderId, Instant timestamp)
            implements DomainEvent {}

    record OrderShipped(Long orderId, Instant timestamp)
            implements DomainEvent {}

    record OrderDelivered(Long orderId, Instant timestamp)
            implements DomainEvent {}

    record OrderCancelled(Long orderId, Instant timestamp)
            implements DomainEvent {}
}
