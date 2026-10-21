package io.tapstate.cloud.it;
import io.tapstate.cloud.sdk.model.DeploymentProgressResponse;
import io.tapstate.cloud.sdk.model.SubscriptionResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * INSTALL deployment progress IT — all 14 operations recorded, aggregate
 * reaches SUCCEEDED / 100 and aligns with the subscription resource READY.
 */
@DisplayName("SubscriptionDeploymentProgressIT — install progress reaches 100%")
class SubscriptionDeploymentProgressIT extends BaseIT {

    private static final List<String> EXPECTED_OPERATIONS = List.of(
        "reserve_mdb_slot", "generate_ssh_key", "provision_ec2", "wait_ssh_ready",
        "restrict_ssh_ingress", "create_dns_record", "create_database",
        "create_database_user", "wait_mdb_allocation", "start_runtime_stack",
        "configure_nginx", "wait_dns_propagation", "issue_tls_certificate", "health_check");

    @Test
    @DisplayName("Install: 14 operations all SUCCEEDED, 100%, aligned with resourceState READY")
    void installProgressReachesOneHundred() throws Exception {
        String orgId = signupWithOrg("Install Progress");

        SubscriptionResponse sub = seedProvisionedSubscription(orgId, "2c8g", "ap-southeast-1",
            "install-progress-" + System.nanoTime());

        DeploymentProgressResponse progress = await()
            .atMost(Duration.ofMinutes(2))
            .pollInterval(Duration.ofMillis(500))
            .until(
                () -> progressApi.getProgress(orgId, sub.getId(), "INSTALL").getData(),
                p -> "SUCCEEDED".equals(p.getOverallStatus()));

        assertEquals(100, progress.getPercent());
        assertNotNull(progress.getStartedAt());
        assertNotNull(progress.getFinishedAt());
        assertEquals(14, progress.getOperations().size());

        List<String> codes = progress.getOperations().stream()
            .map(io.tapstate.cloud.sdk.model.DeploymentStepResponse::getOperation).toList();
        assertEquals(EXPECTED_OPERATIONS, codes, "operations must match the fixed catalog order");
        assertTrue(progress.getOperations().stream()
            .allMatch(op -> "SUCCEEDED".equals(op.getStatus())));
        assertTrue(progress.getOperations().stream()
            .allMatch(op -> op.getAttemptsCount() == 1));

        // Aggregate must align with the subscription resource readiness join.
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
            assertEquals("READY",
                subscriptionApi.getSubscription(orgId, sub.getId()).getData().getResourceState()));
    }
}
