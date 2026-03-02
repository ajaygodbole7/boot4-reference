package com.example.boot4ref;

import com.example.boot4ref.common.fixture.OrderFixtures;
import com.example.boot4ref.common.fixture.ProductFixtures;
import com.example.boot4ref.order.OrderStatus;
import com.example.boot4ref.order.event.EventTypes;
import com.example.boot4ref.order.rest.OrderCreateRequest;
import com.example.boot4ref.order.rest.OrderLineRequest;
import com.example.boot4ref.order.rest.OrderResponse;
import com.example.boot4ref.order.service.OrderService;
import com.example.boot4ref.outbox.OutboxEvent;
import com.example.boot4ref.outbox.OutboxEventRepository;
import com.example.boot4ref.outbox.OutboxStatus;
import com.example.boot4ref.product.Product;
import com.example.boot4ref.product.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for the transactional outbox pattern.
 * Outbox entry inserted in same transaction as domain change.
 * Cleanup of processed entries older than threshold.
 * CloudEvents payload format.
 */
class OutboxIT extends AbstractIntegrationTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    private Product testProduct;

    @BeforeEach
    void setUp() {
        outboxEventRepository.deleteAll();
        testProduct = productRepository.save(ProductFixtures.randomActiveProduct());
    }

    @Test
    void shouldInsertOutboxEntryWhenOrderCreated() {
        OrderCreateRequest request = OrderFixtures.randomOrderCreateRequest(testProduct.getId());

        OrderResponse created = orderService.create(request, "outbox-create-key");

        List<OutboxEvent> events = outboxEventRepository.findAll();
        assertThat(events).hasSize(1);

        OutboxEvent event = events.getFirst();
        assertThat(event.getAggregateType()).isEqualTo("Order");
        assertThat(event.getAggregateId()).isEqualTo(created.id());
        assertThat(event.getEventType()).isEqualTo(EventTypes.ORDER_PLACED);
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getRetryCount()).isZero();
    }

    @Test
    void shouldInsertOutboxEntryWhenOrderTransitioned() {
        OrderCreateRequest request = new OrderCreateRequest(
                List.of(new OrderLineRequest(testProduct.getId(), 1)));
        OrderResponse created = orderService.create(request, "outbox-transition-key");

        orderService.transition(created.id(), OrderStatus.CONFIRMED);

        List<OutboxEvent> events = outboxEventRepository.findAll();
        assertThat(events).hasSize(2);
        assertThat(events.stream().map(OutboxEvent::getEventType).toList())
                .containsExactlyInAnyOrder(EventTypes.ORDER_PLACED, EventTypes.ORDER_CONFIRMED);
    }

    @Test
    void shouldContainCloudEventsPayloadFormat() {
        OrderCreateRequest request = new OrderCreateRequest(
                List.of(new OrderLineRequest(testProduct.getId(), 3)));
        orderService.create(request, "outbox-cloudevents-key");

        OutboxEvent event = outboxEventRepository.findAll().getFirst();
        String payload = event.getPayload();
        assertThat(payload).contains("\"specversion\"");
        assertThat(payload).contains("\"1.0\"");
        assertThat(payload).contains("\"type\"");
        assertThat(payload).contains("\"Order::placed\"");
        assertThat(payload).contains("\"source\"");
        assertThat(payload).contains("\"/orders/");
        assertThat(payload).contains("\"data\"");
        assertThat(payload).contains("\"datacontenttype\"");
    }

    @Test
    void shouldFindPendingEventsWithSkipLocked() {
        OrderCreateRequest request = new OrderCreateRequest(
                List.of(new OrderLineRequest(testProduct.getId(), 1)));
        orderService.create(request, "skip-locked-key");

        List<OutboxEvent> pending = outboxEventRepository.findPendingWithLock(10);
        assertThat(pending).hasSize(1);
        assertThat(pending.getFirst().getStatus()).isEqualTo(OutboxStatus.PENDING);
    }

    @Test
    void shouldDeleteProcessedEntriesOlderThanThreshold() {
        // Create order to get outbox entry
        OrderCreateRequest request = new OrderCreateRequest(
                List.of(new OrderLineRequest(testProduct.getId(), 1)));
        orderService.create(request, "cleanup-test-key");

        // Manually mark as processed with old timestamp
        OutboxEvent event = outboxEventRepository.findAll().getFirst();
        event.setStatus(OutboxStatus.PROCESSED);
        event.setProcessedAt(Instant.now().minus(8, ChronoUnit.DAYS));
        outboxEventRepository.saveAndFlush(event);

        // Cleanup should delete entries older than 7 days
        Instant cutoff = Instant.now().minus(7, ChronoUnit.DAYS);
        int deleted = outboxEventRepository.deleteByStatusBefore(OutboxStatus.PROCESSED, cutoff);
        assertThat(deleted).isEqualTo(1);

        assertThat(outboxEventRepository.findAll()).isEmpty();
    }

    @Test
    void shouldProduceEventsForFullOrderLifecycle() {
        OrderCreateRequest request = new OrderCreateRequest(
                List.of(new OrderLineRequest(testProduct.getId(), 1)));
        OrderResponse created = orderService.create(request, "lifecycle-key");

        orderService.transition(created.id(), OrderStatus.CONFIRMED);
        orderService.transition(created.id(), OrderStatus.SHIPPED);
        orderService.transition(created.id(), OrderStatus.DELIVERED);

        List<String> eventTypes = outboxEventRepository.findAll().stream()
                .map(OutboxEvent::getEventType).toList();
        assertThat(eventTypes).containsExactly(
                EventTypes.ORDER_PLACED, EventTypes.ORDER_CONFIRMED,
                EventTypes.ORDER_SHIPPED, EventTypes.ORDER_DELIVERED);
    }

    @Test
    void shouldProduceCancelledEventOnCancellation() {
        OrderCreateRequest request = new OrderCreateRequest(
                List.of(new OrderLineRequest(testProduct.getId(), 1)));
        OrderResponse created = orderService.create(request, "cancel-event-key");

        orderService.transition(created.id(), OrderStatus.CANCELLED);

        List<String> eventTypes = outboxEventRepository.findAll().stream()
                .map(OutboxEvent::getEventType).toList();
        assertThat(eventTypes).containsExactly(EventTypes.ORDER_PLACED, EventTypes.ORDER_CANCELLED);
    }

    @Test
    void shouldNotInsertOutboxEntryWhenOrderCreationFails() {
        // Insufficient stock — order creation should fail, no outbox entry
        OrderCreateRequest request = new OrderCreateRequest(
                List.of(new OrderLineRequest(testProduct.getId(), 999)));

        assertThatThrownBy(() -> orderService.create(request, "atomicity-key"))
                .isInstanceOf(Exception.class);

        assertThat(outboxEventRepository.findAll()).isEmpty();
    }

    @Test
    void shouldContainOrderDataInCloudEventsPayload() {
        OrderCreateRequest request = new OrderCreateRequest(
                List.of(new OrderLineRequest(testProduct.getId(), 3)));
        orderService.create(request, "data-content-key");

        OutboxEvent event = outboxEventRepository.findAll().getFirst();
        String payload = event.getPayload();
        // CloudEvents data field should contain event-specific fields
        assertThat(payload).contains("\"totalAmount\"");
        assertThat(payload).contains("\"lineCount\"");
        assertThat(payload).contains("\"orderId\"");
        assertThat(payload).contains("\"timestamp\"");
    }

    @Test
    void shouldNotDeleteRecentProcessedEntries() {
        OrderCreateRequest request = new OrderCreateRequest(
                List.of(new OrderLineRequest(testProduct.getId(), 1)));
        orderService.create(request, "recent-cleanup-key");

        // Mark as processed recently (1 day ago)
        OutboxEvent event = outboxEventRepository.findAll().getFirst();
        event.setStatus(OutboxStatus.PROCESSED);
        event.setProcessedAt(Instant.now().minus(1, ChronoUnit.DAYS));
        outboxEventRepository.saveAndFlush(event);

        // Cleanup with 7-day cutoff should NOT delete recent entries
        Instant cutoff = Instant.now().minus(7, ChronoUnit.DAYS);
        int deleted = outboxEventRepository.deleteByStatusBefore(OutboxStatus.PROCESSED, cutoff);
        assertThat(deleted).isZero();

        assertThat(outboxEventRepository.findAll()).hasSize(1);
    }
}
