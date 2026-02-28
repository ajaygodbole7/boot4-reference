package com.example.boot4ref.outbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

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

    @InjectMocks
    private OutboxPoller outboxPoller;

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
    void shouldNotMarkEventAsProcessedWhenKafkaPublisherMarkedFailed() {
        OutboxEvent event = createPendingEvent();
        when(outboxEventRepository.findPendingWithLock(anyInt())).thenReturn(List.of(event));
        doAnswer(invocation -> {
            OutboxEvent e = invocation.getArgument(0);
            e.setStatus(OutboxStatus.FAILED);
            return null;
        }).when(kafkaEventPublisher).publish(event);

        outboxPoller.poll();

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(event.getProcessedAt()).isNull();
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
                .eventType("Order::placed")
                .payload("{}")
                .build();
    }
}
