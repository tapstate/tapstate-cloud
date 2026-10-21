package io.tapstate.cloud.subscription;

import io.tapstate.cloud.cluster.Cluster;
import io.tapstate.cloud.cluster.ClusterService;
import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.BusinessException;
import io.tapstate.cloud.mdb.MdbAllocation;
import io.tapstate.cloud.mdb.MdbAllocationService;
import io.tapstate.cloud.payment.PaymentGateway;
import io.tapstate.cloud.payment.PaymentGateway.CreateSessionRequest;
import io.tapstate.cloud.payment.PaymentGateway.PaymentSessionResult;
import io.tapstate.cloud.payment.stripe.StripePaymentChannelRecord;
import io.tapstate.cloud.payment.stripe.StripePriceEntry;
import io.tapstate.cloud.payment.stripe.StripeProductService;
import io.tapstate.cloud.progress.DeploymentOpType;
import io.tapstate.cloud.progress.DeploymentStepRecorder;
import io.tapstate.cloud.subscription.fsm.SubscriptionEventType;
import io.tapstate.cloud.subscription.fsm.SubscriptionStateMachineService;
import io.tapstate.cloud.version.ClusterVersion;
import io.tapstate.cloud.version.ClusterVersionService;
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
 * There is one creation path: {@link #createCheckoutSession} — Stripe Checkout
 * creates a shell Subscription (PENDING_PAYMENT) plus a hosted-payment URL, and
 * provisioning happens only after the {@code checkout.session.completed} webhook
 * fires (see {@link #activateAndProvision}). No API can create a subscription, or
 * move one into a provisionable state, without a channel confirming payment.
 * <p>
 * All status transitions (PENDING_PAYMENT / TRIAL / ACTIVE / PAST_DUE / PENDING_CANCEL / CANCELLED)
 * go through {@link SubscriptionStateMachineService}. This enforces validated
 * transition paths — rejected transitions return the current status unchanged.
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
    private final ClusterVersionService clusterVersionService;

    // Stripe / payment-gateway wiring — injected via Map<String, PaymentGateway>
    // so we can look up by PaymentMethod at runtime. The Stripe channel is the
    // only one wired today; AWS/GCP are reserved for future expansion.
    private final SpecConfigService specConfigService;
    private final StripeProductService stripeProductService;
    private final PaymentGateway paymentGateway;

    // ========== Stripe Checkout flow ================

    /**
     * Create a Checkout Session + PENDING_PAYMENT shell Subscription.
     * <p>
     * Dual-query validation:
     * <ol>
     *   <li>{@code spec_config} — find enabled SpecConfig by key (throws if missing/deprecated)</li>
     *   <li>{@code stripe_products} — find enabled StripePriceEntry for the same specKey
     *       (throws if no pricing configured in Stripe catalog)</li>
     * </ol>
     * <p>
     * The returned Subscription is a shell — NO Cluster / MDB created yet.
     * Provisioning happens asynchronously in {@link #activateAndProvision}
     * after the Stripe webhook confirms payment.
     *
     * @param orgId        owning organization
     * @param specKey      platform spec key (e.g. "2c8g")
     * @param userEmail    user email (passed to Stripe Customer lookup/create)
     * @param clientRequestId idempotency key — prevents double-create on retry
     * @return Subscription shell + redirect URL (wrapped in {@link CheckoutResult})
     */
    @Transactional
    public CheckoutResult createCheckoutSession(String orgId, String specKey,
                                                 String userEmail, String clientRequestId) {
        // ① Validate spec exists and is enabled
        SpecConfig specConfig = specConfigService.findEnabledByKey(specKey)
                .orElseThrow(() -> new BusinessException(
                        AppErrorCode.SUBSCRIPTION_SPEC_NOT_FOUND, 400, specKey));

        // ② Validate Stripe pricing is configured for this specKey
        StripePriceEntry priceEntry = stripeProductService.findEnabledBySpecKey(specKey)
                .orElseThrow(() -> new BusinessException(
                        AppErrorCode.SUBSCRIPTION_PRICING_NOT_CONFIGURED, 400, specKey));

        // USD monthly is the only shape the product sells. The catalogue sync only
        // stores those, so anything else was written outside it — refuse rather
        // than take a payment in a currency the price page never offered.
        if (!"usd".equalsIgnoreCase(priceEntry.currency())
                || !"month".equalsIgnoreCase(priceEntry.billingInterval())) {
            throw new BusinessException(
                    AppErrorCode.SUBSCRIPTION_PRICING_NOT_CONFIGURED, 400, specKey);
        }

        // ③ Resolve the latest ACTIVE Cluster version (same as legacy path)
        ClusterVersion clusterVersion = clusterVersionService.findClusterLastVersion();

        // ④ Check idempotency — duplicate clientRequestId + orgId means same subscription
        if (clientRequestId != null && !clientRequestId.isBlank()) {
            ObjectId orgOid = new ObjectId(orgId);
            var existing = subscriptionRepository.findByClientRequestIdAndOrgId(clientRequestId, orgOid);
            if (existing.isPresent()) {
                Subscription dup = existing.get();
                if (dup.getStripe() != null && dup.getStripe().getCheckoutSessionId() != null) {
                    log.info("[Subscription] idempotent createCheckoutSession — returning existing subscription {}",
                            dup.getId());
                    // We need a URL — re-fetch the session? Actually we don't store the original URL,
                    // but we can throw "already exists" and let the client look up the subscription instead.
                    throw new BusinessException(
                            AppErrorCode.SUBSCRIPTION_ALREADY_ACTIVE, 409, dup.getId().toHexString());
                }
            }
        }

        // ⑤ Call PaymentGateway to create Checkout Session
        CreateSessionRequest sessionRequest = new CreateSessionRequest(
                orgId, userEmail, specConfig, priceEntry.priceId());
        PaymentSessionResult sessionResult = paymentGateway.createSession(sessionRequest);

        // ⑥ Create PENDING_PAYMENT shell Subscription
        Instant now = Instant.now();
        Subscription subscription = Subscription.builder()
                .orgId(new ObjectId(orgId))
                .status(SubscriptionStatus.PENDING_PAYMENT)
                .paymentMethod(paymentGateway.getPaymentMethod())
                .specConfig(specConfig)          // snapshot!
                .clientRequestId(clientRequestId)
                .currentPeriodStart(now)
                .currentPeriodEnd(now.plus(30, ChronoUnit.DAYS)) // provisional — Stripe sets real date later
                .build();

        // Write Stripe embed from channel ref
        if (sessionResult.channelRef() instanceof StripePaymentChannelRecord ref) {
            StripeSubscriptionRef stripeRef = StripeSubscriptionRef.builder()
                    .customerId(ref.stripeCustomerId())
                    .checkoutSessionId(ref.checkoutSessionId())
                    .priceId(ref.priceId())
                    .productId(ref.productId())
                    .autoRenewAuthorized(false)  // becomes true after checkout completes
                    .build();
            subscription.setStripe(stripeRef);
        }

        subscription = subscriptionRepository.save(subscription);

        // Seed deployment progress tracking — provisioning will start later
        deploymentStepRecorder.seed(new ObjectId(orgId), subscription.getId(), DeploymentOpType.INSTALL);

        // Create two default billable items (same as legacy path, but price will
        // be zero until we actually start provisioning — the real billing line item
        // is tracked by Stripe).
        SubscriptionItem runtimeItem = SubscriptionItem.builder()
                .subscriptionId(subscription.getId())
                .orgId(new ObjectId(orgId))
                .itemType(SubscriptionItemType.CLUSTER_PIPELINE_RUNTIME)
                .includedQuantity(0)
                .unitPriceCents(0L)
                .metadata(Map.of("plan_tier", "pending_payment"))
                .build();

        SubscriptionItem storageItem = SubscriptionItem.builder()
                .subscriptionId(subscription.getId())
                .orgId(new ObjectId(orgId))
                .itemType(SubscriptionItemType.ATLAS_DATABASE_STORAGE)
                .includedQuantity(0)
                .unitPriceCents(0L)
                .metadata(Map.of("plan_tier", "pending_payment"))
                .build();

        itemRepository.saveAll(List.of(runtimeItem, storageItem));

        log.info("[Subscription] PENDING_PAYMENT shell created: subscriptionId={}, checkoutSessionId={}",
                subscription.getId().toHexString(),
                subscription.getStripe() != null ? subscription.getStripe().getCheckoutSessionId() : "null");

        return new CheckoutResult(subscription, sessionResult.sessionUrl());
    }

    /**
     * Activate a PENDING_PAYMENT subscription and start provisioning.
     * <p>
     * Called by {@link StripeWebhookEventService#dispatch} on receipt of
     * {@code checkout.session.completed}. This method is idempotent — the
     * {@code provisionInitiated} flag is checked and set atomically on first call,
     * so webhook replays cannot double-provision.
     *
     * @param subscriptionId hex string of the Subscription ObjectId
     */
    @Transactional
    public void activateAndProvision(String subscriptionId) {
        Subscription subscription = subscriptionRepository.findById(new ObjectId(subscriptionId))
                .orElseThrow(() -> new BusinessException(
                        AppErrorCode.SUBSCRIPTION_NOT_FOUND, 404, subscriptionId));

        // Idempotency — already activated?
        if ((subscription.getStatus() == SubscriptionStatus.ACTIVE
                || subscription.getStatus() == SubscriptionStatus.TRIAL)
                && subscription.isProvisionInitiated()) {
            log.info("[Subscription] activateAndProvision — already activated+provisioned, idempotent skip");
            return;
        }

        // FSM transition: PENDING_PAYMENT → ACTIVE, or → TRIAL for a trial spec.
        if (subscription.getStatus() == SubscriptionStatus.PENDING_PAYMENT) {
            boolean trial = subscription.getSpecConfig() != null && subscription.getSpecConfig().hasTrial();
            SubscriptionEventType checkoutEvent = trial
                    ? SubscriptionEventType.CHECKOUT_COMPLETED_TRIAL
                    : SubscriptionEventType.CHECKOUT_COMPLETED;
            var result = subscriptionStateMachineService.transition(
                    subscription.getId(), checkoutEvent,
                    "webhook:checkout.session.completed");
            if (!result.accepted()) {
                log.warn("[Subscription] FSM transition {} rejected for subscription={}, current={}",
                        checkoutEvent, subscriptionId, subscription.getStatus());
                throw new BusinessException(AppErrorCode.SUBSCRIPTION_ALREADY_ACTIVE, 400, subscriptionId);
            }
            subscription = subscriptionRepository.findById(subscription.getId()).orElse(subscription);
        } else if (subscription.getStatus() != SubscriptionStatus.ACTIVE
                && subscription.getStatus() != SubscriptionStatus.TRIAL) {
            log.warn("[Subscription] activateAndProvision — subscription in unexpected status {}, aborting",
                    subscription.getStatus());
            return;
        }

        // Idempotent provisioning check — set atomically
        if (subscription.isProvisionInitiated()) {
            log.info("[Subscription] activateAndProvision — provisionInitiated already true, skipping provisioning");
            return;
        }
        subscription.setProvisionInitiated(true);
        subscriptionRepository.save(subscription);

        // Provision Cluster + MDB — use specKey from the snapshot
        SpecConfig specConfig = subscription.getSpecConfig();
        String specKey = specConfig != null ? specConfig.getKey() : "basic";

        // Resolve the Cluster version (pinned at activation time — matches user intent)
        String clusterVersion = clusterVersionService.findClusterLastVersion().getVersion();

        // We need a region and clusterName — but the Checkout shell doesn't carry these yet.
        // The client (Console) will provide them in a separate "finalize provisioning" API call
        // AFTER checkout. For now we skip this and log — the actual provisioning happens via
        // the Console's "finalize" endpoint which calls clusterService.createCluster().
        //
        // This approach separates the payment confirmation from the infrastructure
        // creation, letting the user review their spec + confirm before provisioning starts.
        log.info("[Subscription] activateAndProvision activated subscriptionId={} (status={}, provisionInitiated={}, specKey={})",
                subscriptionId, subscription.getStatus(), subscription.isProvisionInitiated(), specKey);
    }

    /**
     * Finalize provisioning — called by Console after checkout confirmation.
     * Creates the Cluster and MdbAllocation with the spec snapshot pinned on
     * the Subscription. Idempotent — if Cluster already exists, silently skips.
     */
    @Transactional
    public Subscription finalizeProvisioning(String subscriptionId, String region,
                                              String clusterName, String creatorEmail) {
        return finalizeProvisioning(subscriptionId, region, clusterName, creatorEmail, null);
    }

    /**
     * @param version Cluster version to deploy; blank/null resolves the latest ACTIVE
     *                version, an explicit value is pinned for this Cluster
     */
    @Transactional
    public Subscription finalizeProvisioning(String subscriptionId, String region,
                                              String clusterName, String creatorEmail,
                                              String version) {
        Subscription subscription = subscriptionRepository.findById(new ObjectId(subscriptionId))
                .orElseThrow(() -> new BusinessException(
                        AppErrorCode.SUBSCRIPTION_NOT_FOUND, 404, subscriptionId));

        // Payment must be confirmed first — a PENDING_PAYMENT shell has no right to
        // infrastructure, and finalising it would provision for free. TRIAL is
        // allowed: a trial has a bound payment method and is exactly the state the
        // free instance is for. Everything else (PAST_DUE, PENDING_CANCEL,
        // CANCELLED) has no right to new resources.
        if (subscription.getStatus() == SubscriptionStatus.PENDING_PAYMENT) {
            throw new BusinessException(
                    AppErrorCode.SUBSCRIPTION_PENDING_PAYMENT, 400, subscriptionId);
        }
        if (subscription.getStatus() != SubscriptionStatus.ACTIVE
                && subscription.getStatus() != SubscriptionStatus.TRIAL) {
            throw new BusinessException(
                    AppErrorCode.SUBSCRIPTION_NOT_ACTIVE, 400, subscriptionId);
        }

        // Idempotent: resources already exist for this subscription.
        if (subscription.getResourceState() != null) {
            log.info("[Subscription] finalizeProvisioning — resources already requested (resourceState={}), "
                    + "idempotent skip", subscription.getResourceState());
            return subscription;
        }

        SpecConfig specConfig = subscription.getSpecConfig();
        if (specConfig == null) {
            throw new BusinessException(AppErrorCode.SUBSCRIPTION_SPEC_NOT_FOUND, 400, subscriptionId);
        }

        String requestedVersion = version == null ? null : version.trim();
        String clusterVersion = (requestedVersion == null || requestedVersion.isEmpty())
                ? clusterVersionService.findClusterLastVersion().getVersion()
                : clusterVersionService.findActiveByVersion(requestedVersion).getVersion();
        ObjectId subscriptionOid = subscription.getId();
        ObjectId orgOid = subscription.getOrgId();
        String orgId = orgOid.toHexString();

        // Create Cluster (specKey from snapshot)
        Cluster cluster = clusterService.createCluster(
                orgId, subscriptionOid, region, clusterName,
                specConfig.getKey(), creatorEmail, clusterVersion);

        // Create MdbAllocation
        MdbAllocation allocation = mdbAllocationService.allocateMdb(
                orgId, cluster.getId().toHexString(), region, subscriptionOid.toHexString());

        // Link allocation → Cluster
        cluster.setMdbAllocationId(allocation.getId());
        clusterService.updateMdbAllocationId(cluster.getId(), allocation.getId());

        // Link resource IDs to billable items
        linkItemResource(subscriptionOid, SubscriptionItemType.CLUSTER_PIPELINE_RUNTIME,
                cluster.getId(), null);
        linkItemResource(subscriptionOid, SubscriptionItemType.ATLAS_DATABASE_STORAGE,
                null, allocation.getId());

        // Start provisioning
        clusterService.startProvisioning(cluster.getId(), "subscription:" + creatorEmail);

        subscription.setResourceState(ResourceState.PROVISIONING);
        subscription.setUpdatedAt(Instant.now());
        subscriptionRepository.save(subscription);

        log.info("[Subscription] finalizeProvisioning: subscriptionId={}, clusterId={}, allocationId={}",
                subscriptionOid.toHexString(), cluster.getId().toHexString(), allocation.getId().toHexString());

        return subscriptionRepository.findById(subscriptionOid).orElse(subscription);
    }

    // ========== Cancel — period-end + immediate ==========

    /**
     * Request cancel at period end — Stripe continues serving, subscription
     * stays ACTIVE until the next billing cycle, then automatically terminates.
     */
    @Transactional
    public Subscription cancelAtPeriodEnd(String subscriptionId, String orgId, String operator) {
        Subscription subscription = getByIdAndOrgId(subscriptionId, orgId);

        if (subscription.getStatus() == SubscriptionStatus.CANCELLED
                || subscription.getStatus() == SubscriptionStatus.PENDING_CANCEL) {
            return subscription;
        }
        if (subscription.getStatus() != SubscriptionStatus.ACTIVE
                && subscription.getStatus() != SubscriptionStatus.TRIAL) {
            throw new BusinessException(AppErrorCode.SUBSCRIPTION_CANNOT_CANCEL, 400,
                    "Cannot request cancel_at_period_end from status: " + subscription.getStatus());
        }

        // ① Stripe-side: set cancel_at_period_end=true
        paymentGateway.requestCancelAtPeriodEnd(subscription);

        // ② FSM: ACTIVE → PENDING_CANCEL
        var result = subscriptionStateMachineService.transition(
                subscription.getId(), SubscriptionEventType.CANCEL_AT_PERIOD_END_REQUESTED, operator);

        if (result.accepted()) {
            Subscription updated = subscriptionRepository.findById(subscription.getId()).orElse(subscription);
            if (updated.getStripe() != null) {
                updated.getStripe().setAutoRenewAuthorized(false);
                updated.getStripe().setAutoRenewAuthorizedChangedAt(Instant.now());
                subscriptionRepository.save(updated);
            }
            log.info("Subscription {} → PENDING_CANCEL for org={} by operator={}", subscriptionId, orgId, operator);
            return updated;
        }
        return subscription;
    }

    /**
     * Immediate cancellation — no grace period. Admin override or urgent user request.
     */
    @Transactional
    public Subscription cancelNow(String subscriptionId, String orgId, String operator) {
        Subscription subscription = getByIdAndOrgId(subscriptionId, orgId);
        return performCancel(subscription, SubscriptionEventType.IMMEDIATE_CANCEL, operator, true);
    }

    /**
     * Cancel because the payment channel reports the subscription is gone
     * (e.g. Stripe deleted it at the end of the billing period).
     * <p>
     * The channel call is skipped — the subscription no longer exists there, and
     * asking Stripe to cancel it again fails with {@code resource_missing}. The
     * local event is derived from the current status: a subscription already in
     * {@code PENDING_CANCEL} expired at its period end, anything else is an
     * out-of-band cancellation.
     */
    @Transactional
    public Subscription cancelDueToChannelDeletion(String subscriptionId, String operator) {
        Subscription subscription = subscriptionRepository.findById(new ObjectId(subscriptionId))
                .orElseThrow(() -> new BusinessException(
                        AppErrorCode.SUBSCRIPTION_NOT_FOUND, 404, subscriptionId));
        return performCancel(subscription, null, operator, false);
    }

    // ========== Read ==========

    public List<Subscription> listByOrgId(String orgId) {
        return subscriptionRepository.findByOrgIdOrderByCreatedAtDesc(new ObjectId(orgId));
    }

    public Subscription getByIdAndOrgId(String subscriptionId, String orgId) {
        return subscriptionRepository
            .findByIdAndOrgId(new ObjectId(subscriptionId), new ObjectId(orgId))
            .orElseThrow(() -> new BusinessException(AppErrorCode.SUBSCRIPTION_NOT_FOUND, 404, subscriptionId));
    }

    public List<SubscriptionItem> getItems(ObjectId subscriptionId) {
        return itemRepository.findBySubscriptionId(subscriptionId);
    }

    // ========== Private helpers ==========

    /**
     * @param event         state-machine event to fire; {@code null} means "derive from status"
     * @param notifyChannel false when the payment channel already removed the subscription,
     *                      so cancelling it there again would fail with {@code resource_missing}
     */
    private Subscription performCancel(Subscription subscription, SubscriptionEventType event,
                                       String operator, boolean notifyChannel) {
        if (subscription.getStatus() == SubscriptionStatus.CANCELLED) {
            return subscription;
        }

        if (notifyChannel) {
            paymentGateway.cancelImmediately(subscription);
        }

        SubscriptionEventType resolved = event != null ? event : defaultCancelEvent(subscription.getStatus());

        var result = subscriptionStateMachineService.transition(
                subscription.getId(), resolved, operator);

        if (result.accepted()) {
            Subscription updated = subscriptionRepository.findById(subscription.getId())
                .orElse(subscription);
            log.info("Subscription {} CANCELLED for org={} by operator={} (event={}, channelNotified={})",
                subscription.getId().toHexString(),
                subscription.getOrgId().toHexString(), operator, resolved, notifyChannel);

            deploymentStepRecorder.seed(subscription.getOrgId(), subscription.getId(),
                DeploymentOpType.UNINSTALL);

            eventPublisher.publishEvent(new SubscriptionCancelledEvent(
                subscription.getOrgId().toHexString(),
                subscription.getId().toHexString(), operator));

            return updated;
        } else {
            log.warn("Subscription {} cancel transition {} rejected (current status={})",
                subscription.getId(), resolved, subscription.getStatus());
            return subscription;
        }
    }

    /**
     * A subscription awaiting its period end expires via the reaper event; anything
     * else is an out-of-band cancellation with no remaining paid period.
     */
    private static SubscriptionEventType defaultCancelEvent(SubscriptionStatus status) {
        return status == SubscriptionStatus.PENDING_CANCEL
                ? SubscriptionEventType.PERIOD_END_REAPED
                : SubscriptionEventType.IMMEDIATE_CANCEL;
    }

    private void linkItemResource(ObjectId subscriptionId, SubscriptionItemType itemType,
                                  ObjectId clusterId, ObjectId mdbAllocationId) {
        itemRepository.findBySubscriptionIdAndItemType(subscriptionId, itemType)
            .ifPresentOrElse(
                item -> {
                    item.setClusterId(clusterId);
                    item.setMdbAllocationId(mdbAllocationId);
                    itemRepository.save(item);
                },
                () -> log.warn("[Subscription] {} item missing for subscription {} — resource link not recorded",
                    itemType, subscriptionId.toHexString()));
    }

    /** Result type returned by {@link #createCheckoutSession}. */
    public record CheckoutResult(Subscription subscription, String sessionUrl) {}
}
