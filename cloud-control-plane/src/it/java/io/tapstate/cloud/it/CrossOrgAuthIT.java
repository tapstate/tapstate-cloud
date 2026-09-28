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

    /** Full provision: register + login + org + READY cluster, returns [orgId, clusterId, api] */
    private String[] provisionAsUser(String email, String orgName, String clusterName) throws Exception {
        ApiClient api = new ApiClient();
        api.setBasePath(BASE_URL);
        AuthApi auth = new AuthApi(api);
        OrganizationsApi orgs = new OrganizationsApi(api);
        SubscriptionApi subs = new SubscriptionApi(api);

        auth.register(new RegisterRequest().email(email).password("StrongPass123!").displayName(email.split("@")[0]));
        auth.login(new LoginRequest().email(email).password("StrongPass123!"));

        String orgId = orgs.create(Map.of("name", orgName, "slug", orgName.toLowerCase())).getId();
        subs.createSubscription(orgId, new CreateSubscriptionRequest()
            .region("ap-southeast-1").clusterName(clusterName).specKey("basic"));
        // Poll for cluster via raw HTTP
        String clusterId = null;
        HttpClient hc = HttpClient.newHttpClient();
        for (int i = 0; i < 10; i++) {
            HttpResponse<String> r = hc.send(HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/v1/orgs/" + orgId + "/clusters"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
            JsonNode arr = new ObjectMapper().readTree(r.body()).path("data");
            if (arr.isArray()) {
                for (JsonNode c : arr) {
                    if (clusterName.equals(c.path("name").asText())) clusterId = c.path("id").asText();
                }
            }
            if (clusterId != null) break;
            Thread.sleep(500);
        }
        assertNotNull(clusterId, "Cluster should be created");
        waitForClusterState(orgId, clusterId, "READY", 30, 1000);

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
        authB.register(new RegisterRequest().email("userB@tapstate.io").password("StrongPass123!").displayName("userB"));
        authB.login(new LoginRequest().email("userB@tapstate.io").password("StrongPass123!"));

        // User B tries to resolve cluster A (belongs to org A)
        ApiException ex = assertThrows(ApiException.class,
            () -> resolveB.resolveCluster(a[1], a[0]));
        int code = ex.getCode();
        assertTrue(code == 403 || code == 404,
            "Expected 403/404 for cross-org resolve, got " + code);
    }
}
