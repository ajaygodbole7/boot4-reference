package com.example.boot4ref.outbox;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;

/**
 * Publishes outbox events to Kafka with retry support.
 * Extracted as a separate component so {@code @Retryable} fires through the AOP proxy.
 * {@code @Recover} marks the event as FAILED after all retry attempts are exhausted.
 * Only active when the outbox poller is enabled (disabled in tests).
 */
@Component
@ConditionalOnProperty(name = "outbox.poller.enabled", havingValue = "true", matchIfMissing = true)
public class KafkaEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;

    public KafkaEventPublisher(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Publishes a single outbox event to Kafka.
     * Topic is derived from aggregate type (e.g., "order-events").
     * {@code @Retryable} retries transient Kafka publish failures.
     */
    @Retryable(retryFor = Exception.class, maxAttempts = 3)
    public void publish(OutboxEvent event) {
        String topic = event.getAggregateType().toLowerCase() + "-events";
        try {
            kafkaTemplate.send(topic, event.getAggregateId().toString(), event.getPayload())
                    .get(10, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw new RuntimeException("Kafka send failed for event id=" + event.getId(), e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Kafka send interrupted for event id=" + event.getId(), e);
        } catch (TimeoutException e) {
            throw new RuntimeException("Kafka send timed out for event id=" + event.getId(), e);
        }
    }

    /**
     * Called after all retry attempts are exhausted.
     * Marks the event as FAILED so it is not retried by the poller.
     */
    @Recover
    public void recover(Exception e, OutboxEvent event) {
        event.setStatus(OutboxStatus.FAILED);
        log.error("Outbox event id={} marked FAILED after retries: {}",
                event.getId(), e.getMessage());
    }
}
