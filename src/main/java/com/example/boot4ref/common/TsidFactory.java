package com.example.boot4ref.common;

import io.hypersistence.tsid.TSID;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Centralized TSID factory to ensure a single {@link TSID.Factory} instance
 * is shared across all entities. Multiple independent factories with default
 * configuration risk generating colliding IDs in multi-instance deployments.
 *
 * <p>Node ID resolution order:
 * <ol>
 *   <li>{@code TSID_NODE_ID} env var — explicit override (0–1023)</li>
 *   <li>Hostname hash mod 1024 — works for Kubernetes pods, Docker containers,
 *       and standalone machines with no configuration</li>
 * </ol>
 */
public final class TsidFactory {

    private static final int NODE_COUNT = 1024;
    private static final int MAX_NODE_ID = NODE_COUNT - 1;

    private static final TSID.Factory INSTANCE = buildFactory();

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
        } else {
            builder.withNode(nodeIdFromHostname());
        }
        return builder.build();
    }

    private static int nodeIdFromHostname() {
        try {
            String host = System.getenv().getOrDefault("HOSTNAME",
                    InetAddress.getLocalHost().getHostName());
            return (host.hashCode() & 0x7FFFFFFF) % NODE_COUNT;
        } catch (UnknownHostException e) {
            return 0;
        }
    }

    private TsidFactory() {}

    public static long nextId() {
        return INSTANCE.generate().toLong();
    }
}
