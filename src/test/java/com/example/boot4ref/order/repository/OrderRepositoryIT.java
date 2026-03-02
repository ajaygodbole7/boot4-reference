package com.example.boot4ref.order.repository;

import com.example.boot4ref.AbstractIntegrationTest;
import com.example.boot4ref.common.fixture.ProductFixtures;
import com.example.boot4ref.order.Order;
import com.example.boot4ref.order.OrderLine;
import com.example.boot4ref.order.OrderStatus;
import com.example.boot4ref.order.specification.OrderSpecifications;
import com.example.boot4ref.product.Product;
import com.example.boot4ref.product.ProductStatus;
import com.example.boot4ref.product.repository.ProductRepository;
import net.ttddyy.dsproxy.QueryCountHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for OrderRepository.
 * Runs against Testcontainers PostgreSQL via AbstractIntegrationTest.
 * datasource-proxy assertions verify query counts.
 */
class OrderRepositoryIT extends AbstractIntegrationTest {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private ProductRepository productRepository;

    private Product testProduct;

    @BeforeEach
    void setUp() {
        QueryCountHolder.clear();
        testProduct = productRepository.save(ProductFixtures.randomActiveProduct());
    }

    @Test
    void shouldPersistOrderWithLinesAndRetrieveViaJoinFetch() {
        Order order = Order.builder()
                .status(OrderStatus.PLACED)
                .build();
        OrderLine line = OrderLine.builder()
                .product(testProduct)
                .quantity(2)
                .unitPrice(testProduct.getPrice())
                .build();
        order.addOrderLine(line);
        order.computeTotal();

        Order saved = orderRepository.save(order);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getOrderLines()).hasSize(1);
        BigDecimal expectedTotal = testProduct.getPrice().multiply(BigDecimal.valueOf(2));
        assertThat(saved.getTotalAmount()).isEqualByComparingTo(expectedTotal);

        // Verify JOIN FETCH loads lines + product in single query
        QueryCountHolder.clear();
        Order found = orderRepository.findByIdWithLines(saved.getId()).orElseThrow();
        assertThat(found.getOrderLines()).hasSize(1);
        assertThat(found.getOrderLines().getFirst().getProduct().getName()).isEqualTo(testProduct.getName());

        long selectCount = QueryCountHolder.getGrandTotal().getSelect();
        assertThat(selectCount)
                .as("findByIdWithLines should execute exactly 1 SELECT (JOIN FETCH)")
                .isEqualTo(1);
    }

    @Test
    void shouldFindOrderByIdempotencyKey() {
        Order order = Order.builder()
                .status(OrderStatus.PLACED)
                .idempotencyKey("test-key-123")
                .build();
        order.addOrderLine(OrderLine.builder()
                .product(testProduct)
                .quantity(1)
                .unitPrice(testProduct.getPrice())
                .build());
        order.computeTotal();
        orderRepository.save(order);

        assertThat(orderRepository.findByIdempotencyKey("test-key-123")).isPresent();
        assertThat(orderRepository.findByIdempotencyKey("nonexistent")).isEmpty();
    }

    @Test
    void shouldFilterOrdersByStatus() {
        Order placed = Order.builder().status(OrderStatus.PLACED).build();
        placed.addOrderLine(OrderLine.builder()
                .product(testProduct).quantity(1).unitPrice(testProduct.getPrice()).build());
        placed.computeTotal();

        Order confirmed = Order.builder().status(OrderStatus.CONFIRMED).build();
        confirmed.addOrderLine(OrderLine.builder()
                .product(testProduct).quantity(1).unitPrice(testProduct.getPrice()).build());
        confirmed.computeTotal();

        orderRepository.saveAll(List.of(placed, confirmed));

        Specification<Order> spec = OrderSpecifications.byStatus(OrderStatus.PLACED);
        List<Order> results = orderRepository.findAll(spec);

        assertThat(results).isNotEmpty();
        assertThat(results).allMatch(o -> o.getStatus() == OrderStatus.PLACED);
    }

    @Test
    void shouldReturnNextPageUsingKeysetCursor() {
        Order o1 = orderRepository.save(createSimpleOrder());
        Order o2 = orderRepository.save(createSimpleOrder());
        Order o3 = orderRepository.save(createSimpleOrder());

        Specification<Order> afterO1 = OrderSpecifications.keysetAfter(o1.getId());
        List<Order> nextPage = orderRepository.findAll(afterO1);

        assertThat(nextPage).doesNotContain(o1);
        assertThat(nextPage).contains(o2, o3);
    }

    @Test
    void shouldLoadPaginatedOrdersWithoutNPlusOneQueries() {
        // Create 3 orders, each with a line item referencing a product
        for (int i = 0; i < 3; i++) {
            orderRepository.save(createSimpleOrder());
        }
        orderRepository.flush();
        QueryCountHolder.clear();

        // Paginated findAll(spec, pageable) — the path fixed by P0-A @EntityGraph
        Specification<Order> spec = OrderSpecifications.byStatus(null);
        Page<Order> page = orderRepository.findAll(spec,
                PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "id")));

        assertThat(page.getContent()).hasSizeGreaterThanOrEqualTo(3);
        // Access lazy associations to prove they were eagerly loaded
        page.getContent().forEach(order -> {
            assertThat(order.getOrderLines()).isNotEmpty();
            order.getOrderLines().forEach(line ->
                    assertThat(line.getProduct().getName()).isNotNull());
        });

        // Without @EntityGraph: 1 SELECT for orders + N SELECTs for orderLines + N for products
        // With @EntityGraph: 1 SELECT with LEFT JOIN (plus 1 count query for Page)
        long selectCount = QueryCountHolder.getGrandTotal().getSelect();
        assertThat(selectCount)
                .as("Paginated findAll should use @EntityGraph (expected 2: data + count), got %d", selectCount)
                .isLessThanOrEqualTo(2);
    }

    @Test
    void shouldBatchInsertOrderLines() {
        Order order = Order.builder().status(OrderStatus.PLACED).build();

        // Add 5 lines — should batch INSERT
        for (int i = 0; i < 5; i++) {
            Product p = productRepository.save(Product.builder()
                    .name("Batch Widget " + i)
                    .price(new BigDecimal("10.00"))
                    .stock(10)
                    .status(ProductStatus.ACTIVE)
                    .build());
            order.addOrderLine(OrderLine.builder()
                    .product(p)
                    .quantity(1)
                    .unitPrice(p.getPrice())
                    .build());
        }
        order.computeTotal();

        QueryCountHolder.clear();
        orderRepository.save(order);
        orderRepository.flush();

        // With hibernate.jdbc.batch_size=25 and order_inserts=true:
        // Hibernate groups inserts by entity type and executes them as JDBC batches.
        // datasource-proxy counts executeBatch() calls, not individual addBatch() calls.
        // Result: 1 batch for Order + 1 batch for 5 OrderLines = 2 insert operations.
        // Without batching this would be 6 individual inserts.
        long insertCount = QueryCountHolder.getGrandTotal().getInsert();
        assertThat(insertCount)
                .as("Expected 2 batched inserts (1 Order + 1 OrderLines batch), got %d", insertCount)
                .isEqualTo(2);
    }

    private Order createSimpleOrder() {
        Order order = Order.builder().status(OrderStatus.PLACED).build();
        order.addOrderLine(OrderLine.builder()
                .product(testProduct)
                .quantity(1)
                .unitPrice(testProduct.getPrice())
                .build());
        order.computeTotal();
        return order;
    }
}
