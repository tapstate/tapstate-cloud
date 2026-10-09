package io.tapstate.cloud.it.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Raw-HTTP client for the operator Cluster version endpoints used by ITs —
 * the endpoints are {@code @Hidden} and deliberately absent from the generated
 * SDK. Static-token authenticated; the console list endpoint additionally
 * carries a session cookie.
 */
public class ClusterVersionITSupport {

    public static final String RELEASE_TOKEN = "it-release-token";
    public static final String BOOTSTRAP_VERSION = "0.6.0";
    public static final String PATH = "/v1/api/cluster-versions";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient httpClient;
    private final String baseUrl;

    public ClusterVersionITSupport(HttpClient httpClient, String baseUrl) {
        this.httpClient = httpClient;
        this.baseUrl = baseUrl;
    }

    /** Release a STABLE version with the fixed IT token; assert 201 and return the record. */
    public JsonNode releaseStable(String version) throws Exception {
        HttpResponse<String> resp = postRelease(version, "STABLE", RELEASE_TOKEN);
        assertEquals(201, resp.statusCode(),
            "release of " + version + " must return 201, body=" + resp.body());
        return MAPPER.readTree(resp.body()).path("data");
    }

    /** Raw POST so auth-negative tests can inspect status and envelope. */
    public HttpResponse<String> postRelease(String version, String releaseCode, String token)
            throws Exception {
        String body = MAPPER.writeValueAsString(Map.of(
            "version", version,
            "releaseCode", releaseCode,
            "releaseNotes", "it release " + version,
            "git", Map.of("branch", "main", "commitId", version)));
        HttpRequest.Builder req = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + PATH))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            req.header("Authorization", "Bearer " + token);
        }
        return send(req);
    }

    /** Raw PATCH /state so auth-negative tests can inspect status and envelope. */
    public HttpResponse<String> patchStateRaw(String version, String state, String token)
            throws Exception {
        String body = MAPPER.writeValueAsString(Map.of("state", state));
        HttpRequest.Builder req = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + PATH + "/" + version + "/state"))
            .header("Content-Type", "application/json")
            .method("PATCH", HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            req.header("Authorization", "Bearer " + token);
        }
        return send(req);
    }

    /** Flip listing state with the fixed IT token; assert 200. */
    public void setState(String version, String state) throws Exception {
        HttpResponse<String> resp = patchStateRaw(version, state, RELEASE_TOKEN);
        assertEquals(200, resp.statusCode(),
            "state change of " + version + " must return 200, body=" + resp.body());
    }

    /**
     * Fetch the active-version list (session-authenticated).
     *
     * @param sessionCookieHeader full {@code Cookie} header value, or null for an anonymous call
     */
    public JsonNode listActive(String sessionCookieHeader) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + PATH))
            .GET();
        if (sessionCookieHeader != null) {
            req.header("Cookie", sessionCookieHeader);
        }
        HttpResponse<String> resp = send(req);
        assertEquals(200, resp.statusCode(),
            "active list must return 200, body=" + resp.body());
        return MAPPER.readTree(resp.body()).path("data");
    }

    /**
     * Guarantee the bootstrap 0.6.0 record exists and is ACTIVE before a test
     * depends on it. The catalog survives the per-test wipe, so destructive ITs
     * call this to heal earlier manual or failed-run mutations.
     */
    public void ensureBootstrapActive() throws Exception {
        HttpResponse<String> patched =
            patchStateRaw(BOOTSTRAP_VERSION, "ACTIVE", RELEASE_TOKEN);
        if (patched.statusCode() == 200) {
            return;
        }
        // 404 (or anything other than a conflict) → publish it ourselves.
        HttpResponse<String> released =
            postRelease(BOOTSTRAP_VERSION, "STABLE", RELEASE_TOKEN);
        org.junit.jupiter.api.Assertions.assertTrue(
            released.statusCode() == 201 || released.statusCode() == 409,
            "bootstrap version must be restorable, body=" + released.body());
        if (released.statusCode() == 201) {
            return;
        }
        setState(BOOTSTRAP_VERSION, "ACTIVE");
    }

    /** Unique, relaxed-format version string safe to release in the kept catalog. */
    public static String uniqueVersion(String prefix) {
        return prefix + "-it" + Long.toHexString(System.nanoTime());
    }

    private HttpResponse<String> send(HttpRequest.Builder req) throws Exception {
        return httpClient.send(req.build(), HttpResponse.BodyHandlers.ofString());
    }
}
