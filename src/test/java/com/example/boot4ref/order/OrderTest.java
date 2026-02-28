package com.example.boot4ref.order;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class OrderTest {

    @Test
    void shouldAddOrderLineWithBidirectionalSync() {
        Order order = Order.builder().status(OrderStatus.PLACED).build();
        OrderLine line = OrderLine.builder()
                .quantity(2)
                .unitPrice(new BigDecimal("10.00"))
                .build();

        order.addOrderLine(line);

        assertThat(order.getOrderLines()).containsExactly(line);
        assertThat(line.getOrder()).isSameAs(order);
    }

    @Test
    void shouldRemoveOrderLineWithBidirectionalSync() {
        Order order = Order.builder().status(OrderStatus.PLACED).build();
        OrderLine line = OrderLine.builder()
                .quantity(1)
                .unitPrice(new BigDecimal("5.00"))
                .build();
        order.addOrderLine(line);

        order.removeOrderLine(line);

        assertThat(order.getOrderLines()).isEmpty();
        assertThat(line.getOrder()).isNull();
    }

    @Test
    void shouldComputeTotalFromOrderLines() {
        Order order = Order.builder().status(OrderStatus.PLACED).build();
        order.addOrderLine(OrderLine.builder()
                .quantity(2)
                .unitPrice(new BigDecimal("10.50"))
                .build());
        order.addOrderLine(OrderLine.builder()
                .quantity(3)
                .unitPrice(new BigDecimal("5.00"))
                .build());

        order.computeTotal();

        // 2 * 10.50 + 3 * 5.00 = 21.00 + 15.00 = 36.00
        assertThat(order.getTotalAmount()).isEqualByComparingTo(new BigDecimal("36.00"));
    }

    @Test
    void shouldComputeZeroTotalWhenNoOrderLines() {
        Order order = Order.builder().status(OrderStatus.PLACED).build();

        order.computeTotal();

        assertThat(order.getTotalAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
