package com.example.boot4ref.order.service;

import com.example.boot4ref.AbstractIntegrationTest;
import com.example.boot4ref.common.fixture.ProductFixtures;
import com.example.boot4ref.order.OrderStatus;
import com.example.boot4ref.order.rest.OrderCreateRequest;
import com.example.boot4ref.order.rest.OrderLineRequest;
import com.example.boot4ref.product.Product;
import com.example.boot4ref.product.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link OrderService} business logic against a real database.
 */
class OrderServiceIT extends AbstractIntegrationTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private ProductRepository productRepository;

    @Test
    void shouldRestoreStockWhenOrderIsCancelled() {
        Product product = ProductFixtures.randomActiveProduct();
        product.setStock(100);
        product.setPrice(new BigDecimal("10.00"));
        product = productRepository.save(product);

        // Place order for 10 units
        OrderCreateRequest request = new OrderCreateRequest(
                List.of(new OrderLineRequest(product.getId(), 10)));
        var result = orderService.create(request, "cancel-stock-test");
        Long orderId = result.order().id();

        // Verify stock decremented
        Product afterOrder = productRepository.findById(product.getId()).orElseThrow();
        assertThat(afterOrder.getStock()).isEqualTo(90);

        // Cancel the order
        orderService.transition(orderId, OrderStatus.CANCELLED);

        // Verify stock restored
        Product afterCancel = productRepository.findById(product.getId()).orElseThrow();
        assertThat(afterCancel.getStock()).isEqualTo(100);
    }
}
