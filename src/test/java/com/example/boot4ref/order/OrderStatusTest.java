package com.example.boot4ref.order;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrderStatusTest {

    // --- PLACED transitions ---

    @Test
    void shouldAllowTransitionFromPlacedToConfirmed() {
        assertThat(OrderStatus.PLACED.canTransitionTo(OrderStatus.CONFIRMED)).isTrue();
    }

    @Test
    void shouldAllowTransitionFromPlacedToCancelled() {
        assertThat(OrderStatus.PLACED.canTransitionTo(OrderStatus.CANCELLED)).isTrue();
    }

    @Test
    void shouldRejectTransitionFromPlacedToShipped() {
        assertThat(OrderStatus.PLACED.canTransitionTo(OrderStatus.SHIPPED)).isFalse();
    }

    @Test
    void shouldRejectTransitionFromPlacedToDelivered() {
        assertThat(OrderStatus.PLACED.canTransitionTo(OrderStatus.DELIVERED)).isFalse();
    }

    // --- CONFIRMED transitions ---

    @Test
    void shouldAllowTransitionFromConfirmedToShipped() {
        assertThat(OrderStatus.CONFIRMED.canTransitionTo(OrderStatus.SHIPPED)).isTrue();
    }

    @Test
    void shouldAllowTransitionFromConfirmedToCancelled() {
        assertThat(OrderStatus.CONFIRMED.canTransitionTo(OrderStatus.CANCELLED)).isTrue();
    }

    @Test
    void shouldRejectTransitionFromConfirmedToPlaced() {
        assertThat(OrderStatus.CONFIRMED.canTransitionTo(OrderStatus.PLACED)).isFalse();
    }

    @Test
    void shouldRejectTransitionFromConfirmedToDelivered() {
        assertThat(OrderStatus.CONFIRMED.canTransitionTo(OrderStatus.DELIVERED)).isFalse();
    }

    // --- SHIPPED transitions ---

    @Test
    void shouldAllowTransitionFromShippedToDelivered() {
        assertThat(OrderStatus.SHIPPED.canTransitionTo(OrderStatus.DELIVERED)).isTrue();
    }

    @Test
    void shouldRejectTransitionFromShippedToCancelled() {
        assertThat(OrderStatus.SHIPPED.canTransitionTo(OrderStatus.CANCELLED)).isFalse();
    }

    @Test
    void shouldRejectTransitionFromShippedToPlaced() {
        assertThat(OrderStatus.SHIPPED.canTransitionTo(OrderStatus.PLACED)).isFalse();
    }

    // --- DELIVERED transitions (terminal) ---

    @Test
    void shouldRejectAllTransitionsFromDelivered() {
        for (OrderStatus target : OrderStatus.values()) {
            assertThat(OrderStatus.DELIVERED.canTransitionTo(target)).isFalse();
        }
    }

    // --- CANCELLED transitions (terminal) ---

    @Test
    void shouldRejectAllTransitionsFromCancelled() {
        for (OrderStatus target : OrderStatus.values()) {
            assertThat(OrderStatus.CANCELLED.canTransitionTo(target)).isFalse();
        }
    }
}
