package com.example.boot4ref.outbox;

import com.example.boot4ref.config.ApplicationProperties;
import com.example.boot4ref.order.event.EventTypes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OutboxPoller}.
 * Verifies polling logic, status transitions, and cleanup delegation.
 */
@ExtendWith(MockitoExtension.class)
class OutboxPollerTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private KafkaEventPublisher kafkaEventPublisher;

    private OutboxPoller outboxPoller;

    @BeforeEach
    void setUp() {
        ApplicationProperties properties = new ApplicationProperties();
        outboxPoller = new OutboxPoller(outboxEventRepository, kafkaEventPublisher, properties);
    }

    @Test
    void shouldDoNothingWhenNoPendingEvents() {
        when(outboxEventRepository.findPendingWithLock(anyInt())).thenReturn(Collections.emptyList());

        outboxPoller.poll();

        verifyNoInteractions(kafkaEventPublisher);
    }

    @Test
    void shouldPublishPendingEventsViaKafkaPublisher() {
        OutboxEvent event = createPendingEvent();
        when(outboxEventRepository.findPendingWithLock(anyInt())).thenReturn(List.of(event));

        outboxPoller.poll();

        verify(kafkaEventPublisher).publish(event);
    }

    @Test
    void shouldMarkEventAsProcessedAfterSuccessfulPublish() {
        OutboxEvent event = createPendingEvent();
        when(outboxEventRepository.findPendingWithLock(anyInt())).thenReturn(List.of(event));

        outboxPoller.poll();

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PROCESSED);
        assertThat(event.getProcessedAt()).isNotNull();
    }

    @Test
    void shouldIncrementRetryCountAndContinueBatchOnPublishFailure() {
        OutboxEvent event1 = createPendingEvent();
        OutboxEvent event2 = createPendingEvent();
        when(outboxEventRepository.findPendingWithLock(anyInt())).thenReturn(List.of(event1, event2));
        doThrow(new RuntimeException("Kafka down")).when(kafkaEventPublisher).publish(event1);

        outboxPoller.poll();

        // First event stays PENDING with incremented retryCount
        assertThat(event1.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event1.getRetryCount()).isEqualTo(1);
        assertThat(event1.getProcessedAt()).isNull();
        // Second event still attempted — no batch abort
        assertThat(event2.getStatus()).isEqualTo(OutboxStatus.PROCESSED);
        verify(kafkaEventPublisher, times(2)).publish(any());
    }

    @Test
    void shouldMarkEventAsFailedAfterMaxRetries() {
        OutboxEvent event = createPendingEvent();
        // Simulate 4 prior retries (default max = 5)
        event.setRetryCount(4);
        when(outboxEventRepository.findPendingWithLock(anyInt())).thenReturn(List.of(event));
        doThrow(new RuntimeException("Kafka down")).when(kafkaEventPublisher).publish(event);

        outboxPoller.poll();

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(event.getRetryCount()).isEqualTo(5);
    }

    @Test
    void shouldContinueProcessingAfterSingleEventFailure() {
        OutboxEvent event1 = createPendingEvent();
        OutboxEvent event2 = createPendingEvent();
        OutboxEvent event3 = createPendingEvent();
        when(outboxEventRepository.findPendingWithLock(anyInt())).thenReturn(List.of(event1, event2, event3));
        // Use doAnswer with reference equality — OutboxEvent.equals returns false for null-ID entities
        doAnswer(invocation -> {
            OutboxEvent arg = invocation.getArgument(0);
            if (arg == event2) {
                throw new RuntimeException("Kafka blip");
            }
            return null;
        }).when(kafkaEventPublisher).publish(any());

        outboxPoller.poll();

        assertThat(event1.getStatus()).isEqualTo(OutboxStatus.PROCESSED);
        assertThat(event2.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event2.getRetryCount()).isEqualTo(1);
        assertThat(event3.getStatus()).isEqualTo(OutboxStatus.PROCESSED);
        verify(kafkaEventPublisher, times(3)).publish(any());
    }

    @Test
    void shouldProcessMultipleEventsInSinglePoll() {
        OutboxEvent event1 = createPendingEvent();
        OutboxEvent event2 = createPendingEvent();
        when(outboxEventRepository.findPendingWithLock(anyInt())).thenReturn(List.of(event1, event2));

        outboxPoller.poll();

        verify(kafkaEventPublisher, times(2)).publish(any());
        assertThat(event1.getStatus()).isEqualTo(OutboxStatus.PROCESSED);
        assertThat(event2.getStatus()).isEqualTo(OutboxStatus.PROCESSED);
    }

    @Test
    void shouldDelegateCleanupToRepository() {
        when(outboxEventRepository.deleteProcessedBefore(any(Instant.class))).thenReturn(5);

        outboxPoller.cleanup();

        ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(outboxEventRepository).deleteProcessedBefore(cutoffCaptor.capture());
        // Cutoff should be roughly 7 days ago
        assertThat(cutoffCaptor.getValue()).isBefore(Instant.now().minusSeconds(6 * 24 * 3600));
    }

    private OutboxEvent createPendingEvent() {
        return OutboxEvent.builder()
                .aggregateType("Order")
                .aggregateId(1L)
                .eventType(EventTypes.ORDER_PLACED)
                .payload("{}")
                .build();
    }
}
