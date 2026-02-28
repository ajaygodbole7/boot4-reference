package com.example.boot4ref.outbox;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * SKIP LOCKED poller that picks up pending outbox entries and publishes to Kafka.
 * Publishing is delegated to {@link KafkaEventPublisher} which handles
 * {@code @Retryable} retries and {@code @Recover} failure marking.
 * Scheduled cleanup deletes processed entries older than 7 days.
 */
@Component
@ConditionalOnProperty(name = "outbox.poller.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);
    private static final int BATCH_SIZE = 50;
    private static final int CLEANUP_DAYS = 7;

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaEventPublisher kafkaEventPublisher;

    public OutboxPoller(OutboxEventRepository outboxEventRepository,
                        KafkaEventPublisher kafkaEventPublisher) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaEventPublisher = kafkaEventPublisher;
    }

    /**
     * Polls for pending outbox entries every 1 second.
     * SKIP LOCKED ensures parallel pollers claim different rows without blocking.
     */
    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void poll() {
        List<OutboxEvent> pending = outboxEventRepository.findPendingWithLock(BATCH_SIZE);
        if (pending.isEmpty()) {
            return;
        }

        for (OutboxEvent event : pending) {
            // KafkaEventPublisher.publish() is @Retryable (3 attempts).
            // If all retries fail, @Recover marks the event as FAILED.
            kafkaEventPublisher.publish(event);

            if (event.getStatus() != OutboxStatus.FAILED) {
                event.setStatus(OutboxStatus.PROCESSED);
                event.setProcessedAt(Instant.now());
                log.debug("Published outbox event id={} type={}", event.getId(), event.getEventType());
            }
        }
    }

    /**
     * Cleanup: deletes PROCESSED entries older than 7 days.
     * Runs once per hour.
     *
     * <p>{@code @Transactional} required: {@code @Modifying} deleteProcessedBefore needs
     * an active transaction. {@code @Scheduled} methods do not inherit a transaction.
     */
    @Scheduled(fixedDelay = 3_600_000)
    @Transactional
    public void cleanup() {
        Instant cutoff = Instant.now().minus(CLEANUP_DAYS, ChronoUnit.DAYS);
        int deleted = outboxEventRepository.deleteProcessedBefore(cutoff);
        if (deleted > 0) {
            log.info("Cleaned up {} processed outbox events older than {} days", deleted, CLEANUP_DAYS);
        }
    }
}
