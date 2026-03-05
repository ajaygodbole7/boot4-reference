package com.example.boot4ref.common;

import io.hypersistence.tsid.TSID;

/**
 * Centralized TSID factory to ensure a single {@link TSID.Factory} instance
 * is shared across all entities. Multiple independent factories with default
 * configuration risk generating colliding IDs in multi-instance deployments.
 */
public final class TsidFactory {

    private static final TSID.Factory INSTANCE = TSID.Factory.builder().build();

    private TsidFactory() {}

    public static long nextId() {
        return INSTANCE.generate().toLong();
    }
}
