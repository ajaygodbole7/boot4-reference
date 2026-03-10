package com.example.boot4ref.outbox;

import com.example.boot4ref.config.ApplicationProperties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.common.errors.AuthenticationException;
import org.apache.kafka.common.errors.AuthorizationException;
import org.apache.kafka.common.errors.SerializationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;

/**
 * Publishes outbox events to Kafka with retry support.
 * Extracted as a separate component so {@code @Retryable} fires through the AOP proxy.
 *
 * <p>On publish failure after retries, the exception propagates to the poller which
 * leaves the event PENDING for the next poll cycle. The outbox table IS the retry
 * mechanism — events stay PENDING until Kafka recovers and drain automatically.
 *
 * <p>Only active when the outbox poller is enabled (disabled in tests).
 */
@Component
@ConditionalOnProperty(name = "app.outbox.poller.enabled", havingValue = "true", matchIfMissing = true)
public class KafkaEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventPublisher.class);
    private static final int KAFKA_RETRY_MAX_ATTEMPTS = 3;
    private static final int KAFKA_RETRY_DELAY_MS = 200;
    private static final String TOPIC_SUFFIX = "-events";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final long sendTimeoutSeconds;

    public KafkaEventPublisher(KafkaTemplate<String, String> kafkaTemplate,
                               ApplicationProperties properties) {
        this.kafkaTemplate = kafkaTemplate;
        this.sendTimeoutSeconds = properties.getKafka().getSendTimeoutSeconds();
    }

    /**
     * Publishes a single outbox event to Kafka.
     * Topic is derived from aggregate type (e.g., "order-events").
     * {@code @Retryable} retries transient Kafka publish failures.
     */
    @Retryable(retryFor = RuntimeException.class,
            noRetryFor = {SerializationException.class,
                          AuthorizationException.class,
                          AuthenticationException.class},
            maxAttempts = KAFKA_RETRY_MAX_ATTEMPTS,
            backoff = @Backoff(delay = KAFKA_RETRY_DELAY_MS, multiplier = 2, random = true))
    public void publish(OutboxEvent event) {
        String topic = event.getAggregateType().toLowerCase() + TOPIC_SUFFIX;
        try {
            kafkaTemplate.send(topic, event.getAggregateId().toString(), event.getPayload())
                    .get(sendTimeoutSeconds, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException("Kafka send failed for event id=" + event.getId(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Kafka send interrupted for event id=" + event.getId(), e);
        } catch (TimeoutException e) {
            throw new RuntimeException("Kafka send timed out for event id=" + event.getId(), e);
        }
    }

}
