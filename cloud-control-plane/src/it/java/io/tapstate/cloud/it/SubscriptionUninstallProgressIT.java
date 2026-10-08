package io.tapstate.cloud.it;

import io.tapstate.cloud.sdk.model.CreateSubscriptionRequest;
import io.tapstate.cloud.sdk.model.DeploymentProgressResponse;
import io.tapstate.cloud.sdk.model.SubscriptionResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * UNINSTALL deployment progress IT — after cancellation all 4 release
 * operations are recorded and the aggregate reaches SUCCEEDED / 100.
 */
@DisplayName("SubscriptionUninstallProgressIT — uninstall progress reaches 100%")
class SubscriptionUninstallProgressIT extends BaseIT {

    private static final List<String> EXPECTED_OPERATIONS = List.of(
        "terminate_ec2", "delete_dns_record", "drop_database", "drop_database_user");

    @Test
    @DisplayName("Uninstall: 4 operations all SUCCEEDED, 100%, Cluster TERMINATED")
    void uninstallProgressReachesOneHundred() throws Exception {
        String orgId = signupWithOrg("Uninstall Progress");

        SubscriptionResponse sub = subscriptionApi.createSubscription(orgId,
            new CreateSubscriptionRequest()
                .region("ap-southeast-1")
                .clusterName("uninstall-progress-" + System.nanoTime())
                .specKey("basic")).getData();
        String clusterId = sub.getClusterId();

        // Only cancel after the initial deployment completed.
        await().atMost(Duration.ofMinutes(2)).until(
            () -> progressApi.getProgress(orgId, sub.getId(), "INSTALL").getData(),
            p -> "SUCCEEDED".equals(p.getOverallStatus()));

        subscriptionApi.cancelSubscription(orgId, sub.getId());

        DeploymentProgressResponse progress = await()
            .atMost(Duration.ofMinutes(2))
            .pollInterval(Duration.ofMillis(500))
            .until(
                () -> progressApi.getProgress(orgId, sub.getId(), "UNINSTALL").getData(),
                p -> "SUCCEEDED".equals(p.getOverallStatus()));

        assertEquals(100, progress.getPercent());
        assertNotNull(progress.getFinishedAt());
        List<String> codes = progress.getOperations().stream()
            .map(io.tapstate.cloud.sdk.model.DeploymentStepResponse::getOperation).toList();
        assertEquals(EXPECTED_OPERATIONS, codes);
        assertTrue(progress.getOperations().stream()
            .allMatch(op -> "SUCCEEDED".equals(op.getStatus())));

        // The release aligns with the Cluster TERMINATED state.
        await().atMost(Duration.ofMinutes(2)).untilAsserted(() ->
            assertEquals("TERMINATED",
                clustersApi.getCluster(orgId, clusterId).getData().getState().getValue()));
    }
}
