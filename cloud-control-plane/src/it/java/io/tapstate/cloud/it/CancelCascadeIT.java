package io.tapstate.cloud.it;

import io.tapstate.cloud.sdk.ApiException;
import io.tapstate.cloud.sdk.api.ClustersApi;
import io.tapstate.cloud.sdk.api.OrganizationsApi;
import io.tapstate.cloud.sdk.api.SubscriptionApi;
import io.tapstate.cloud.sdk.model.ClusterResponse;
import io.tapstate.cloud.sdk.model.ClusterResponse.StateEnum;
import io.tapstate.cloud.sdk.model.CreateClusterRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cancel / termination flows — direct cluster cancel + subscription cancel cascade.
 * <p>
 * Covers (Week 2 Gap Closure #3 — cancel endpoint):
 * <ul>
 *   <li>Direct cluster cancel (READY → TERMINATED)</li>
 *   <li>Cancel subscription → async cascade → ALL org clusters reach TERMINATED</li>
 *   <li>Cancel cluster already TERMINATED → idempotent 200</li>
 * </ul>
 */
@DisplayName("CancelCascadeIT — cluster cancel + subscription→cluster cascade")
class CancelCascadeIT extends BaseIT {

    /** Create + READY cluster, return [orgId, clusterId]. */
    private String[] createProvisionedCluster(String name) throws Exception {
        signupAndLogin(name.toLowerCase() + "@tapstate.io", "StrongPass123!");

        String orgId = orgsApi.create(Map.of(
            "name", "Cancel-" + name,
            "slug", "cancel-" + name.toLowerCase())).getData().getId();

        String clusterId = clustersApi.createCluster(orgId,
            new CreateClusterRequest().region("ap-southeast-1").name(name)).getData().getId();

        waitForReady(orgId, clusterId);
        return new String[]{orgId, clusterId};
    }

    @Test
    @DisplayName("T1 — Direct cancel cluster → TERMINATED")
    void cancelCluster_ready_becomesTerminated() throws Exception {
        String[] t = createProvisionedCluster("Direct");
        String orgId = t[0], clusterId = t[1];

        ClusterResponse canceled = clustersApi.cancelCluster(orgId, clusterId).getData();
        assertEquals(StateEnum.TERMINATED, canceled.getState());
    }

    @Test
    @DisplayName("T2 — Cancel subscription → 2 clusters cascade to TERMINATED")
    void cancelSubscription_cascadesAllClusters() throws Exception {
        signupAndLogin("cascade@tapstate.io", "StrongPass123!");

        String orgId = orgsApi.create(Map.of(
            "name", "Cascade",
            "slug", "cascade")).getData().getId();

        String c1 = clustersApi.createCluster(orgId,
            new CreateClusterRequest().region("ap-southeast-1").name("Cascade-A")).getData().getId();
        String c2 = clustersApi.createCluster(orgId,
            new CreateClusterRequest().region("ap-southeast-1").name("Cascade-B")).getData().getId();

        waitForReady(orgId, c1);
        waitForReady(orgId, c2);

        // Cancel subscription → async cascade
        subscriptionApi.cancelSubscription(orgId);
        Thread.sleep(5000);  // @EventListener @Async needs a tick

        assertEquals(StateEnum.TERMINATED, clustersApi.getCluster(orgId, c1).getData().getState(),
            "Cluster A not terminated");
        assertEquals(StateEnum.TERMINATED, clustersApi.getCluster(orgId, c2).getData().getState(),
            "Cluster B not terminated");
    }

    @Test
    @DisplayName("T3 — Cancel already TERMINATED → idempotent success")
    void cancelTerminated_isIdempotent() throws Exception {
        String[] t = createProvisionedCluster("Idem");
        String orgId = t[0], clusterId = t[1];

        clustersApi.cancelCluster(orgId, clusterId);

        // Second cancel — should still return TERMINATED
        ClusterResponse r2 = clustersApi.cancelCluster(orgId, clusterId).getData();
        assertEquals(StateEnum.TERMINATED, r2.getState());
    }
}
