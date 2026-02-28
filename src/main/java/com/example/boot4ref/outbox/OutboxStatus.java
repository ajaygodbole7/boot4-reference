package com.example.boot4ref.outbox;

/**
 * Status of an outbox event entry.
 */
public enum OutboxStatus {
    PENDING,
    PROCESSED,
    FAILED
}
