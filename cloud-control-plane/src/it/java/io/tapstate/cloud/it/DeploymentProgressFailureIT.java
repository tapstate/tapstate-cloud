package io.tapstate.cloud.it;

import io.tapstate.cloud.sdk.model.CreateSubscriptionRequest;
import io.tapstate.cloud.sdk.model.DeploymentProgressResponse;
import io.tapstate.cloud.sdk.model.DeploymentStepResponse;
import io.tapstate.cloud.sdk.model.SubscriptionResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Failure + retry IT — a one-shot mock failure fixture makes the Cluster
 * provisioning fail at a fixed operation. The aggregate becomes FAILED with
 * the percentage frozen and the failure detail exposed; the Cluster retry
 * endpoint archives the attempt, reruns the operations and reaches 100 with
 * the attempt count incremented.
 */
@DisplayName("DeploymentProgressFailureIT — failure freezes progress, retry reaches 100")
class DeploymentProgressFailureIT extends BaseIT {

    @Test
    @DisplayName("Failure at provision_ec2 → FAILED/5%; retry → SUCCEEDED/100%, attemptsCount=2")
    void failureFreezesProgressThenRetrySucceeds() throws Exception {
        String orgId = signupWithOrg("Failure Progress");

        // Seed the one-shot failure fixture BEFORE the subscription is created —
        // the mock worker consumes it when it starts.
        mongoshEval("db.mock_provision_failures.insertOne({"
            + "orgId: ObjectId('" + orgId + "'),"
            + "failOperation: 'provision_ec2',"
            + "consumed: false,"
            + "createdAt: new Date()})");

        SubscriptionResponse sub = subscriptionApi.createSubscription(orgId,
            new CreateSubscriptionRequest()
                .region("ap-southeast-1")
                .clusterName("failure-progress-" + System.nanoTime())
                .specKey("basic")).getData();
        String clusterId = sub.getClusterId();

        DeploymentProgressResponse failedProgress = await()
            .atMost(Duration.ofMinutes(2))
            .pollInterval(Duration.ofMillis(500))
            .until(
                () -> progressApi.getProgress(orgId, sub.getId(), "INSTALL").getData(),
                p -> "FAILED".equals(p.getOverallStatus()));

        // Frozen percentage: only SUCCEEDED operations count. The Atlas worker
        // runs in parallel and completes before the Cluster worker reaches the
        // injected failure, so reserve_mdb_slot (3) + generate_ssh_key (2) +
        // create_database (7) + create_database_user (10) = 22.
        assertEquals(22, failedProgress.getPercent());

        // The percentage stays frozen rather than accumulating further.
        Thread.sleep(1000);
        DeploymentProgressResponse refetched =
            progressApi.getProgress(orgId, sub.getId(), "INSTALL").getData();
        assertEquals("FAILED", refetched.getOverallStatus());
        assertEquals(22, refetched.getPercent());
        assertNotNull(failedProgress.getMessage());
        assertTrue(failedProgress.getMessage().contains("provision_ec2"));
        DeploymentStepResponse failedStep = failedProgress.getOperations().stream()
            .filter(op -> "provision_ec2".equals(op.getOperation())).findFirst().orElseThrow();
        assertEquals("FAILED", failedStep.getStatus());
        assertEquals(1, failedStep.getAttemptsCount());
        assertNotNull(failedStep.getMessage());

        // The Cluster reflects the failed provisioning.
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
            assertEquals("PROVISIONING_FAILED",
                clustersApi.getCluster(orgId, clusterId).getData().getState().getValue()));

        // Retry — the failed attempt is archived and every operation runs again.
        clustersApi.retryCluster(orgId, clusterId);

        DeploymentProgressResponse retriedProgress = await()
            .atMost(Duration.ofMinutes(2))
            .pollInterval(Duration.ofMillis(500))
            .until(
                () -> progressApi.getProgress(orgId, sub.getId(), "INSTALL").getData(),
                p -> "SUCCEEDED".equals(p.getOverallStatus()));

        assertEquals(100, retriedProgress.getPercent());
        DeploymentStepResponse retriedStep = retriedProgress.getOperations().stream()
            .filter(op -> "provision_ec2".equals(op.getOperation())).findFirst().orElseThrow();
        assertEquals("SUCCEEDED", retriedStep.getStatus());
        assertEquals(2, retriedStep.getAttemptsCount(),
            "the archived failed attempt plus the successful retry");
    }
}
