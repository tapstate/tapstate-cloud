package io.tapstate.cloud.it;

import io.tapstate.cloud.sdk.ApiException;
import io.tapstate.cloud.sdk.model.ApiResponseSubscriptionResponse;
import io.tapstate.cloud.sdk.model.CreateSubscriptionRequest;
import io.tapstate.cloud.sdk.model.ItemResponse;
import io.tapstate.cloud.sdk.model.SubscriptionResponse;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * T0–T5: Subscription multi-subscription model + status state machine (SDK + real HTTP).
 * <p>
 * An Organization owns many subscriptions; every subscription owns exactly one
 * Cluster and one MdbAllocation. Creating an Organization creates no subscription.
 * <p>
 * State machine transitions (Spring Statemachine backend):
 * TRIAL → ACTIVE (payment confirmed)
 * ACTIVE → PAST_DUE (payment failed)
 * PAST_DUE → ACTIVE (payment received)
 * TRIAL/ACTIVE/PAST_DUE → CANCELLED (cancel requested)
 * Invalid transitions rejected: subscription record unchanged.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SubscriptionFlowIT extends BaseIT {

    private static final String REGION = "ap-southeast-1";
    private static final String SPEC = "basic";

    // ========== T0: Organization starts with no subscriptions ==========

    @Test
    @Order(0)
    void t0_newOrganizationHasNoSubscriptions() throws Exception {
        String orgId = createOrg("SubFlow T0");

        List<SubscriptionResponse> subscriptions =
            subscriptionApi.listSubscriptions(orgId).getData();
        assertNotNull(subscriptions, "list payload must not be null");
        assertTrue(subscriptions.isEmpty(),
            "a fresh Organization must own zero subscriptions, got: " + subscriptions.size());

        // GET of an unknown subscription id is a 404.
        ApiException notFound = assertThrows(ApiException.class,
            () -> subscriptionApi.getSubscription(orgId, "507f1f77bcf86cd799439011"));
        assertEquals(404, notFound.getCode());
    }

    // ========== T1: Subscribe creates a complete subscription ==========

    @Test
    @Order(1)
    void t1_subscribe_provisionsClusterAndMdb_returnsTrialSubscription() throws Exception {
        String orgId = createOrg("SubFlow T1");

        ApiResponseSubscriptionResponse httpResp = subscriptionApi.createSubscription(orgId,
            new CreateSubscriptionRequest()
                .region(REGION)
                .clusterName("sub-t1-" + uniqueSuffix())
                .specKey(SPEC));

        SubscriptionResponse sub = httpResp.getData();
        assertNotNull(sub.getId(), "Subscription ID should be generated");
        assertEquals(orgId, sub.getOrgId());
        assertEquals("TRIAL", sub.getStatus());
        assertEquals("PROVISIONING", sub.getResourceState(),
            "resourceState should be PROVISIONING right after create");
        assertNotNull(sub.getClusterId(), "subscription must carry its provisioned Cluster ID");
        assertNotNull(sub.getMdbAllocationId(), "subscription must carry its MdbAllocation ID");
        assertNotNull(sub.getCurrentPeriodStart());
        assertNotNull(sub.getCurrentPeriodEnd());
        assertTrue(Boolean.TRUE.equals(sub.isProvisioningAllowed()),
            "TRIAL subscriptions allow provisioning");

        // Read it back by its own id.
        SubscriptionResponse fetched = subscriptionApi.getSubscription(orgId, sub.getId()).getData();
        assertEquals(sub.getId(), fetched.getId());

        // The org now lists exactly one subscription.
        List<SubscriptionResponse> listed = subscriptionApi.listSubscriptions(orgId).getData();
        assertEquals(1, listed.size());
        assertEquals(sub.getId(), listed.get(0).getId());

        // Two billable items, each linked to this subscription's own resource.
        assertEquals(2, fetched.getItems().size(), "Should have 2 billable items: CLUSTER + ATLAS");
        ItemResponse runtimeItem = getItem(fetched, "CLUSTER_PIPELINE_RUNTIME");
        ItemResponse storageItem = getItem(fetched, "ATLAS_DATABASE_STORAGE");
        assertNotNull(runtimeItem, "CLUSTER_PIPELINE_RUNTIME item missing");
        assertNotNull(storageItem, "ATLAS_DATABASE_STORAGE item missing");
        assertEquals(0, runtimeItem.getIncludedQuantity(), "Trial runtime item should be quantity 0 (unlimited)");
        assertEquals(0, runtimeItem.getUnitPriceCents(), "Trial runtime item should be free");
        assertEquals("trial", runtimeItem.getMetadata().get("plan_tier"));
        assertEquals(sub.getClusterId(), runtimeItem.getClusterId(),
            "runtime item must bill this subscription's own cluster");
        assertNull(runtimeItem.getMdbAllocationId());
        assertEquals(sub.getMdbAllocationId(), storageItem.getMdbAllocationId(),
            "storage item must bill this subscription's own allocation");
        assertNull(storageItem.getClusterId());

        // Cluster must reach READY — the subscription's own cluster.
        String clusterId = sub.getClusterId();
        waitForClusterState(orgId, clusterId, "READY", Duration.ofMinutes(5));

        // After the cluster reaches READY, the subscription's MdbAllocation READY
        // joins and the subscription resource state converges to READY.
        await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofSeconds(2)).untilAsserted(() -> {
            SubscriptionResponse polled = subscriptionApi.getSubscription(orgId, sub.getId()).getData();
            assertEquals("READY", polled.getResourceState(),
                "Subscription resources should converge to READY once Cluster + MDB are ready");
            assertNotNull(polled.getResourcesReadyAt(), "resourcesReadyAt must be set on convergence");
        });

        // Trial period sanity — 30 days
        long days = Duration.between(
            fetched.getCurrentPeriodStart().toInstant(),
            fetched.getCurrentPeriodEnd().toInstant()).toDays();
        assertEquals(30, days, "Trial period should be 30 days");
        assertTrue(fetched.getCurrentPeriodEnd().toInstant().isAfter(Instant.now()),
            "Trial should end in the future");
    }

    // ========== T2: Input validation (400) + duplicate cluster name (409) ==========

    @Test
    @Order(2)
    void t2_subscribe_validationAndDuplicateRejection() throws Exception {
        String orgId = createOrg("SubFlow T2");
        String clusterName = "sub-t2-" + uniqueSuffix();

        // Missing region → 400
        ApiException missingRegion = assertThrows(ApiException.class, () ->
            subscriptionApi.createSubscription(orgId,
                new CreateSubscriptionRequest().clusterName(clusterName).specKey(SPEC)));
        assertEquals(400, missingRegion.getCode());

        // Missing clusterName → 400
        ApiException missingName = assertThrows(ApiException.class, () ->
            subscriptionApi.createSubscription(orgId,
                new CreateSubscriptionRequest().region(REGION).specKey(SPEC)));
        assertEquals(400, missingName.getCode());

        // First subscription succeeds.
        SubscriptionResponse first = subscriptionApi.createSubscription(orgId,
            new CreateSubscriptionRequest().region(REGION).clusterName(clusterName).specKey(SPEC)).getData();
        assertNotNull(first.getId());

        // Same cluster name in the same org + region → 409, even on a new subscription.
        ApiException dup = assertThrows(ApiException.class, () ->
            subscriptionApi.createSubscription(orgId,
                new CreateSubscriptionRequest().region(REGION).clusterName(clusterName).specKey(SPEC)));
        assertEquals(409, dup.getCode());
        assertTrue(dup.getResponseBody().contains("already exists"),
            "409 body should mention the duplicate cluster name, got: " + dup.getResponseBody());
    }

    // ========== T3: Two subscriptions stay independent ==========

    @Test
    @Order(3)
    void t3_secondSubscriptionIsIndependentFromTheFirst() throws Exception {
        String orgId = createOrg("SubFlow T3");

        SubscriptionResponse first = subscriptionApi.createSubscription(orgId,
            new CreateSubscriptionRequest()
                .region(REGION)
                .clusterName("sub-t3a-" + uniqueSuffix())
                .specKey(SPEC)).getData();
        SubscriptionResponse second = subscriptionApi.createSubscription(orgId,
            new CreateSubscriptionRequest()
                .region(REGION)
                .clusterName("sub-t3b-" + uniqueSuffix())
                .specKey(SPEC)).getData();

        assertNotEquals(first.getId(), second.getId(), "each create must produce its own subscription");
        assertNotEquals(first.getClusterId(), second.getClusterId());
        assertNotEquals(first.getMdbAllocationId(), second.getMdbAllocationId());

        // The org lists both, newest first.
        List<SubscriptionResponse> listed = subscriptionApi.listSubscriptions(orgId).getData();
        assertEquals(2, listed.size());
        assertEquals(second.getId(), listed.get(0).getId(), "list must be newest-first");
        assertEquals(first.getId(), listed.get(1).getId());

        // Each subscription's billable items link its own resources — the second
        // create must never overwrite the first subscription's item links.
        SubscriptionResponse firstRefetched = subscriptionApi.getSubscription(orgId, first.getId()).getData();
        assertEquals(first.getClusterId(), getItem(firstRefetched, "CLUSTER_PIPELINE_RUNTIME").getClusterId());
        assertEquals(first.getMdbAllocationId(), getItem(firstRefetched, "ATLAS_DATABASE_STORAGE").getMdbAllocationId());
        SubscriptionResponse secondRefetched = subscriptionApi.getSubscription(orgId, second.getId()).getData();
        assertEquals(second.getClusterId(), getItem(secondRefetched, "CLUSTER_PIPELINE_RUNTIME").getClusterId());
        assertEquals(second.getMdbAllocationId(), getItem(secondRefetched, "ATLAS_DATABASE_STORAGE").getMdbAllocationId());

        // Each subscription owns exactly one cluster record.
        assertEquals(1, clustersApi.listClusters(orgId).getData().stream()
            .filter(c -> first.getClusterId().equals(c.getId())).count());
        assertEquals(1, clustersApi.listClusters(orgId).getData().stream()
            .filter(c -> second.getClusterId().equals(c.getId())).count());

        // Both subscriptions converge independently to READY.
        waitForClusterState(orgId, first.getClusterId(), "READY", Duration.ofMinutes(5));
        waitForClusterState(orgId, second.getClusterId(), "READY", Duration.ofMinutes(5));
        await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofSeconds(2)).untilAsserted(() -> {
            assertEquals("READY",
                subscriptionApi.getSubscription(orgId, first.getId()).getData().getResourceState());
            assertEquals("READY",
                subscriptionApi.getSubscription(orgId, second.getId()).getData().getResourceState());
        });
    }

    // ========== T4: Payment state machine — valid transitions + invalid rejection ==========

    @Test
    @Order(4)
    void t4_paymentFlow_validTransitionsAndInvalidRejection() throws Exception {
        String orgId = createOrg("SubFlow T4");
        SubscriptionResponse sub = subscriptionApi.createSubscription(orgId,
            new CreateSubscriptionRequest()
                .region(REGION)
                .clusterName("sub-t4-" + uniqueSuffix())
                .specKey(SPEC)).getData();
        String subId = sub.getId();

        // TRIAL → ACTIVE (payment confirmed, trial conversion)
        transition(orgId, subId, "ACTIVE");
        assertEquals("ACTIVE", subscriptionApi.getSubscription(orgId, subId).getData().getStatus());

        // ACTIVE → PAST_DUE (payment failed)
        transition(orgId, subId, "PAST_DUE");
        assertEquals("PAST_DUE", subscriptionApi.getSubscription(orgId, subId).getData().getStatus());

        // PAST_DUE → ACTIVE (payment recovered) — valid
        transition(orgId, subId, "ACTIVE");
        assertEquals("ACTIVE", subscriptionApi.getSubscription(orgId, subId).getData().getStatus());

        // ACTIVE → PROVISIONING (invalid: PROVISIONING is a resource state, not a
        // subscription status) → must be rejected, status unchanged.
        ApiException invalid = assertThrows(ApiException.class,
            () -> transition(orgId, subId, "PROVISIONING"));
        assertEquals(400, invalid.getCode());
        assertEquals("ACTIVE", subscriptionApi.getSubscription(orgId, subId).getData().getStatus(),
            "Invalid transition must not change subscription status");
    }

    // ========== T5: Cancellation (TRIAL → CANCELLED) + idempotency ==========

    @Test
    @Order(5)
    void t5_cancel_trialToCancelled_idempotent() throws Exception {
        String orgId = createOrg("SubFlow T5");
        SubscriptionResponse sub = subscriptionApi.createSubscription(orgId,
            new CreateSubscriptionRequest()
                .region(REGION)
                .clusterName("sub-t5-" + uniqueSuffix())
                .specKey(SPEC)).getData();
        String subId = sub.getId();

        // TRIAL → CANCELLED
        SubscriptionResponse cancelled =
            subscriptionApi.cancelSubscription(orgId, subId).getData();
        assertEquals("CANCELLED", cancelled.getStatus());
        assertFalse(Boolean.TRUE.equals(cancelled.isProvisioningAllowed()),
            "Cancelled subscriptions block provisioning");

        // Verify persisted via GET by id
        SubscriptionResponse fetched = subscriptionApi.getSubscription(orgId, subId).getData();
        assertEquals("CANCELLED", fetched.getStatus());

        // Cancelled subscription still appears in the org's list with CANCELLED.
        boolean stillListed = subscriptionApi.listSubscriptions(orgId).getData().stream()
            .anyMatch(s -> subId.equals(s.getId()) && "CANCELLED".equals(s.getStatus()));
        assertTrue(stillListed, "Cancelled subscription must remain visible in the list");

        // Idempotent: calling cancel twice more must not error
        assertDoesNotThrow(() -> subscriptionApi.cancelSubscription(orgId, subId));
        assertEquals("CANCELLED", subscriptionApi.getSubscription(orgId, subId).getData().getStatus());
        assertDoesNotThrow(() -> subscriptionApi.cancelSubscription(orgId, subId));
        assertEquals("CANCELLED", subscriptionApi.getSubscription(orgId, subId).getData().getStatus());
    }

    // ========== Helpers ==========

    /** Register + login a fresh user and create its first Organization (no subscriptions yet). */
    private String createOrg(String name) throws Exception {
        String suffix = uniqueSuffix();
        signupAndLogin("subflow-" + suffix + "@tapstate.io", "StrongPass123!");
        return orgsApi.create(Map.of("name", name, "slug", "subflow-" + suffix))
            .getData().getId();
    }

    private static String uniqueSuffix() {
        return Long.toHexString(System.nanoTime());
    }

    private void waitForClusterState(String orgId, String clusterId, String targetState, Duration timeout) {
        await().atMost(timeout).pollInterval(Duration.ofSeconds(2)).untilAsserted(() -> {
            try {
                assertEquals(targetState,
                    clustersApi.getCluster(orgId, clusterId).getData().getState().getValue());
            } catch (ApiException e) {
                throw new AssertionError(e);
            }
        });
    }

    private void transition(String orgId, String subscriptionId, String targetStatus) throws ApiException {
        subscriptionApi.transitionStatus(orgId, subscriptionId,
            Map.of("status", targetStatus));
    }

    private ItemResponse getItem(SubscriptionResponse sub, String itemType) {
        return sub.getItems().stream()
            .filter(i -> itemType.equals(i.getItemType()))
            .findFirst().orElse(null);
    }
}
