package io.tapstate.cloud.it;

import com.fasterxml.jackson.databind.JsonNode;
import io.tapstate.cloud.sdk.ApiException;
import io.tapstate.cloud.sdk.model.FinalizeProvisioningRequest;
import io.tapstate.cloud.sdk.model.SubscriptionResponse;
import io.tapstate.cloud.it.support.ClusterVersionITSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IT for version selection at provisioning time:
 * <ul>
 *   <li>unspecified version pins the latest ACTIVE version onto the Cluster</li>
 *   <li>an explicit ACTIVE version is pinned verbatim</li>
 *   <li>a delisted or unknown version is rejected (409 / 404) before any
 *       resource is created</li>
 * </ul>
 * The pinned value is asserted both through the Cluster API and directly in the
 * persisted document; it is the sole input to the runtime image tag.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SubscriptionVersionIT extends BaseIT {

    private static final String REGION = "ap-southeast-1";
    private static final String SPEC = "2c8g";

    private ClusterVersionITSupport versions;
    private String orgId;

    @BeforeEach
    void setUpContext() throws Exception {
        versions = new ClusterVersionITSupport(httpClient, BASE_URL);
        versions.ensureBootstrapActive();
        orgId = signupWithOrg("Subscription Version IT");
    }

    @Test
    @Order(1)
    void unspecifiedVersionPinsLatestActive() throws Exception {
        // Read "latest" exactly as the server defines it: first row of the
        // descending ACTIVE list, captured before provisioning.
        JsonNode active = versions.listActive("TAPSTATE_CLOUD_SESSION=" + sessionCookieValue);
        assertTrue(active.isArray() && active.size() > 0, "at least one ACTIVE version must exist");
        String latest = active.get(0).path("version").asText();

        SubscriptionResponse sub = seedProvisionedSubscription(orgId, SPEC, REGION,
            "ver-default-" + Long.toHexString(System.nanoTime()));

        waitForReady(orgId, sub.getClusterId());

        assertEquals(latest,
            clustersApi.getCluster(orgId, sub.getClusterId()).getData().getClusterVersion(),
            "Cluster API must expose the pinned latest version");
        assertDocVersion(sub.getClusterId(), latest);
    }

    @Test
    @Order(2)
    void explicitActiveVersionIsPinnedVerbatim() throws Exception {
        String version = ClusterVersionITSupport.uniqueVersion("3.1.0");
        versions.releaseStable(version);

        SubscriptionResponse sub = seedProvisionedSubscription(orgId, SPEC, REGION,
            "ver-explicit-" + Long.toHexString(System.nanoTime()), version);

        waitForReady(orgId, sub.getClusterId());

        assertEquals(version,
            clustersApi.getCluster(orgId, sub.getClusterId()).getData().getClusterVersion(),
            "the requested version must be pinned verbatim");
        assertDocVersion(sub.getClusterId(), version);
    }

    @Test
    @Order(3)
    void delistedExplicitVersionIsRejected409() throws Exception {
        String version = ClusterVersionITSupport.uniqueVersion("3.2.0-rc.2");
        versions.releaseStable(version);
        versions.setState(version, "OFFLINE");

        try {
            String subscriptionId = seedSubscription(orgId, SPEC);
            ApiException failure = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> subscriptionApi.finalizeProvisioning(orgId, subscriptionId,
                    new FinalizeProvisioningRequest()
                        .region(REGION)
                        .clusterName("ver-offline-" + Long.toHexString(System.nanoTime()))
                        .version(version)));
            assertEquals(409, failure.getCode());
            assertTrue(failure.getResponseBody().contains("cluster.version-not-active"),
                failure.getResponseBody());

            // Rejected before resource creation: the org still owns no clusters.
            assertTrue(clustersApi.listClusters(orgId).getData().isEmpty(),
                "a rejected finalize must not create a Cluster");
        } finally {
            versions.setState(version, "ACTIVE");
        }
    }

    @Test
    @Order(4)
    void unknownExplicitVersionIsRejected404() throws Exception {
        String subscriptionId = seedSubscription(orgId, SPEC);
        ApiException failure = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
            () -> subscriptionApi.finalizeProvisioning(orgId, subscriptionId,
                new FinalizeProvisioningRequest()
                    .region(REGION)
                    .clusterName("ver-unknown-" + Long.toHexString(System.nanoTime()))
                    .version("9.9.9-it" + System.nanoTime())));
        assertEquals(404, failure.getCode());
        assertTrue(failure.getResponseBody().contains("cluster.version-not-found"),
            failure.getResponseBody());
    }

    @Test
    @Order(5)
    void emptyCatalogRejectsDefaultProvisioning503AndBaselineIsRestored() throws Exception {
        // Destructive precondition: remove every catalog row but keep the
        // counters collection so sequence numbers are not reused on recovery.
        mongoshEval("print('DELETED=' + db.cluster_versions.deleteMany({}).deletedCount)");
        try {
            String subscriptionId = seedSubscription(orgId, SPEC);
            ApiException failure = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> subscriptionApi.finalizeProvisioning(orgId, subscriptionId,
                    new FinalizeProvisioningRequest()
                        .region(REGION)
                        .clusterName("ver-empty-" + Long.toHexString(System.nanoTime()))));
            assertEquals(503, failure.getCode());
            assertTrue(failure.getResponseBody().contains("cluster.version-unavailable"),
                failure.getResponseBody());

            // Resolution happens before any resource is created.
            assertTrue(clustersApi.listClusters(orgId).getData().isEmpty(),
                "an unresolvable default version must not create a Cluster");
        } finally {
            // Re-publish the bootstrap baseline for later tests in the same JVM.
            var restored = versions.postRelease(ClusterVersionITSupport.BOOTSTRAP_VERSION,
                "STABLE", ClusterVersionITSupport.RELEASE_TOKEN);
            assertEquals(201, restored.statusCode(),
                "bootstrap baseline must be restored, body=" + restored.body());
            versions.setState(ClusterVersionITSupport.BOOTSTRAP_VERSION, "ACTIVE");
        }
    }

    /** Assert the pinned version persisted on the Cluster document itself. */
    private void assertDocVersion(String clusterId, String expectedVersion) throws Exception {
        String out = mongoshEval(
            "print('DOCVERSION=' + db.clusters.findOne({_id: ObjectId('" + clusterId
                + "')}, {clusterVersion: 1}).clusterVersion)");
        assertTrue(out.contains("DOCVERSION=" + expectedVersion),
            "persisted cluster document must pin " + expectedVersion + ", mongosh output:\n" + out);
    }
}
