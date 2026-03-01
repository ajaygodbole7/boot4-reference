package com.example.boot4ref.outbox;

import com.example.boot4ref.order.event.DomainEvent;
import com.example.boot4ref.order.event.EventTypes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OutboxPublisher}.
 * Verifies event type resolution, CloudEvents payload format, and correct outbox entry creation.
 */
@ExtendWith(MockitoExtension.class)
class OutboxPublisherTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Captor
    private ArgumentCaptor<OutboxEvent> eventCaptor;

    private OutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = JsonMapper.builder().build();
        publisher = new OutboxPublisher(outboxEventRepository, objectMapper);
    }

    // --- Event type resolution (sealed exhaustive switch) ---

    @Test
    void shouldResolveOrderPlacedEventType() {
        var event = new DomainEvent.OrderPlaced(1L, Instant.now(), BigDecimal.TEN, 2);
        when(outboxEventRepository.save(eventCaptor.capture())).thenAnswer(i -> i.getArgument(0));

        publisher.publish(event);

        assertThat(eventCaptor.getValue().getEventType()).isEqualTo(EventTypes.ORDER_PLACED);
    }

    @Test
    void shouldResolveOrderConfirmedEventType() {
        var event = new DomainEvent.OrderConfirmed(1L, Instant.now());
        when(outboxEventRepository.save(eventCaptor.capture())).thenAnswer(i -> i.getArgument(0));

        publisher.publish(event);

        assertThat(eventCaptor.getValue().getEventType()).isEqualTo(EventTypes.ORDER_CONFIRMED);
    }

    @Test
    void shouldResolveOrderShippedEventType() {
        var event = new DomainEvent.OrderShipped(1L, Instant.now());
        when(outboxEventRepository.save(eventCaptor.capture())).thenAnswer(i -> i.getArgument(0));

        publisher.publish(event);

        assertThat(eventCaptor.getValue().getEventType()).isEqualTo(EventTypes.ORDER_SHIPPED);
    }

    @Test
    void shouldResolveOrderDeliveredEventType() {
        var event = new DomainEvent.OrderDelivered(1L, Instant.now());
        when(outboxEventRepository.save(eventCaptor.capture())).thenAnswer(i -> i.getArgument(0));

        publisher.publish(event);

        assertThat(eventCaptor.getValue().getEventType()).isEqualTo(EventTypes.ORDER_DELIVERED);
    }

    @Test
    void shouldResolveOrderCancelledEventType() {
        var event = new DomainEvent.OrderCancelled(1L, Instant.now());
        when(outboxEventRepository.save(eventCaptor.capture())).thenAnswer(i -> i.getArgument(0));

        publisher.publish(event);

        assertThat(eventCaptor.getValue().getEventType()).isEqualTo(EventTypes.ORDER_CANCELLED);
    }

    // --- Outbox entry fields ---

    @Test
    void shouldSetAggregateTypeToOrder() {
        var event = new DomainEvent.OrderPlaced(42L, Instant.now(), BigDecimal.ONE, 1);
        when(outboxEventRepository.save(eventCaptor.capture())).thenAnswer(i -> i.getArgument(0));

        publisher.publish(event);

        assertThat(eventCaptor.getValue().getAggregateType()).isEqualTo("Order");
    }

    @Test
    void shouldSetAggregateIdFromEvent() {
        var event = new DomainEvent.OrderPlaced(42L, Instant.now(), BigDecimal.ONE, 1);
        when(outboxEventRepository.save(eventCaptor.capture())).thenAnswer(i -> i.getArgument(0));

        publisher.publish(event);

        assertThat(eventCaptor.getValue().getAggregateId()).isEqualTo(42L);
    }

    @Test
    void shouldSetPendingStatus() {
        var event = new DomainEvent.OrderPlaced(1L, Instant.now(), BigDecimal.ONE, 1);
        when(outboxEventRepository.save(eventCaptor.capture())).thenAnswer(i -> i.getArgument(0));

        publisher.publish(event);

        assertThat(eventCaptor.getValue().getStatus()).isEqualTo(OutboxStatus.PENDING);
    }

    // --- CloudEvents payload format ---

    @Test
    void shouldProduceCloudEventsPayloadWithSpecVersion() {
        var event = new DomainEvent.OrderPlaced(1L, Instant.now(), BigDecimal.TEN, 3);
        when(outboxEventRepository.save(eventCaptor.capture())).thenAnswer(i -> i.getArgument(0));

        publisher.publish(event);

        String payload = eventCaptor.getValue().getPayload();
        assertThat(payload).contains("\"specversion\"");
        assertThat(payload).contains("\"1.0\"");
    }

    @Test
    void shouldProduceCloudEventsPayloadWithCorrectType() {
        var event = new DomainEvent.OrderConfirmed(99L, Instant.now());
        when(outboxEventRepository.save(eventCaptor.capture())).thenAnswer(i -> i.getArgument(0));

        publisher.publish(event);

        String payload = eventCaptor.getValue().getPayload();
        assertThat(payload).contains("\"Order::confirmed\"");
    }

    @Test
    void shouldProduceCloudEventsPayloadWithSource() {
        var event = new DomainEvent.OrderPlaced(55L, Instant.now(), BigDecimal.ONE, 1);
        when(outboxEventRepository.save(eventCaptor.capture())).thenAnswer(i -> i.getArgument(0));

        publisher.publish(event);

        String payload = eventCaptor.getValue().getPayload();
        assertThat(payload).contains("\"/orders/55\"");
    }

    @Test
    void shouldProduceCloudEventsPayloadWithDataContentType() {
        var event = new DomainEvent.OrderPlaced(1L, Instant.now(), BigDecimal.ONE, 1);
        when(outboxEventRepository.save(eventCaptor.capture())).thenAnswer(i -> i.getArgument(0));

        publisher.publish(event);

        String payload = eventCaptor.getValue().getPayload();
        assertThat(payload).contains("\"datacontenttype\"");
        assertThat(payload).contains("\"application/json\"");
    }

    @Test
    void shouldProduceCloudEventsPayloadWithData() {
        var event = new DomainEvent.OrderPlaced(1L, Instant.now(), new BigDecimal("50.00"), 3);
        when(outboxEventRepository.save(eventCaptor.capture())).thenAnswer(i -> i.getArgument(0));

        publisher.publish(event);

        String payload = eventCaptor.getValue().getPayload();
        assertThat(payload).contains("\"data\"");
    }

    @Test
    void shouldSaveOutboxEventToRepository() {
        var event = new DomainEvent.OrderPlaced(1L, Instant.now(), BigDecimal.ONE, 1);
        when(outboxEventRepository.save(eventCaptor.capture())).thenAnswer(i -> i.getArgument(0));

        publisher.publish(event);

        verify(outboxEventRepository).save(eventCaptor.getValue());
    }
}
