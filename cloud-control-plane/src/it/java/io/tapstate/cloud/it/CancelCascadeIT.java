package io.tapstate.cloud.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tapstate.cloud.sdk.api.SubscriptionApi;
import io.tapstate.cloud.sdk.model.ClusterResponse.StateEnum;
import io.tapstate.cloud.sdk.model.CreateSubscriptionRequest;
import io.tapstate.cloud.sdk.model.SubscriptionResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Subscription cancel cascade IT — Cluster termination + MDB reclaim.
 * <p>
 * Cluster creation via subscriptionApi.createSubscription (SDK).
 * Cancel via subscriptionApi.cancelSubscription (SDK).
 * Cluster listing via raw HTTP (SDK return-type bug for list).
 */
@DisplayName("CancelCascadeIT — subscription cancel cascades Cluster + MDB")
class CancelCascadeIT extends BaseIT {

    private final ObjectMapper mapper = new ObjectMapper();
    private String orgId;

    @BeforeEach
    void setUpOrg() throws Exception {
        signupAndLogin("cancel-" + System.nanoTime() + "@tapstate.io", "StrongPass123!");
        orgId = orgsApi.create(Map.of(
            "name", "Cancel-Test",
            "slug", "cancel-" + System.nanoTime()
        )).getData().getId();
    }

    private String createWithCluster(String clusterName) throws Exception {
        subscriptionApi.createSubscription(orgId,
            new CreateSubscriptionRequest().region("ap-southeast-1").clusterName(clusterName).specKey("basic"));
        // Poll until visible
        for (int i = 0; i < 10; i++) {
            HttpResponse<String> resp = get("/v1/api/orgs/" + orgId + "/clusters",
                Map.of("Cookie", "TAPSTATE_CLOUD_SESSION=" + sessionCookieValue));
            JsonNode clusters = mapper.readTree(resp.body()).path("data");
            if (clusters.isArray()) {
                for (JsonNode c : clusters) {
                    if (clusterName.equals(c.path("name").asText())) return c.path("id").asText();
                }
            }
            Thread.sleep(500);
        }
        throw new AssertionError("Cluster " + clusterName + " not created");
    }

    @Test
    @DisplayName("T1 — Cancel subscription → 1 cluster TERMINATED")
    void cancelSubscription_cascadesOneCluster() throws Exception {
        String clusterId = createWithCluster("Single");
        waitForReady(orgId, clusterId);

        subscriptionApi.cancelSubscription(orgId);

        boolean ok = false;
        for (int i = 0; i < 20; i++) {
            if (clustersApi.getCluster(orgId, clusterId).getData().getState() == StateEnum.TERMINATED) { ok = true; break; }
            Thread.sleep(1000);
        }
        assertTrue(ok, "Cluster should TERMINATE");
        assertEquals("CANCELLED", subscriptionApi.getSubscription(orgId).getData().getStatus());
    }

    @Test
    @DisplayName("T2 — Cancel subscription → 2 clusters both TERMINATED")
    void cancelSubscription_cascadesMultipleClusters() throws Exception {
        String c1 = createWithCluster("Multi-A");
        String c2 = createWithCluster("Multi-B");
        waitForReady(orgId, c1);
        waitForReady(orgId, c2);

        subscriptionApi.cancelSubscription(orgId);

        for (int i = 0; i < 20; i++) {
            if (clustersApi.getCluster(orgId, c1).getData().getState() == StateEnum.TERMINATED
                && clustersApi.getCluster(orgId, c2).getData().getState() == StateEnum.TERMINATED) break;
            Thread.sleep(1000);
        }
        assertEquals(StateEnum.TERMINATED, clustersApi.getCluster(orgId, c1).getData().getState());
        assertEquals(StateEnum.TERMINATED, clustersApi.getCluster(orgId, c2).getData().getState());
    }

    @Test
    @DisplayName("T3 — Cancel already CANCELLED → idempotent")
    void cancelAlreadyCancelled_isIdempotent() throws Exception {
        subscriptionApi.cancelSubscription(orgId);
        SubscriptionResponse first = subscriptionApi.getSubscription(orgId).getData();
        assertEquals("CANCELLED", first.getStatus());

        subscriptionApi.cancelSubscription(orgId);
        SubscriptionResponse second = subscriptionApi.getSubscription(orgId).getData();
        assertEquals("CANCELLED", second.getStatus());
    }

    @Test
    @DisplayName("T4 — Cancel org with no clusters → safe no-op")
    void cancelSubscription_noClusters_isSafe() {
        assertDoesNotThrow(() -> subscriptionApi.cancelSubscription(orgId));
    }
}
