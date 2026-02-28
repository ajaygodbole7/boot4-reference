CREATE TABLE outbox_events (
    id              BIGINT          PRIMARY KEY,
    aggregate_type  VARCHAR(100)    NOT NULL,
    aggregate_id    BIGINT          NOT NULL,
    event_type      VARCHAR(100)    NOT NULL,
    payload         JSONB           NOT NULL,
    status          VARCHAR(20)     NOT NULL DEFAULT 'PENDING',
    created_at      TIMESTAMPTZ     NOT NULL,
    processed_at    TIMESTAMPTZ,
    retry_count     INTEGER         NOT NULL DEFAULT 0,
    version         INTEGER         NOT NULL DEFAULT 0
);

CREATE INDEX idx_outbox_pending ON outbox_events(created_at)
    WHERE status = 'PENDING';
CREATE INDEX idx_outbox_aggregate ON outbox_events(aggregate_type, aggregate_id);
