package com.example.boot4ref.common;

import io.hypersistence.tsid.TSID;

/**
 * Centralized TSID factory to ensure a single {@link TSID.Factory} instance
 * is shared across all entities. Multiple independent factories with default
 * configuration risk generating colliding IDs in multi-instance deployments.
 *
 * <p>The node ID is read from the {@code TSID_NODE_ID} environment variable.
 * If unset, falls back to random node resolution (safe for single-instance
 * deployments). For multi-instance deployments, set {@code TSID_NODE_ID} to a
 * unique value per instance (e.g., Kubernetes pod ordinal or hostname hash).
 */
public final class TsidFactory {

    private static final TSID.Factory INSTANCE = buildFactory();

    private static final int MAX_NODE_ID = 1023;

    private static TSID.Factory buildFactory() {
        TSID.Factory.Builder builder = TSID.Factory.builder();
        String nodeIdEnv = System.getenv("TSID_NODE_ID");
        if (nodeIdEnv != null && !nodeIdEnv.isBlank()) {
            int nodeId;
            try {
                nodeId = Integer.parseInt(nodeIdEnv);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "TSID_NODE_ID must be an integer 0–" + MAX_NODE_ID + ", got: '" + nodeIdEnv + "'", e);
            }
            if (nodeId < 0 || nodeId > MAX_NODE_ID) {
                throw new IllegalArgumentException(
                        "TSID_NODE_ID must be 0–" + MAX_NODE_ID + ", got: " + nodeId);
            }
            builder.withNode(nodeId);
        }
        return builder.build();
    }

    private TsidFactory() {}

    public static long nextId() {
        return INSTANCE.generate().toLong();
    }
}
