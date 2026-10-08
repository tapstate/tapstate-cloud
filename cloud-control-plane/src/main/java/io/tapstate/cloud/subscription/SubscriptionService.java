package io.tapstate.cloud.subscription;

import io.tapstate.cloud.cluster.Cluster;
import io.tapstate.cloud.cluster.ClusterService;
import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.BusinessException;
import io.tapstate.cloud.mdb.MdbAllocation;
import io.tapstate.cloud.mdb.MdbAllocationService;
import io.tapstate.cloud.progress.DeploymentOpType;
import io.tapstate.cloud.progress.DeploymentStepRecorder;
import io.tapstate.cloud.subscription.fsm.SubscriptionEventType;
import io.tapstate.cloud.subscription.fsm.SubscriptionStateMachineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * Subscription lifecycle management — create subscriptions with their default
 * items, provision the one Cluster + one MdbAllocation every subscription owns,
 * and enforce subscription validity at resource-provisioning gates.
 * <p>
 * An Organization owns many Subscriptions; each Subscription owns exactly one
 * Cluster and one MdbAllocation. Creating an Organization no longer creates a
 * subscription — every subscription is created explicitly via
 * {@code POST /v1/api/orgs/{orgId}/subscriptions}.
 * <p>
 * All status transitions (TRIAL↔ACTIVE↔PAST_DUE↔CANCELLED) go through
 * {@link SubscriptionStateMachineService} which wraps Spring Statemachine.
 * This enforces validated transition paths — rejected transitions return
 * the current status unchanged without mutating the DB.
 * <p>
 * <b>Creation flow</b> ({@code POST /subscriptions}):
 * <ol>
 *   <li>Create Subscription (TRIAL state) + its two default billable items</li>
 *   <li>Sync create Cluster record (linked to the new subscription)</li>
 *   <li>Sync create MdbAllocation record (linked to the new subscription)</li>
 *   <li>Start Cluster provisioning; both state machines then run async</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SubscriptionService {

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionItemRepository itemRepository;
    private final SubscriptionStateMachineService subscriptionStateMachineService;
    private final ApplicationEventPublisher eventPublisher;
    private final ClusterService clusterService;
    private final MdbAllocationService mdbAllocationService;
    private final DeploymentStepRecorder deploymentStepRecorder;

    /** Default trial period — 30 days. */
    private static final int TRIAL_DAYS = 30;

    // ========== Create subscription with its Cluster + MDB ==========

    /**
     * Create a new subscription (TRIAL, 30-day period) with its two default
     * billable items, then provision its one Cluster and one Atlas MdbAllocation.
     *
     * @param orgId        owning organization
     * @param region       AWS region for the Cluster
     * @param clusterName  Cluster display name (unique within org + region)
     * @param specKey      provisioning spec (e.g. "basic")
     * @param creatorEmail user email for audit
     * @return the persisted subscription with Cluster and MDB allocation records
     */
    @Transactional
    public Subscription createSubscription(String orgId, String region, String clusterName,
                                           String specKey, String creatorEmail) {
        // 1. Subscription shell + the two default billable items.
        Subscription subscription = createSubscriptionShell(orgId);
        ObjectId subscriptionId = subscription.getId();

        // Seed every INSTALL operation as PENDING before any worker can start.
        deploymentStepRecorder.seed(new ObjectId(orgId), subscriptionId, DeploymentOpType.INSTALL);

        // 2. Sync: create Cluster record (provisioning starts only after the
        //    allocation link below is durable).
        Cluster cluster = clusterService.createCluster(
            orgId, subscriptionId, region, clusterName, specKey, creatorEmail);
        log.info("[Subscription] Cluster created: clusterId={}, subscriptionId={}",
            cluster.getId().toHexString(), subscriptionId.toHexString());

        // 3. Sync: create MdbAllocation record + fire its START_PROVISION.
        MdbAllocation allocation = mdbAllocationService.allocateMdb(
            orgId, cluster.getId().toHexString(), region, subscriptionId.toHexString());
        log.info("[Subscription] MdbAllocation created: allocationId={}, clusterId={}, subscriptionId={}",
            allocation.getId().toHexString(), cluster.getId().toHexString(), subscriptionId.toHexString());

        // Link allocation ID to Cluster so the Cluster runtime can find its Atlas DB credentials.
        cluster.setMdbAllocationId(allocation.getId());
        clusterService.updateMdbAllocationId(cluster.getId(), allocation.getId());

        // Record the provisioned resource IDs on this subscription's own billable
        // items, so usage aggregation and billing can resolve each line to the
        // actual Cluster / MdbAllocation instead of inferring the link.
        linkItemResource(subscriptionId, SubscriptionItemType.CLUSTER_PIPELINE_RUNTIME,
            cluster.getId(), null);
        linkItemResource(subscriptionId, SubscriptionItemType.ATLAS_DATABASE_STORAGE,
            null, allocation.getId());

        // Start Cluster provisioning only now that its allocation link is
        // durable. The async run loads the Cluster after this point and always
        // observes the link, so the runtime URI resolves via the allocation
        // instead of silently falling back to the global URI / local sidecar.
        clusterService.startProvisioning(cluster.getId(),
            "subscription:" + creatorEmail);

        // Both FSMs are now running; the subscription resources stay PROVISIONING
        // until SubscriptionResourceCoordinator observes the Cluster AND allocation READY.
        subscription.setResourceState(ResourceState.PROVISIONING);
        subscription.setUpdatedAt(Instant.now());
        subscriptionRepository.save(subscription);

        log.info("[Subscription] Full subscription provisioned: subscriptionId={}, clusterId={}, allocationId={}",
            subscriptionId.toHexString(), cluster.getId().toHexString(), allocation.getId().toHexString());

        return subscriptionRepository.findById(subscriptionId).orElse(subscription);
    }

    // ========== Read ==========

    /** List every Subscription owned by an Organization, newest first. */
    public List<Subscription> listByOrgId(String orgId) {
        return subscriptionRepository.findByOrgIdOrderByCreatedAtDesc(new ObjectId(orgId));
    }

    /**
     * Get a subscription scoped by its owning Organization. Throws i18n
     * BusinessException (404) when it does not exist or belongs to another org.
     */
    public Subscription getByIdAndOrgId(String subscriptionId, String orgId) {
        return subscriptionRepository
            .findByIdAndOrgId(new ObjectId(subscriptionId), new ObjectId(orgId))
            .orElseThrow(() -> new BusinessException(AppErrorCode.SUBSCRIPTION_NOT_FOUND, 404, subscriptionId));
    }

    /** Get the billable items of one subscription. */
    public List<SubscriptionItem> getItems(ObjectId subscriptionId) {
        return itemRepository.findBySubscriptionId(subscriptionId);
    }

    // ========== Cancel ==========

    /**
     * Cancel one subscription — mark it CANCELLED through the state machine and
     * publish {@link SubscriptionCancelledEvent} so listeners cascade the
     * termination of the subscription's own Cluster and the reclaim of its own
     * MdbAllocation. Other subscriptions of the same org are untouched.
     * <p>
     * Valid from any of: TRIAL / ACTIVE / PAST_DUE. Already-CANCELLED is a
     * no-op (returns current subscription unchanged).
     *
     * @param subscriptionId subscription to cancel
     * @param orgId          owning organization (ownership check)
     * @param operator       who triggered the cancel (e.g. "gc:user-email")
     */
    @Transactional
    public Subscription cancelSubscription(String subscriptionId, String orgId, String operator) {
        Subscription subscription = getByIdAndOrgId(subscriptionId, orgId);
        if (subscription.getStatus() == SubscriptionStatus.CANCELLED) {
            return subscription; // idempotent — already cancelled
        }

        var result = subscriptionStateMachineService.transition(
            subscription.getId(), SubscriptionEventType.CANCEL_REQUESTED, operator);

        if (result.accepted()) {
            Subscription updated = subscriptionRepository.findById(subscription.getId())
                .orElse(subscription);
            log.info("Subscription {} CANCELLED for org={} by operator={}",
                subscriptionId, orgId, operator);

            // Seed every UNINSTALL operation as PENDING so the API can report
            // release progress even before the cascading workers pick up.
            deploymentStepRecorder.seed(new ObjectId(orgId), subscription.getId(),
                DeploymentOpType.UNINSTALL);

            // Publish event → listeners cascade this subscription's Cluster termination + MDB reclaim
            eventPublisher.publishEvent(new SubscriptionCancelledEvent(
                orgId, subscription.getId().toHexString(), operator));

            return updated;
        } else {
            log.warn("Subscription cancel transition rejected for subscription={} (current status={})",
                subscriptionId, subscription.getStatus());
            return subscription;
        }
    }

    // ========== Status transition ==========

    /**
     * Transition subscription status — delegates to Spring Statemachine.
     * <p>
     * Maps target status → the correct event type. Caller specifies desired target;
     * the state machine validates that the transition is allowed from the current state.
     */
    @Transactional
    public Subscription transitionStatus(String subscriptionId, String orgId, SubscriptionStatus target) {
        Subscription subscription = getByIdAndOrgId(subscriptionId, orgId);
        SubscriptionStatus from = subscription.getStatus();
        SubscriptionEventType event = mapToEvent(from, target);

        if (event == null) {
            log.warn("Subscription {} transition {} → {} has no state-machine edge — ignoring request",
                subscriptionId, from, target);
            return subscription;
        }

        log.info("Subscription {} status transition request: {} → {} (event={})", subscriptionId, from, target, event);
        var result = subscriptionStateMachineService.transition(subscription.getId(), event, "api:transitionStatus");

        if (!result.accepted()) {
            log.warn("Subscription {} transition {} rejected by state machine", subscriptionId, event);
            return subscriptionRepository.findById(subscription.getId()).orElse(subscription);
        }
        return subscriptionRepository.findById(subscription.getId()).orElse(subscription);
    }

    // ========== Private helpers ==========

    /**
     * Stamp one billable item with the resource ID it bills. A subscription
     * always carries both default items; a missing item means the items were
     * created by an older path and is logged, not fatal.
     */
    private void linkItemResource(ObjectId subscriptionId, SubscriptionItemType itemType,
                                  ObjectId clusterId, ObjectId mdbAllocationId) {
        itemRepository.findBySubscriptionIdAndItemType(subscriptionId, itemType)
            .ifPresentOrElse(
                item -> {
                    item.setClusterId(clusterId);
                    item.setMdbAllocationId(mdbAllocationId);
                    itemRepository.save(item);
                    log.info("[Subscription] {} item linked: subscriptionId={}, clusterId={}, mdbAllocationId={}",
                        itemType, subscriptionId.toHexString(),
                        clusterId == null ? null : clusterId.toHexString(),
                        mdbAllocationId == null ? null : mdbAllocationId.toHexString());
                },
                () -> log.warn("[Subscription] {} item missing for subscription {} — resource link not recorded",
                    itemType, subscriptionId.toHexString()));
    }

    /** Create a subscription record with its two default billable items. */
    private Subscription createSubscriptionShell(String orgId) {
        Instant now = Instant.now();
        Subscription subscription = Subscription.builder()
            .orgId(new ObjectId(orgId))
            .status(SubscriptionStatus.TRIAL)
            .currentPeriodStart(now)
            .currentPeriodEnd(now.plus(TRIAL_DAYS, ChronoUnit.DAYS))
            .build();
        subscription = subscriptionRepository.save(subscription);

        // Two default billable items — both "unlimited during trial"
        SubscriptionItem runtimeItem = SubscriptionItem.builder()
            .subscriptionId(subscription.getId())
            .orgId(new ObjectId(orgId))
            .itemType(SubscriptionItemType.CLUSTER_PIPELINE_RUNTIME)
            .includedQuantity(0)
            .unitPriceCents(0L)
            .metadata(Map.of("plan_tier", "trial", "unlimited", "true"))
            .build();

        SubscriptionItem storageItem = SubscriptionItem.builder()
            .subscriptionId(subscription.getId())
            .orgId(new ObjectId(orgId))
            .itemType(SubscriptionItemType.ATLAS_DATABASE_STORAGE)
            .includedQuantity(0)
            .unitPriceCents(0L)
            .metadata(Map.of("plan_tier", "trial", "unlimited", "true"))
            .build();

        itemRepository.saveAll(List.of(runtimeItem, storageItem));

        log.info("Subscription created: org={}, subscriptionId={}", orgId, subscription.getId().toHexString());
        return subscription;
    }

    private SubscriptionEventType mapToEvent(SubscriptionStatus from, SubscriptionStatus to) {
        if (from == SubscriptionStatus.TRIAL && to == SubscriptionStatus.ACTIVE) {
            return SubscriptionEventType.PAYMENT_CONFIRMED;
        }
        if (from == SubscriptionStatus.ACTIVE && to == SubscriptionStatus.PAST_DUE) {
            return SubscriptionEventType.PAYMENT_FAILED;
        }
        if (from == SubscriptionStatus.PAST_DUE && to == SubscriptionStatus.ACTIVE) {
            return SubscriptionEventType.PAYMENT_RECEIVED;
        }
        if ((from == SubscriptionStatus.TRIAL || from == SubscriptionStatus.ACTIVE || from == SubscriptionStatus.PAST_DUE)
            && to == SubscriptionStatus.CANCELLED) {
            return SubscriptionEventType.CANCEL_REQUESTED;
        }
        if (from == SubscriptionStatus.CANCELLED && to == SubscriptionStatus.TRIAL) {
            return SubscriptionEventType.ADMIN_RESTORE;
        }
        return null;
    }
}
