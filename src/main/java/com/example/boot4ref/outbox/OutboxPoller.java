package com.example.boot4ref.outbox;

import com.example.boot4ref.config.ApplicationProperties;
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
 * <p>On publish failure, the event's retryCount is incremented and the poller
 * continues to the next event. After {@code maxRetryCount} failures (default 5),
 * the event is marked FAILED and excluded from future polls by the
 * {@code WHERE status = 'PENDING'} filter. Scheduled cleanup deletes processed
 * entries older than the configured retention period.
 *
 * <p><strong>Ordering guarantee:</strong> Events are polled {@code ORDER BY created_at}.
 * TSID IDs are time-sorted, so created_at order ≈ insertion order. The Kafka partition key
 * ({@code aggregateId}) ensures per-aggregate ordering at the consumer. If Kafka fails
 * mid-batch, the remaining events stay PENDING and the next poll picks them up in the
 * same order.
 *
 * <p><strong>Sizing constraint:</strong> The poll loop holds a DB connection and row locks
 * for the entire batch. Worst-case hold time = {@code batch-size × send-timeout-seconds}.
 * With defaults (5 events × 5s timeout = 25s max), this stays well under HikariCP's
 * {@code connection-timeout} (5s) for a single connection. When tuning, keep
 * {@code batch-size × send-timeout-seconds} comfortably below {@code maximumPoolSize ×
 * connection-timeout} to avoid connection pool starvation during Kafka slowdowns.
 */
@Component
@ConditionalOnProperty(name = "app.outbox.poller.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaEventPublisher kafkaEventPublisher;
    private final int batchSize;
    private final int maxRetryCount;
    private final int retentionDays;

    public OutboxPoller(OutboxEventRepository outboxEventRepository,
                        KafkaEventPublisher kafkaEventPublisher,
                        ApplicationProperties properties) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaEventPublisher = kafkaEventPublisher;
        this.batchSize = properties.getOutbox().getBatchSize();
        this.maxRetryCount = properties.getOutbox().getMaxRetryCount();
        this.retentionDays = properties.getOutbox().getRetentionDays();
    }

    /**
     * Polls for pending outbox entries every 1 second.
     * SKIP LOCKED ensures parallel pollers claim different rows without blocking.
     */
    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms}")
    @Transactional
    public void poll() {
        List<OutboxEvent> pending = outboxEventRepository.findPendingWithLock(batchSize);
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
                event.setRetryCount(event.getRetryCount() + 1);
                if (event.getRetryCount() >= maxRetryCount) {
                    event.setStatus(OutboxStatus.FAILED);
                    log.error("Outbox event id={} permanently failed after {} retries: {}",
                            event.getId(), event.getRetryCount(), ex.getMessage());
                } else {
                    log.warn("Outbox event id={} publish failed (attempt {}/{}), will retry next cycle: {}",
                            event.getId(), event.getRetryCount(), maxRetryCount, ex.getMessage());
                }
            }
        }
    }

    /**
     * Cleanup: deletes PROCESSED and FAILED entries older than {@code app.outbox.retention-days} (default 7).
     * Runs every {@code app.outbox.cleanup-interval-ms} (default 1 hour).
     *
     * <p>{@code @Transactional} required: the {@code @Modifying} delete queries need
     * an active transaction. {@code @Scheduled} methods do not inherit a transaction.
     */
    @Scheduled(fixedDelayString = "${app.outbox.cleanup-interval-ms}")
    @Transactional
    public void cleanup() {
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        int deletedProcessed = outboxEventRepository.deleteByStatusProcessedBefore(OutboxStatus.PROCESSED, cutoff);
        int deletedFailed = outboxEventRepository.deleteByStatusCreatedBefore(OutboxStatus.FAILED, cutoff);
        int total = deletedProcessed + deletedFailed;
        if (total > 0) {
            log.info("Cleaned up {} outbox events older than {} days ({} processed, {} failed)",
                    total, retentionDays, deletedProcessed, deletedFailed);
        }
    }
}
