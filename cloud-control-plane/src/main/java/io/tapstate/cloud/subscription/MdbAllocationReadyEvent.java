package io.tapstate.cloud.subscription;

import org.bson.types.ObjectId;

/**
 * Published after an MdbAllocation document persists its transition into READY.
 *
 * @param allocationId   the allocation that became ready
 * @param clusterId      the Cluster owning the allocation
 * @param subscriptionId the Subscription owning the allocation
 */
public record MdbAllocationReadyEvent(ObjectId allocationId, ObjectId clusterId,
                                      ObjectId subscriptionId) {
}
