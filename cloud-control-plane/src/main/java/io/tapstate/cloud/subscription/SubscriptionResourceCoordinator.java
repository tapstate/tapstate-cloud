package io.tapstate.cloud.subscription;

import io.tapstate.cloud.cluster.Cluster;
import io.tapstate.cloud.cluster.ClusterRepository;
import io.tapstate.cloud.cluster.ClusterState;
import io.tapstate.cloud.mdb.MdbAllocation;
import io.tapstate.cloud.mdb.MdbAllocationRepository;
import io.tapstate.cloud.mdb.MdbAllocationStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Converges a Subscription's {@link ResourceState} from the independent Cluster
 * and MdbAllocation state machines.
 * <p>
 * The two FSMs provision in parallel and neither knows about the other. Each one
 * publishes a ready event after persisting its READY transition; this coordinator
 * is the single join point. A Subscription flips to
 * {@link ResourceState#READY} only when its own Cluster is READY and its own
 * MdbAllocation is READY — an Organization owns many subscriptions, so the join
 * is strictly subscription-scoped and never observes another subscription's
 * resources.
 * The flip is idempotent (repeated events are a no-op), never applies to a
 * CANCELLED subscription, and uses a conditional atomic update so two events
 * racing the cancel transition cannot resurrect a cancelled subscription.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SubscriptionResourceCoordinator {

    private final SubscriptionRepository subscriptionRepository;
    private final ClusterRepository clusterRepository;
    private final MdbAllocationRepository allocationRepository;
    private final MongoTemplate mongoTemplate;

    @EventListener
    public void onClusterReady(ClusterReadyEvent event) {
        allocationRepository.findByClusterId(event.clusterId())
            .filter(allocation -> allocation.getStatus() == MdbAllocationStatus.READY)
            .ifPresent(allocation -> evaluate(allocation.getSubscriptionId()));
    }

    @EventListener
    public void onMdbAllocationReady(MdbAllocationReadyEvent event) {
        clusterRepository.findById(event.clusterId())
            .filter(cluster -> cluster.getState() == ClusterState.READY)
            .ifPresent(cluster -> evaluate(event.subscriptionId()));
    }

    /**
     * Re-evaluate and, when both resource families are READY, atomically mark the
     * Subscription's resources READY.
     */
    void evaluate(ObjectId subscriptionId) {
        if (subscriptionId == null) {
            return;
        }
        Subscription subscription = subscriptionRepository.findById(subscriptionId).orElse(null);
        if (subscription == null
            || subscription.getResourceState() == ResourceState.READY
            || subscription.getStatus() == SubscriptionStatus.CANCELLED) {
            return;
        }

        List<Cluster> clusters = clusterRepository.findBySubscriptionId(subscriptionId);
        List<MdbAllocation> allocations = allocationRepository.findBySubscriptionId(subscriptionId);
        if (clusters.isEmpty() || allocations.isEmpty()) {
            return;
        }
        boolean clustersReady = clusters.stream()
            .allMatch(cluster -> cluster.getState() == ClusterState.READY);
        boolean allocationsReady = allocations.stream()
            .allMatch(allocation -> allocation.getStatus() == MdbAllocationStatus.READY);
        if (!clustersReady || !allocationsReady) {
            log.debug("[ResourceCoordinator] subscription {} not ready yet (clustersReady={}, allocationsReady={})",
                subscriptionId.toHexString(), clustersReady, allocationsReady);
            return;
        }

        // Conditional flip: excludes CANCELLED and already-READY documents so a
        // racing cancel can never be overwritten back to a provisioning view.
        Query query = Query.query(Criteria.where("_id").is(subscriptionId)
            .and("status").ne(SubscriptionStatus.CANCELLED.name())
            .and("resourceState").ne(ResourceState.READY.name()));
        Instant now = Instant.now();
        Update update = new Update()
            .set("resourceState", ResourceState.READY.name())
            .set("resourcesReadyAt", now)
            .set("updatedAt", now);

        long modified = mongoTemplate.updateFirst(query, update, Subscription.class).getModifiedCount();
        if (modified > 0) {
            log.info("[ResourceCoordinator] subscription {} resources READY ({} cluster(s), {} allocation(s))",
                subscriptionId.toHexString(), clusters.size(), allocations.size());
        }
    }
}
