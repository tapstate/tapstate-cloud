package io.tapstate.cloud.it;

import io.tapstate.cloud.sdk.ApiClient;
import io.tapstate.cloud.sdk.ApiException;
import io.tapstate.cloud.sdk.api.DeploymentProgressApi;
import io.tapstate.cloud.sdk.model.CreateSubscriptionRequest;
import io.tapstate.cloud.sdk.model.SubscriptionResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Progress endpoint security and validation IT — invalid opType is a 400 with
 * the typed error code, unknown subscription is 404, cross-org access is 403,
 * anonymous access is 401, and subscription responses carry both progress
 * summaries.
 */
@DisplayName("DeploymentProgressApiIT — endpoint validation, scoping and summaries")
class DeploymentProgressApiIT extends BaseIT {

    @Test
    @DisplayName("400 invalid opType / 404 unknown sub / 403 cross-org / 401 anonymous / summaries present")
    void endpointValidationAndScoping() throws Exception {
        String orgId = signupWithOrg("Progress API");

        SubscriptionResponse sub = seedProvisionedSubscription(orgId, "2c8g", "ap-southeast-1",
            "progress-api-" + System.nanoTime());

        // Wait for the initial deployment so summary assertions are stable.
        await().atMost(Duration.ofMinutes(2)).until(
            () -> progressApi.getProgress(orgId, sub.getId(), "INSTALL").getData(),
            p -> "SUCCEEDED".equals(p.getOverallStatus()));

        // Invalid opType → 400 with the typed error code.
        ApiException invalidOpType = assertThrows(ApiException.class,
            () -> progressApi.getProgress(orgId, sub.getId(), "FOO"));
        assertEquals(400, invalidOpType.getCode());
        assertTrue(invalidOpType.getResponseBody().contains("deployment.op-type-invalid"),
            "body should carry the typed code, got: " + invalidOpType.getResponseBody());

        // Unknown subscription → 404.
        ApiException notFound = assertThrows(ApiException.class,
            () -> progressApi.getProgress(orgId, "507f1f77bcf86cd799439011", "INSTALL"));
        assertEquals(404, notFound.getCode());

        // Anonymous → 401.
        ApiClient anonymousClient = new ApiClient();
        anonymousClient.setBasePath(BASE_URL);
        DeploymentProgressApi anonymousProgress = new DeploymentProgressApi(anonymousClient);
        ApiException unauthorized = assertThrows(ApiException.class,
            () -> anonymousProgress.getProgress(orgId, sub.getId(), "INSTALL"));
        assertEquals(401, unauthorized.getCode());

        // A second user (non-member of the owner Organization) must not read it.
        long suffix = System.nanoTime();
        signupAndLogin("crossorg-" + suffix + "@tapstate.io", "StrongPass123!");
        // Non-member querying the owner org scope → 403 (membership is checked
        // before subscription ownership).
        ApiException crossOrg = assertThrows(ApiException.class,
            () -> progressApi.getProgress(orgId, sub.getId(), "INSTALL"));
        assertEquals(403, crossOrg.getCode());
    }

    @Test
    @DisplayName("Subscription responses embed install + uninstall progress summaries")
    void subscriptionResponseCarriesSummaries() throws Exception {
        String orgId = signupWithOrg("Progress Summaries");

        SubscriptionResponse sub = seedProvisionedSubscription(orgId, "2c8g", "ap-southeast-1",
            "summaries-" + System.nanoTime());

        await().atMost(Duration.ofMinutes(2)).untilAsserted(() -> {
            SubscriptionResponse fetched =
                subscriptionApi.getSubscription(orgId, sub.getId()).getData();
            assertNotNull(fetched.getInstallProgress());
            assertEquals("SUCCEEDED", fetched.getInstallProgress().getOverallStatus());
            assertEquals(100, fetched.getInstallProgress().getPercent());
            assertNotNull(fetched.getUninstallProgress());
            assertEquals("PENDING", fetched.getUninstallProgress().getOverallStatus());
            assertEquals(0, fetched.getUninstallProgress().getPercent());
        });
    }
}
