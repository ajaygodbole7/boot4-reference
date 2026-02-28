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
 * {@code @Retryable} retries for transient blips.
 *
 * <p>If Kafka is unreachable after retries, the event stays PENDING and the batch
 * aborts. The next poll cycle (1s later) retries automatically — the outbox table
 * IS the infinite retry buffer. Events drain when Kafka recovers.
 * Scheduled cleanup deletes processed entries older than 7 days.
 *
 * <p><strong>Sizing constraint:</strong> The poll loop holds a DB connection and row locks
 * for the entire batch. Worst-case hold time = {@code BATCH_SIZE × kafka.send.timeout}.
 * With defaults (50 events × 10s timeout = 500s max), ensure HikariCP's
 * {@code maximumPoolSize} has headroom beyond the poller's connection. For higher
 * throughput or stricter latency targets, reduce {@code BATCH_SIZE} — the 1-second
 * poll interval will catch up across multiple cycles.
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
            try {
                kafkaEventPublisher.publish(event);
                event.setStatus(OutboxStatus.PROCESSED);
                event.setProcessedAt(Instant.now());
                log.debug("Published outbox event id={} type={}", event.getId(), event.getEventType());
            } catch (Exception ex) {
                // Leave event PENDING — next poll cycle retries automatically.
                // Break the batch: if Kafka is down, remaining events would also fail.
                log.warn("Outbox event id={} publish failed, will retry next cycle: {}",
                        event.getId(), ex.getMessage());
                break;
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
