package io.tapstate.cloud.it;

import io.tapstate.cloud.sdk.ApiException;
import io.tapstate.cloud.sdk.model.ItemResponse;
import io.tapstate.cloud.sdk.model.SubscriptionResponse;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Subscription read model + multi-subscription ownership (SDK + real HTTP).
 * <p>
 * An Organization owns many subscriptions and every subscription owns exactly
 * one Cluster and one MdbAllocation; creating an Organization creates none. The
 * paid creation path is covered by the subscription cases and the Stripe
 * webhook tests, so this class starts from a provisioned subscription and
 * asserts what the API reports about it.
 * <p>
 * There is no endpoint that moves a subscription between payment states — those
 * transitions are webhook-driven — so nothing here forges one.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SubscriptionFlowIT extends BaseIT {

    private static final String REGION = "ap-southeast-1";
    private static final String SPEC = "2c8g";

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

    // ========== T1: a subscription carries its own resources and items ==========

    @Test
    @Order(1)
    void t1_subscriptionCarriesItsOwnClusterItemsAndPeriod() throws Exception {
        String orgId = createOrg("SubFlow T1");

        SubscriptionResponse sub = seedProvisionedSubscription(orgId, SPEC, REGION,
            "sub-t1-" + uniqueSuffix());

        assertNotNull(sub.getId(), "Subscription ID should be generated");
        assertEquals(orgId, sub.getOrgId());
        assertEquals("TRIAL", sub.getStatus());
        assertEquals("PROVISIONING", sub.getResourceState(),
            "resourceState should be PROVISIONING right after finalize");
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
        assertEquals(sub.getClusterId(), runtimeItem.getClusterId(),
            "runtime item must bill this subscription's own cluster");
        assertNull(runtimeItem.getMdbAllocationId());
        assertEquals(sub.getMdbAllocationId(), storageItem.getMdbAllocationId(),
            "storage item must bill this subscription's own allocation");
        assertNull(storageItem.getClusterId());

        // Cluster must reach READY — the subscription's own cluster.
        waitForClusterState(orgId, sub.getClusterId(), "READY", Duration.ofMinutes(5));

        // After the cluster reaches READY, the subscription's MdbAllocation READY
        // joins and the subscription resource state converges to READY.
        await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofSeconds(2)).untilAsserted(() -> {
            SubscriptionResponse polled = subscriptionApi.getSubscription(orgId, sub.getId()).getData();
            assertEquals("READY", polled.getResourceState(),
                "Subscription resources should converge to READY once Cluster + MDB are ready");
            assertNotNull(polled.getResourcesReadyAt(), "resourcesReadyAt must be set on convergence");
        });
    }

    // ========== T2: Two subscriptions stay independent ==========

    @Test
    @Order(2)
    void t2_secondSubscriptionIsIndependentFromTheFirst() throws Exception {
        String orgId = createOrg("SubFlow T2");

        SubscriptionResponse first = seedProvisionedSubscription(orgId, SPEC, REGION,
            "sub-t2a-" + uniqueSuffix());
        SubscriptionResponse second = seedProvisionedSubscription(orgId, SPEC, REGION,
            "sub-t2b-" + uniqueSuffix());

        assertNotEquals(first.getId(), second.getId(), "each subscription is its own document");
        assertNotEquals(first.getClusterId(), second.getClusterId());
        assertNotEquals(first.getMdbAllocationId(), second.getMdbAllocationId());

        // The org lists both, newest first.
        List<SubscriptionResponse> listed = subscriptionApi.listSubscriptions(orgId).getData();
        assertEquals(2, listed.size());
        assertEquals(second.getId(), listed.get(0).getId(), "list must be newest-first");
        assertEquals(first.getId(), listed.get(1).getId());

        // Each subscription's billable items link its own resources — finalizing the
        // second must never overwrite the first subscription's item links.
        SubscriptionResponse firstRefetched = subscriptionApi.getSubscription(orgId, first.getId()).getData();
        assertEquals(first.getClusterId(), getItem(firstRefetched, "CLUSTER_PIPELINE_RUNTIME").getClusterId());
        assertEquals(first.getMdbAllocationId(), getItem(firstRefetched, "ATLAS_DATABASE_STORAGE").getMdbAllocationId());
        SubscriptionResponse secondRefetched = subscriptionApi.getSubscription(orgId, second.getId()).getData();
        assertEquals(second.getClusterId(), getItem(secondRefetched, "CLUSTER_PIPELINE_RUNTIME").getClusterId());
        assertEquals(second.getMdbAllocationId(), getItem(secondRefetched, "ATLAS_DATABASE_STORAGE").getMdbAllocationId());

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

    // ========== T3: Cancellation blocks provisioning, stays visible, is idempotent ==========

    @Test
    @Order(3)
    void t3_cancel_blocksProvisioningAndIsIdempotent() throws Exception {
        String orgId = createOrg("SubFlow T3");
        SubscriptionResponse sub = seedProvisionedSubscription(orgId, SPEC, REGION,
            "sub-t3-" + uniqueSuffix());
        String subId = sub.getId();

        SubscriptionResponse cancelled = subscriptionApi.cancelNow(orgId, subId).getData();
        assertEquals("CANCELLED", cancelled.getStatus());
        assertFalse(Boolean.TRUE.equals(cancelled.isProvisioningAllowed()),
            "Cancelled subscriptions block provisioning");

        // Verify persisted via GET by id.
        SubscriptionResponse fetched = subscriptionApi.getSubscription(orgId, subId).getData();
        assertEquals("CANCELLED", fetched.getStatus());

        // Cancelled subscription still appears in the org's list with CANCELLED.
        boolean stillListed = subscriptionApi.listSubscriptions(orgId).getData().stream()
            .anyMatch(s -> subId.equals(s.getId()) && "CANCELLED".equals(s.getStatus()));
        assertTrue(stillListed, "Cancelled subscription must remain visible in the list");

        // Idempotent: cancelling twice more must not error.
        assertDoesNotThrow(() -> subscriptionApi.cancelNow(orgId, subId));
        assertEquals("CANCELLED", subscriptionApi.getSubscription(orgId, subId).getData().getStatus());
        assertDoesNotThrow(() -> subscriptionApi.cancelNow(orgId, subId));
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

    private ItemResponse getItem(SubscriptionResponse sub, String itemType) {
        return sub.getItems().stream()
            .filter(i -> itemType.equals(i.getItemType()))
            .findFirst().orElse(null);
    }
}
