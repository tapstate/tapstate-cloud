package io.tapstate.cloud.subscription;

import org.bson.types.ObjectId;

/**
 * Published after a Cluster document persists its transition into READY.
 *
 * @param clusterId the Cluster that became ready
 */
public record ClusterReadyEvent(ObjectId clusterId) {
}
