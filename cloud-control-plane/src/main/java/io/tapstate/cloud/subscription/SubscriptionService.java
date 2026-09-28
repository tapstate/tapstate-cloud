package io.tapstate.cloud.subscription;

import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.BusinessException;
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

/**
 * Subscription lifecycle management — create default subscription on org creation,
 * enforce subscription validity at resource-provisioning gates (cluster create, etc.).
 * <p>
 * All status transitions (TRIAL↔ACTIVE↔PAST_DUE↔CANCELLED) go through
 * {@link SubscriptionStateMachineService} which wraps Spring Statemachine.
 * This enforces validated transition paths — rejected transitions return
 * the current status unchanged without mutating the DB.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SubscriptionService {

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionItemRepository itemRepository;
    private final SubscriptionStateMachineService subscriptionStateMachineService;
    private final ApplicationEventPublisher eventPublisher;

    /** Default trial period — 30 days. */
    private static final int TRIAL_DAYS = 30;

    /**
     * Create the default subscription for a newly-created Organization.
     * <p>
     * Always called during organization creation (auto-wired in OrganizationService).
     * Idempotent: returns the existing subscription if one is already present for this org.
     */
    @Transactional
    public Subscription createDefaultSubscription(String orgId) {
        subscriptionRepository.findByOrgId(orgId).ifPresent(existing -> {
            throw new IllegalStateException(
                "Organization " + orgId + " already has a subscription (id=" + existing.getId() + ")");
        });

        Instant now = Instant.now();
        Subscription subscription = Subscription.builder()
            .id(UUID.randomUUID().toString().replace("-", ""))
            .orgId(orgId)
            .status(SubscriptionStatus.TRIAL)
            .currentPeriodStart(now)
            .currentPeriodEnd(now.plus(TRIAL_DAYS, ChronoUnit.DAYS))
            .build();
        subscription = subscriptionRepository.save(subscription);

        // Two default billable items — both "unlimited during trial"
        SubscriptionItem runtimeItem = SubscriptionItem.builder()
            .id(UUID.randomUUID().toString().replace("-", ""))
            .subscriptionId(subscription.getId())
            .orgId(orgId)
            .itemType(SubscriptionItemType.CLUSTER_PIPELINE_RUNTIME)
            .includedQuantity(0) // 0 = unlimited during trial
            .unitPriceCents(0L)
            .metadata(Map.of(
                "plan_tier", "trial",
                "unlimited", "true"
            ))
            .build();

        SubscriptionItem storageItem = SubscriptionItem.builder()
            .id(UUID.randomUUID().toString().replace("-", ""))
            .subscriptionId(subscription.getId())
            .orgId(orgId)
            .itemType(SubscriptionItemType.ATLAS_DATABASE_STORAGE)
            .includedQuantity(0)
            .unitPriceCents(0L)
            .metadata(Map.of(
                "plan_tier", "trial",
                "unlimited", "true"
            ))
            .build();

        itemRepository.saveAll(List.of(runtimeItem, storageItem));

        log.info("Default trial subscription created for org={}, subscriptionId={}", orgId, subscription.getId());
        return subscription;
    }

    /**
     * Get the subscription for an Organization, throwing i18n BusinessException if not found.
     */
    public Subscription getByOrgId(String orgId) {
        return subscriptionRepository.findByOrgId(orgId)
            .orElseThrow(() -> new BusinessException(AppErrorCode.SUBSCRIPTION_NOT_FOUND, 404, orgId));
    }

    /**
     * Get subscription items for an Organization (denormalized lookup — no subscription join needed).
     */
    public List<SubscriptionItem> getItemsByOrgId(String orgId) {
        return itemRepository.findByOrgId(orgId);
    }

    /**
     * Validate subscription is in a state that allows new resource provisioning.
     * <p>
     * Enforcement gate used by ClusterService.createCluster() and future provisioning endpoints.
     * Throws {@link BusinessException} with i18n message if blocked.
     */
    public void assertProvisioningAllowed(String orgId) {
        Subscription subscription = getByOrgId(orgId);
        SubscriptionStatus status = subscription.getStatus();
        if (!status.allowsProvisioning()) {
            switch (status) {
                case PAST_DUE -> throw new BusinessException(AppErrorCode.SUBSCRIPTION_PAST_DUE, 403);
                case CANCELLED -> throw new BusinessException(AppErrorCode.SUBSCRIPTION_CANCELLED, 403);
                default -> { /* TRIAL / ACTIVE — allow */ }
            }
        }
    }

    /**
     * Cancel the subscription for an Organization — marks it CANCELLED in the state
     * machine and publishes {@link SubscriptionCancelledEvent} so the Cluster
     * package can cascade termination of all Clusters owned by this org.
     * <p>
     * Valid from any of: TRIAL / ACTIVE / PAST_DUE. Already-CANCELLED is a
     * no-op (returns current subscription unchanged). After this call,
     * subscription status is fixed as CANCELLED — new provisioning blocked
     * by {@link #assertProvisioningAllowed(String)}.
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

            // Publish event → Cluster package listener cascades termination of
            // every non-terminated Cluster under this org (runs @Async so this
            // HTTP response isn't blocked).
            eventPublisher.publishEvent(new SubscriptionCancelledEvent(
                orgId, subscription.getId(), operator));

            return updated;
        } else {
            log.warn("Subscription cancel transition rejected for org={} (current status={})",
                orgId, subscription.getStatus());
            return subscription;
        }
    }

    /**
     * Transition subscription status — delegates to Spring Statemachine.
     * <p>
     * Maps target status → the correct event type. Caller specifies desired target;
     * the state machine validates that the transition is allowed from the current state.
     * If the pair has no defined state-machine edge (e.g. ACTIVE→TRIAL directly),
     * returns the subscription unchanged with a clear log warning.
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

    /**
     * Map (fromStatus, toStatus) → SubscriptionEventType.
     * Returns null if the pair has no defined state-machine transition.
     */
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
        // No direct transition defined — caller asked for something outside the SM graph
        return null;
    }
}
