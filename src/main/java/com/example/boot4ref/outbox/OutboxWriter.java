package com.example.boot4ref.outbox;

import com.example.boot4ref.order.event.DomainEvent;
import com.example.boot4ref.order.event.EventTypes;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Writes domain events as outbox entries in the same transaction as the domain change.
 * Payload is formatted as CloudEvents 1.0 using the native CloudEvents SDK.
 */
@Component
public class OutboxWriter {

    private static final Logger log = LoggerFactory.getLogger(OutboxWriter.class);

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public OutboxWriter(OutboxEventRepository outboxEventRepository, ObjectMapper objectMapper) {
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Inserts a CloudEvents-formatted outbox entry for the given domain event.
     * Must be called within an existing @Transactional context.
     */
    public void stageEvent(DomainEvent event) {
        String eventType = resolveEventType(event);
        String payload = buildCloudEventsPayload(event, eventType);

        OutboxEvent outboxEvent = OutboxEvent.builder()
                .aggregateType("Order")
                .aggregateId(event.orderId())
                .eventType(eventType)
                .payload(payload)
                .build();

        outboxEventRepository.saveAndFlush(outboxEvent);
        log.debug("Outbox entry created: type={}, orderId={}", eventType, event.orderId());
    }

    /**
     * Exhaustive switch on sealed DomainEvent hierarchy.
     * Adding a new event type without a case here is a compile error.
     */
    private String resolveEventType(DomainEvent event) {
        return switch (event) {
            case DomainEvent.OrderPlaced _ -> EventTypes.ORDER_PLACED;
            case DomainEvent.OrderConfirmed _ -> EventTypes.ORDER_CONFIRMED;
            case DomainEvent.OrderShipped _ -> EventTypes.ORDER_SHIPPED;
            case DomainEvent.OrderDelivered _ -> EventTypes.ORDER_DELIVERED;
            case DomainEvent.OrderCancelled _ -> EventTypes.ORDER_CANCELLED;
        };
    }

    private String buildCloudEventsPayload(DomainEvent event, String eventType) {
        try {
            // Serialize event data with Jackson 3.x, then pass raw bytes to CloudEvents SDK
            byte[] dataBytes = objectMapper.writeValueAsBytes(event);

            CloudEvent cloudEvent = CloudEventBuilder.v1()
                    .withId(java.util.UUID.randomUUID().toString())
                    .withSource(URI.create("/orders/" + event.orderId()))
                    .withType(eventType)
                    .withTime(OffsetDateTime.ofInstant(event.timestamp(), ZoneOffset.UTC))
                    .withDataContentType("application/json")
                    .withData("application/json", dataBytes)
                    .build();

            var format = io.cloudevents.core.provider.EventFormatProvider.getInstance()
                    .resolveFormat("application/cloudevents+json");
            if (format == null) {
                throw new IllegalStateException(
                        "CloudEvents JSON format not registered — missing cloudevents-json-jackson dependency?");
            }
            return new String(format.serialize(cloudEvent));
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize CloudEvent payload", e);
        }
    }
}
