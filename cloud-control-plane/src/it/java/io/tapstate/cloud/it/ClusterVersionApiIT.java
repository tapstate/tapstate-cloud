package io.tapstate.cloud.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tapstate.cloud.it.support.ClusterVersionITSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IT for the Cluster version catalog API:
 * <ul>
 *   <li>GET list is session-authenticated, ACTIVE-only, newest first</li>
 *   <li>POST publish authenticates the static Bearer token and validates input</li>
 *   <li>PATCH /state lists/delists under the same token</li>
 * </ul>
 * The {@code cluster_versions} collection survives the per-test wipe, so every
 * released version uses a unique nano suffix and the bootstrap 0.6.0 record is
 * restored to ACTIVE before each test.
 */
class ClusterVersionApiIT extends BaseIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ClusterVersionITSupport versions;

    @BeforeEach
    void setUpSupport() throws Exception {
        versions = new ClusterVersionITSupport(httpClient, BASE_URL);
        versions.ensureBootstrapActive();
    }

    @Test
    void listRequiresSessionAndReturnsActiveVersionsNewestFirst() throws Exception {
        // Anonymous GET is rejected by Spring Security.
        assertEquals(401, get(ClusterVersionITSupport.PATH, null).statusCode());

        signupWithOrg("Version List IT");

        String versionA = ClusterVersionITSupport.uniqueVersion("1.4.0-alpha");
        String versionB = ClusterVersionITSupport.uniqueVersion("1.4.0-beta");
        versions.releaseStable(versionA);
        versions.releaseStable(versionB);

        JsonNode data = versions.listActive("TAPSTATE_CLOUD_SESSION=" + sessionCookieValue);
        assertTrue(data.isArray(), "list payload must be an array");

        List<String> versionStrings = new ArrayList<>();
        long previousNumber = Long.MAX_VALUE;
        for (JsonNode row : data) {
            assertEquals("ACTIVE", row.path("state").asText(),
                "only ACTIVE versions may be listed");
            versionStrings.add(row.path("version").asText());
            long number = row.path("versionNumber").asLong();
            assertTrue(number < previousNumber,
                "list must be ordered by descending versionNumber");
            previousNumber = number;
        }
        assertTrue(versionStrings.contains(ClusterVersionITSupport.BOOTSTRAP_VERSION),
            "bootstrap 0.6.0 must be listed");
        assertTrue(versionStrings.contains(versionA));
        assertTrue(versionStrings.contains(versionB));
        assertTrue(versionStrings.indexOf(versionB) < versionStrings.indexOf(versionA),
            "the later release must sort first");

        // Delisting removes A from the list without deleting it.
        versions.setState(versionA, "OFFLINE");
        JsonNode afterDelist = versions.listActive("TAPSTATE_CLOUD_SESSION=" + sessionCookieValue);
        List<String> remaining = new ArrayList<>();
        afterDelist.forEach(row -> remaining.add(row.path("version").asText()));
        assertFalse(remaining.contains(versionA), "delisted version must disappear from the list");
        assertTrue(remaining.contains(versionB));
        versions.setState(versionA, "ACTIVE");
    }

    @Test
    void publishAuthenticatesStaticBearerTokenAndRejectsDuplicates() throws Exception {
        String version = ClusterVersionITSupport.uniqueVersion("2.0.0-rc.1");

        // Missing header.
        var missing = versions.postRelease(version, "RC", null);
        assertEquals(401, missing.statusCode());
        assertTrue(missing.body().contains("cluster.release-token-missing"), missing.body());

        // A non-matching Bearer secret.
        var badScheme = versions.postRelease(version, "RC", "not-a-bearer-header");
        assertEquals(401, badScheme.statusCode());

        // Wrong token.
        var wrongToken = versions.postRelease(version, "RC", "wrong-token");
        assertEquals(401, wrongToken.statusCode());
        assertTrue(wrongToken.body().contains("cluster.release-token-invalid"), wrongToken.body());

        // Correct token → 201 with server-assigned fields.
        var ok = versions.postRelease(version, "RC", ClusterVersionITSupport.RELEASE_TOKEN);
        assertEquals(201, ok.statusCode(), ok.body());
        JsonNode record = MAPPER.readTree(ok.body()).path("data");
        assertEquals(version, record.path("version").asText());
        assertEquals("RC", record.path("releaseCode").asText());
        assertEquals("ACTIVE", record.path("state").asText());
        assertTrue(record.path("versionNumber").asLong() > 0);
        assertFalse(record.path("id").asText().isBlank());
        assertEquals("main", record.path("git").path("branch").asText());

        // Releasing the same version conflicts.
        var duplicate = versions.postRelease(version, "RC", ClusterVersionITSupport.RELEASE_TOKEN);
        assertEquals(409, duplicate.statusCode());
        assertTrue(duplicate.body().contains("cluster.version-duplicate"), duplicate.body());
    }

    @Test
    void publishValidatesVersionAndGitPayload() throws Exception {
        String token = ClusterVersionITSupport.RELEASE_TOKEN;

        // Blank version → 400 even with a valid token.
        var blankVersion = post(ClusterVersionITSupport.PATH,
            MAPPER.writeValueAsString(Map.of(
                "version", "   ",
                "releaseCode", "STABLE",
                "git", Map.of("branch", "main", "commitId", "abc"))),
            Map.of("Authorization", "Bearer " + token));
        assertEquals(400, blankVersion.statusCode(), blankVersion.body());

        // Missing git block → 400.
        var missingGit = post(ClusterVersionITSupport.PATH,
            MAPPER.writeValueAsString(Map.of(
                "version", ClusterVersionITSupport.uniqueVersion("2.1.0"),
                "releaseCode", "STABLE")),
            Map.of("Authorization", "Bearer " + token));
        assertEquals(400, missingGit.statusCode(), missingGit.body());
    }

    @Test
    void patchStateRejectsBadTokenAndUnknownVersion() throws Exception {
        String version = ClusterVersionITSupport.uniqueVersion("2.2.0");
        versions.releaseStable(version);

        try {
            // Wrong token → 401.
            var badToken = versions.patchStateRaw(version, "OFFLINE", "nope");
            assertEquals(401, badToken.statusCode());

            // Correct token delists → 200, and the list drops it.
            assertEquals(200, versions.patchStateRaw(version, "OFFLINE",
                ClusterVersionITSupport.RELEASE_TOKEN).statusCode());
            signupWithOrg("Version Patch IT");
            JsonNode data = versions.listActive("TAPSTATE_CLOUD_SESSION=" + sessionCookieValue);
            List<String> listed = new ArrayList<>();
            data.forEach(row -> listed.add(row.path("version").asText()));
            assertFalse(listed.contains(version));

            // Unknown version → 404.
            var notFound = versions.patchStateRaw("9.9.9-it" + System.nanoTime(), "ACTIVE",
                ClusterVersionITSupport.RELEASE_TOKEN);
            assertEquals(404, notFound.statusCode());
            assertTrue(notFound.body().contains("cluster.version-not-found"), notFound.body());
        } finally {
            // Restore so repeated local runs keep a clean catalog even if assertions change.
            versions.setState(version, "ACTIVE");
        }
    }
}
