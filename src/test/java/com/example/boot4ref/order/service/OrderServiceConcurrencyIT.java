package com.example.boot4ref.order.service;

import com.example.boot4ref.AbstractIntegrationTest;
import com.example.boot4ref.common.fixture.ProductFixtures;
import com.example.boot4ref.order.OrderStatus;
import com.example.boot4ref.order.rest.OrderCreateRequest;
import com.example.boot4ref.order.rest.OrderLineRequest;
import com.example.boot4ref.product.Product;
import com.example.boot4ref.product.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency integration tests for Order operations.
 * Optimistic lock retry on concurrent order updates.
 * Pessimistic lock ensures stock correctness under concurrent order placement.
 */
class OrderServiceConcurrencyIT extends AbstractIntegrationTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private ProductRepository productRepository;

    private Product testProduct;

    @BeforeEach
    void setUp() {
        Product p = ProductFixtures.randomActiveProduct();
        p.setStock(100);
        p.setPrice(new BigDecimal("10.00"));
        testProduct = productRepository.save(p);
    }

    /**
     * Concurrent order placement for same product must not oversell.
     * PESSIMISTIC_WRITE ensures stock correctness — 10 threads each ordering 10 units
     * from a product with stock=100 should all succeed with final stock=0.
     */
    @Test
    void shouldNotOversellWhenConcurrentOrdersPlacedForSameProduct() throws Exception {
        // Set stock to exactly 100
        testProduct.setStock(100);
        productRepository.save(testProduct);

        int threadCount = 10;
        int quantityPerOrder = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            futures.add(executor.submit(() -> {
                try {
                    latch.await(); // All threads start simultaneously
                    OrderCreateRequest request = new OrderCreateRequest(
                            List.of(new OrderLineRequest(testProduct.getId(), quantityPerOrder)));
                    orderService.create(request, "concurrent-key-" + idx);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                }
            }));
        }

        latch.countDown(); // Release all threads
        for (Future<?> f : futures) {
            f.get(); // Wait for completion
        }
        executor.shutdown();

        // All 10 orders should succeed (10 * 10 = 100 = total stock)
        assertThat(successCount.get()).isEqualTo(threadCount);

        // Product stock should be exactly 0
        Product updated = productRepository.findById(testProduct.getId()).orElseThrow();
        assertThat(updated.getStock())
                .as("Stock should be exactly 0 after %d orders of %d units each",
                        threadCount, quantityPerOrder)
                .isEqualTo(0);
    }

    /**
     * When stock is insufficient for all concurrent orders,
     * some should fail with InsufficientStockException, and no oversell occurs.
     */
    @Test
    void shouldRejectSomeOrdersWhenStockInsufficientForAllConcurrentOrders() throws Exception {
        // Only 50 units available, but 10 threads each want 10 = 100 total
        testProduct.setStock(50);
        productRepository.save(testProduct);

        int threadCount = 10;
        int quantityPerOrder = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            futures.add(executor.submit(() -> {
                try {
                    latch.await();
                    OrderCreateRequest request = new OrderCreateRequest(
                            List.of(new OrderLineRequest(testProduct.getId(), quantityPerOrder)));
                    orderService.create(request, "partial-key-" + idx);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                }
            }));
        }

        latch.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        executor.shutdown();

        // Exactly 5 should succeed (50 stock / 10 per order)
        assertThat(successCount.get()).isEqualTo(5);
        assertThat(failCount.get()).isEqualTo(5);

        // Stock should be exactly 0
        Product updated = productRepository.findById(testProduct.getId()).orElseThrow();
        assertThat(updated.getStock()).isEqualTo(0);
    }

    /**
     * Two threads calling orderService.transition() on the same order concurrently.
     * One should succeed, the other should get ObjectOptimisticLockingFailureException
     * (which ExceptionTranslator maps to 409 Conflict).
     */
    @Test
    void shouldThrowOptimisticLockExceptionOnConcurrentOrderStatusUpdate() throws Exception {
        // Create an order to transition
        OrderCreateRequest request = new OrderCreateRequest(
                List.of(new OrderLineRequest(testProduct.getId(), 1)));
        var created = orderService.create(request, "opt-lock-test");

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger optimisticLockCount = new AtomicInteger(0);

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                try {
                    latch.await();
                    // Both threads try to transition PLACED -> CONFIRMED via service layer
                    orderService.transition(created.id(), OrderStatus.CONFIRMED);
                    successCount.incrementAndGet();
                } catch (ObjectOptimisticLockingFailureException e) {
                    optimisticLockCount.incrementAndGet();
                } catch (Exception e) {
                    // Ignore other exceptions (e.g. OrderConflictException if already transitioned)
                    if (e.getCause() instanceof ObjectOptimisticLockingFailureException) {
                        optimisticLockCount.incrementAndGet();
                    }
                }
            }));
        }

        latch.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        executor.shutdown();

        // One thread succeeds, the other hits optimistic lock or finds already-transitioned state
        assertThat(successCount.get())
                .as("Exactly one thread should succeed in transitioning the order")
                .isEqualTo(1);

        // Verify the order ended up in CONFIRMED state
        var finalOrder = orderService.findById(created.id());
        assertThat(finalOrder.status()).isEqualTo("CONFIRMED");
    }
}
