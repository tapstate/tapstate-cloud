package io.tapstate.cloud.subscription;

import io.tapstate.cloud.cluster.Cluster;
import io.tapstate.cloud.cluster.ClusterService;
import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.BusinessException;
import io.tapstate.cloud.mdb.MdbAllocation;
import io.tapstate.cloud.mdb.MdbAllocationService;
import io.tapstate.cloud.subscription.fsm.SubscriptionEventType;
import io.tapstate.cloud.subscription.fsm.SubscriptionStateMachineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bson.types.ObjectId;

/**
 * Subscription lifecycle management — create subscription with default items,
 * provision initial Cluster + MDB allocation on activation, enforce subscription
 * validity at resource-provisioning gates.
 * <p>
 * All status transitions (TRIAL↔ACTIVE↔PAST_DUE↔CANCELLED) go through
 * {@link SubscriptionStateMachineService} which wraps Spring Statemachine.
 * This enforces validated transition paths — rejected transitions return
 * the current status unchanged without mutating the DB.
 * <p>
 * <b>Creation flow</b> (POST /subscription):
 * <ol>
 *   <li>Create Subscription (TRIAL state) + default billable items</li>
 *   <li>Sync create Cluster record → Cluster state machine fires START_PROVISION</li>
 *   <li>Sync create MdbAllocation record → MDB state machine fires START_PROVISION</li>
 * </ol>
 * Both state machines run in parallel; the subscription orchestration layer
 * (this service) coordinates them.
 * <p>
 * <b>Org auto-subscription</b> (OrganizationService): calls
 * {@link #createDefaultSubscription(String)} — creates only the subscription
 * shell (no Cluster + MDB). The explicit POST /subscription endpoint is what
 * triggers actual resource provisioning.
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

    /** Default trial period — 30 days. */
    private static final int TRIAL_DAYS = 30;

    // ========== Org auto-subscription (shell only — no Cluster/MDB) ==========

    /**
     * Create the default subscription shell for a newly-created Organization.
     * <p>
     * Called during organization creation (auto-wired in OrganizationService).
     * Idempotent: throws if one already exists. Does NOT provision Cluster or MDB —
     * those happen when the user explicitly subscribes via POST /subscription.
     */
    @Transactional
    public Subscription createDefaultSubscription(String orgId) {
        subscriptionRepository.findByOrgId(new ObjectId(orgId)).ifPresent(existing -> {
            throw new IllegalStateException(
                "Organization " + orgId + " already has a subscription (id=" + existing.getId() + ")");
        });

        return createSubscriptionShell(orgId);
    }

    // ========== Explicit subscription with Cluster + MDB ==========

    /**
     * Create a full subscription with initial Cluster + Atlas MDB allocation.
     * <p>
     * This is the entry point for POST /subscription — the user explicitly
     * subscribes with a Cluster region/name/spec. Creates:
     * <ol>
     *   <li>Subscription (TRIAL, 30-day period) + default billable items</li>
     *   <li>Cluster record → Cluster FSM async CloudFormation simulation</li>
     *   <li>MdbAllocation record → MDB FSM async Atlas DB + user creation</li>
     * </ol>
     * <p>
     * If the org already has a subscription shell (from OrganizationService),
     * reuses it instead of creating a duplicate.
     *
     * @param orgId       owning organization
     * @param region      AWS region for the initial Cluster
     * @param clusterName Cluster display name
     * @param specKey     provisioning spec (e.g. "basic")
     * @param creatorEmail user email for audit
     * @return the created/updated subscription with Cluster and MDB allocation records
     */
    @Transactional
    public Subscription createSubscription(String orgId, String region, String clusterName,
                                           String specKey, String creatorEmail) {
        // Reuse existing subscription shell or create new one
        Subscription subscription = subscriptionRepository.findByOrgId(new ObjectId(orgId))
            .orElseGet(() -> createSubscriptionShell(orgId));

        ObjectId subscriptionId = subscription.getId();

        // Sync: create Cluster record + fire START_PROVISION
        Cluster cluster = clusterService.createCluster(
            orgId, region, clusterName, specKey, creatorEmail);
        log.info("[Subscription] Cluster created: clusterId={}, subscriptionId={}",
            cluster.getId().toHexString(), subscriptionId.toHexString());

        // Sync: create MdbAllocation record + fire START_PROVISION
        MdbAllocation allocation = mdbAllocationService.allocateMdb(
            orgId, cluster.getId().toHexString(), region, subscriptionId.toHexString());
        log.info("[Subscription] MdbAllocation created: allocationId={}, clusterId={}, subscriptionId={}",
            allocation.getId().toHexString(), cluster.getId().toHexString(), subscriptionId.toHexString());

        // Link allocation ID to Cluster so the Cluster runtime can find its Atlas DB credentials
        cluster.setMdbAllocationId(allocation.getId().toHexString());
        // Note: we need to save cluster — but ClusterService.createCluster already owns the save.
        // We do a lightweight update here (only setting mdbAllocationId).
        // Actually, let's use the repo directly since it's a simple add-on.
        clusterService.updateMdbAllocationId(cluster.getId(), allocation.getId());

        log.info("[Subscription] Full subscription provisioned: subscriptionId={}, clusterId={}, allocationId={}",
            subscriptionId.toHexString(), cluster.getId().toHexString(), allocation.getId().toHexString());

        return subscriptionRepository.findById(subscriptionId).orElse(subscription);
    }

    // ========== Read ==========

    /**
     * Get the subscription for an Organization, throwing i18n BusinessException if not found.
     */
    public Subscription getByOrgId(String orgId) {
        return subscriptionRepository.findByOrgId(new ObjectId(orgId))
            .orElseThrow(() -> new BusinessException(AppErrorCode.SUBSCRIPTION_NOT_FOUND, 404, orgId));
    }

    /**
     * Get subscription items for an Organization (denormalized lookup).
     */
    public List<SubscriptionItem> getItemsByOrgId(String orgId) {
        return itemRepository.findByOrgId(new ObjectId(orgId));
    }

    // ========== Cancel ==========

    /**
     * Cancel the subscription for an Organization — marks it CANCELLED in the state
     * machine and publishes {@link SubscriptionCancelledEvent} so listeners can
     * cascade termination of all Clusters and reclaim all MDB allocations.
     * <p>
     * Valid from any of: TRIAL / ACTIVE / PAST_DUE. Already-CANCELLED is a
     * no-op (returns current subscription unchanged).
     *
     * @param orgId   organization whose subscription to cancel
     * @param operator who triggered the cancel (e.g. "gc:user-email")
     */
    @Transactional
    public Subscription cancelSubscription(String orgId, String operator) {
        Subscription subscription = getByOrgId(orgId);
        if (subscription.getStatus() == SubscriptionStatus.CANCELLED) {
            return subscription; // idempotent — already cancelled
        }

        var result = subscriptionStateMachineService.transition(
            subscription.getId(), SubscriptionEventType.CANCEL_REQUESTED, operator);

        if (result.accepted()) {
            Subscription updated = subscriptionRepository.findById(subscription.getId())
                .orElse(subscription);
            log.info("Subscription CANCELLED for org={} by operator={}", orgId, operator);

            // Publish event → listeners cascade Cluster termination + MDB reclaim
            eventPublisher.publishEvent(new SubscriptionCancelledEvent(
                orgId, subscription.getId().toHexString(), operator));

            return updated;
        } else {
            log.warn("Subscription cancel transition rejected for org={} (current status={})",
                orgId, subscription.getStatus());
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
    public Subscription transitionStatus(String orgId, SubscriptionStatus target) {
        Subscription subscription = getByOrgId(orgId);
        SubscriptionStatus from = subscription.getStatus();
        SubscriptionEventType event = mapToEvent(from, target);

        if (event == null) {
            log.warn("Subscription {} transition {} → {} has no state-machine edge — ignoring request",
                orgId, from, target);
            return subscription;
        }

        log.info("Subscription {} status transition request: {} → {} (event={})", orgId, from, target, event);
        var result = subscriptionStateMachineService.transition(subscription.getId(), event, "api:transitionStatus");

        if (!result.accepted()) {
            log.warn("Subscription {} transition {} rejected by state machine", orgId, event);
            return subscriptionRepository.findById(subscription.getId()).orElse(subscription);
        }
        return subscriptionRepository.findById(subscription.getId()).orElse(subscription);
    }

    // ========== Private helpers ==========

    /** Create subscription shell (record + default items), return persisted entity. */
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

        log.info("Subscription shell created: org={}, subscriptionId={}", orgId, subscription.getId().toHexString());
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
