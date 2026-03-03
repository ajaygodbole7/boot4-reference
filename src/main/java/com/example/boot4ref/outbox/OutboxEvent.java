package com.example.boot4ref.outbox;

import com.example.boot4ref.common.TsidFactory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * JPA entity mapping to the outbox_events table (V3 migration).
 * JSONB payload stores CloudEvents-formatted event data.
 */
@Entity
@Table(name = "outbox_events")
@Getter
@Setter
public class OutboxEvent {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "aggregate_type", nullable = false, length = 100)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private Long aggregateId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "JSONB")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OutboxStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Version
    @Column(name = "version", nullable = false)
    private int version;

    protected OutboxEvent() {}

    @PrePersist
    void prePersist() {
        if (this.id == null) {
            this.id = TsidFactory.nextId();
        }
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
        if (this.status == null) {
            this.status = OutboxStatus.PENDING;
        }
    }

    @Override
    public final boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof OutboxEvent that)) return false;
        return id != null && id.equals(that.id);
    }

    @Override
    public final int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        return "OutboxEvent{id=" + id + "}";
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String aggregateType;
        private Long aggregateId;
        private String eventType;
        private String payload;

        private Builder() {}

        public Builder aggregateType(String aggregateType) { this.aggregateType = aggregateType; return this; }
        public Builder aggregateId(Long aggregateId) { this.aggregateId = aggregateId; return this; }
        public Builder eventType(String eventType) { this.eventType = eventType; return this; }
        public Builder payload(String payload) { this.payload = payload; return this; }

        public OutboxEvent build() {
            OutboxEvent e = new OutboxEvent();
            e.aggregateType = this.aggregateType;
            e.aggregateId = this.aggregateId;
            e.eventType = this.eventType;
            e.payload = this.payload;
            e.status = OutboxStatus.PENDING;
            e.retryCount = 0;
            return e;
        }
    }
}
