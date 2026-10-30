package io.tapstate.cloud.cluster;

import java.util.UUID;

/** The new Cluster's original CONFIG creation operation; only its dedicated conditional writer may advance it. */
public record ClusterConfigurationInitialization(int formatVersion, String operationId, String phase,
                                                String ownerBootId, String configurationHash) {
    public static final String FIELD = "configurationInitialization";

    public static ClusterConfigurationInitialization available() {
        return new ClusterConfigurationInitialization(1, UUID.randomUUID().toString(), "AVAILABLE", "", "");
    }

    @Override public String toString() { return "ClusterConfigurationInitialization[redacted]"; }
}
