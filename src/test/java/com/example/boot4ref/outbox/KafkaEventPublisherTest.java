package com.example.boot4ref.outbox;

import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link KafkaEventPublisher}.
 * Verifies topic derivation, message key, and failure recovery.
 */
@ExtendWith(MockitoExtension.class)
class KafkaEventPublisherTest {

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @InjectMocks
    private KafkaEventPublisher kafkaEventPublisher;

    @Test
    void shouldPublishToTopicDerivedFromAggregateType() {
        stubKafkaSend();
        OutboxEvent event = createEvent("Order", 42L, "Order::placed", "{}");

        kafkaEventPublisher.publish(event);

        verify(kafkaTemplate).send("order-events", "42", "{}");
    }

    @Test
    void shouldUseLowercaseAggregateTypeForTopic() {
        stubKafkaSend();
        OutboxEvent event = createEvent("Product", 10L, "Product::created", "{\"test\":true}");

        kafkaEventPublisher.publish(event);

        verify(kafkaTemplate).send("product-events", "10", "{\"test\":true}");
    }

    @Test
    void shouldUseAggregateIdAsMessageKey() {
        stubKafkaSend();
        OutboxEvent event = createEvent("Order", 99L, "Order::confirmed", "{}");

        kafkaEventPublisher.publish(event);

        verify(kafkaTemplate).send("order-events", "99", "{}");
    }

    @SuppressWarnings("unchecked")
    private void stubKafkaSend() {
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    @Test
    void shouldMarkEventAsFailedOnRecover() {
        OutboxEvent event = createEvent("Order", 1L, "Order::placed", "{}");

        kafkaEventPublisher.recover(new RuntimeException("Kafka down"), event);

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.FAILED);
    }

    private OutboxEvent createEvent(String aggregateType, Long aggregateId,
                                     String eventType, String payload) {
        return OutboxEvent.builder()
                .aggregateType(aggregateType)
                .aggregateId(aggregateId)
                .eventType(eventType)
                .payload(payload)
                .build();
    }
}
