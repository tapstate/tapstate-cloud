package io.tapstate.cloud.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tapstate.cloud.sdk.ApiClient;
import io.tapstate.cloud.sdk.ApiException;
import io.tapstate.cloud.sdk.api.AuthApi;
import io.tapstate.cloud.sdk.api.OrganizationsApi;
import io.tapstate.cloud.sdk.api.ResolveExchangeApi;
import io.tapstate.cloud.sdk.api.SubscriptionApi;
import io.tapstate.cloud.sdk.model.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cross-Organization isolation — user A cannot see or act on org B's resources.
 * <p>
 * Covers (cloud-week2-entry-and-onboarding T10 — CloudEntryAuthorizationIT acceptance):
 * <ul>
 *   <li>User A creates org A + cluster; User B cannot resolve org A's cluster</li>
 * </ul>
 */
@DisplayName("CrossOrgAuthIT — cross-organization resource isolation")
class CrossOrgAuthIT extends BaseIT {

    /** Pin the TAPSTATE_CLOUD_SESSION cookie from the last response onto the ApiClient
     *  (OpenAPI Generator ApiClient does NOT auto-manage session cookies). */
    private static void pinSessionCookie(ApiClient api) {
        var headers = api.getResponseHeaders();
        if (headers == null || headers.get("Set-Cookie") == null) return;
        for (String sc : headers.get("Set-Cookie")) {
            int eq = sc.indexOf('=');
            int semi = sc.indexOf(';');
            if (eq > 0 && (semi < 0 || semi > eq)) {
                String name = sc.substring(0, eq).trim();
                String value = semi > eq ? sc.substring(eq + 1, semi).trim() : sc.substring(eq + 1).trim();
                if ("TAPSTATE_CLOUD_SESSION".equals(name)) {
                    api.addDefaultCookie(name, value);
                    return;
                }
            }
        }
    }

    /** Full provision: register + login + org + READY cluster, returns [orgId, clusterId, api] */
    private String[] provisionAsUser(String email, String orgName, String clusterName) throws Exception {
        ApiClient api = new ApiClient();
        api.setBasePath(BASE_URL);
        AuthApi auth = new AuthApi(api);
        OrganizationsApi orgs = new OrganizationsApi(api);
        SubscriptionApi subs = new SubscriptionApi(api);

        register(email, "StrongPass123!");
        auth.login(new LoginRequest().email(email).password("StrongPass123!"));
        pinSessionCookie(api);

        String orgId = orgs.create(organizationRequest(orgName)).getData().getId();
        // Seed the subscription directly (no payment channel here) and finalize it
        // with THIS user's client, because the shared BaseIT client belongs to
        // another session and would be denied on this Organization.
        String subscriptionId = seedSubscription(orgId, "2c8g");
        subs.finalizeProvisioning(orgId, subscriptionId,
            new io.tapstate.cloud.sdk.model.FinalizeProvisioningRequest()
                .region("ap-southeast-1").clusterName(clusterName));
        // Poll for cluster with THIS user's own client — the shared BaseIT client
        // carries a different session and would be denied (403) on this org.
        io.tapstate.cloud.sdk.api.ClustersApi clusters = new io.tapstate.cloud.sdk.api.ClustersApi(api);
        String clusterId = null;
        for (int i = 0; i < 20 && clusterId == null; i++) {
            var list = clusters.listClusters(orgId).getData();
            for (var c : list) {
                if (clusterName.equals(c.getName())) clusterId = c.getId();
            }
            if (clusterId == null) Thread.sleep(500);
        }
        assertNotNull(clusterId, "Cluster should be created");

        // Wait for READY using this user's client
        boolean ready = false;
        for (int i = 0; i < 40 && !ready; i++) {
            try {
                ready = "READY".equals(clusters.getCluster(orgId, clusterId).getData().getState().getValue());
            } catch (Exception ignored) { /* keep polling */ }
            if (!ready) Thread.sleep(1000);
        }
        assertTrue(ready, "Cluster " + clusterId + " never reached READY");

        return new String[]{orgId, clusterId};
    }

    @Test
    @DisplayName("T1 — User B cannot resolve org A's cluster → 403/404")
    void crossOrg_resolve_denied() throws Exception {
        // User A owns cluster in org A
        String[] a = provisionAsUser("userA@tapstate.io", "OrgA", "ClusterA");

        // User B tries to resolve it
        ApiClient clientB = new ApiClient();
        clientB.setBasePath(BASE_URL);
        AuthApi authB = new AuthApi(clientB);
        ResolveExchangeApi resolveB = new ResolveExchangeApi(clientB);
        register("userB@tapstate.io", "StrongPass123!");
        authB.login(new LoginRequest().email("userB@tapstate.io").password("StrongPass123!"));
        pinSessionCookie(clientB);

        // User B tries to resolve cluster A (belongs to org A)
        ApiException ex = assertThrows(ApiException.class,
            () -> resolveB.resolveCluster(a[1], a[0]));
        int code = ex.getCode();
        assertTrue(code == 403 || code == 404,
            "Expected 403/404 for cross-org resolve, got " + code);
    }
}
