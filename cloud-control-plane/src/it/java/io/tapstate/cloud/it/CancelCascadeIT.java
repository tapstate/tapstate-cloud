package io.tapstate.cloud.it;

import io.tapstate.cloud.sdk.model.CreateSubscriptionRequest;
import io.tapstate.cloud.sdk.model.SubscriptionResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;

/**
 * Subscription cancel cascade IT — Cluster termination + MDB reclaim, scoped
 * strictly to the cancelled subscription.
 * <p>
 * An Organization owns many subscriptions; cancelling one subscription must
 * terminate that subscription's own Cluster (and reclaim its own MdbAllocation)
 * while sibling subscriptions of the same Organization keep running.
 */
@DisplayName("CancelCascadeIT — subscription cancel cascades only its own Cluster + MDB")
class CancelCascadeIT extends BaseIT {

    private String orgId;

    @BeforeEach
    void setUpOrg() throws Exception {
        signupAndLogin("cancel-" + System.nanoTime() + "@tapstate.io", "StrongPass123!");
        orgId = orgsApi.create(organizationRequest("Cancel-Test")).getData().getId();
    }

    /** Create a subscription (its Cluster id comes back on the response). */
    private SubscriptionResponse createWithCluster(String clusterName) throws Exception {
        return seedProvisionedSubscription(orgId, "2c8g", "ap-southeast-1", clusterName);
    }

    private void waitForCluster(String clusterId, String targetState) {
        await().atMost(Duration.ofMinutes(5)).pollInterval(Duration.ofSeconds(2)).untilAsserted(() ->
            assertEquals(targetState,
                clustersApi.getCluster(orgId, clusterId).getData().getState().getValue()));
    }

    @Test
    @DisplayName("T1 — Cancel subscription → its Cluster TERMINATED")
    void cancelSubscription_cascadesOneCluster() throws Exception {
        SubscriptionResponse sub = createWithCluster("Single");
        String clusterId = sub.getClusterId();
        waitForCluster(clusterId, "READY");

        subscriptionApi.cancelNow(orgId, sub.getId());

        waitForCluster(clusterId, "TERMINATED");
        assertEquals("CANCELLED",
            subscriptionApi.getSubscription(orgId, sub.getId()).getData().getStatus());
    }

    @Test
    @DisplayName("T2 — Cancel one subscription → sibling subscription's Cluster stays READY")
    void cancelOneSubscription_doesNotTouchSiblingSubscription() throws Exception {
        SubscriptionResponse first = createWithCluster("Multi-A");
        SubscriptionResponse second = createWithCluster("Multi-B");
        waitForCluster(first.getClusterId(), "READY");
        waitForCluster(second.getClusterId(), "READY");

        // Cancel only the FIRST subscription.
        subscriptionApi.cancelNow(orgId, first.getId());

        // The first subscription's cluster terminates.
        waitForCluster(first.getClusterId(), "TERMINATED");

        // The sibling subscription is untouched: still TRIAL, its Cluster READY.
        // Give the async cascade a generous window in which it must NOT fire.
        Thread.sleep(10_000);
        assertEquals("READY",
            clustersApi.getCluster(orgId, second.getClusterId()).getData().getState().getValue(),
            "sibling subscription's Cluster must stay READY");
        SubscriptionResponse sibling = subscriptionApi.getSubscription(orgId, second.getId()).getData();
        assertEquals("TRIAL", sibling.getStatus(), "sibling subscription must not be cancelled");

        // The org still lists both subscriptions, first CANCELLED, second not.
        var listed = subscriptionApi.listSubscriptions(orgId).getData();
        assertEquals(2, listed.size());
        assertNotNull(listed.stream()
            .filter(s -> s.getId().equals(first.getId()) && "CANCELLED".equals(s.getStatus()))
            .findFirst().orElse(null));
        assertNotNull(listed.stream()
            .filter(s -> s.getId().equals(second.getId()) && "TRIAL".equals(s.getStatus()))
            .findFirst().orElse(null));

        // Cancelling the sibling now terminates its own cluster too.
        subscriptionApi.cancelNow(orgId, second.getId());
        waitForCluster(second.getClusterId(), "TERMINATED");
    }

    @Test
    @DisplayName("T3 — Cancel already CANCELLED → idempotent")
    void cancelAlreadyCancelled_isIdempotent() throws Exception {
        SubscriptionResponse sub = createWithCluster("Idem");
        subscriptionApi.cancelNow(orgId, sub.getId());
        SubscriptionResponse first = subscriptionApi.getSubscription(orgId, sub.getId()).getData();
        assertEquals("CANCELLED", first.getStatus());

        subscriptionApi.cancelNow(orgId, sub.getId());
        SubscriptionResponse second = subscriptionApi.getSubscription(orgId, sub.getId()).getData();
        assertEquals("CANCELLED", second.getStatus());
    }

    @Test
    @DisplayName("T4 — Unknown subscription id → 404")
    void cancelUnknownSubscription_is404() {
        var ex = assertThrows(io.tapstate.cloud.sdk.ApiException.class,
            () -> subscriptionApi.cancelNow(orgId, "507f1f77bcf86cd799439011"));
        assertEquals(404, ex.getCode());
    }
}
