package io.tapstate.cloud.it;

import io.tapstate.cloud.sdk.ApiClient;
import io.tapstate.cloud.sdk.ApiException;
import io.tapstate.cloud.sdk.api.AuthApi;
import io.tapstate.cloud.sdk.api.ClustersApi;
import io.tapstate.cloud.sdk.api.ExchangeApi;
import io.tapstate.cloud.sdk.api.OrganizationsApi;
import io.tapstate.cloud.sdk.api.ResolveExchangeApi;
import io.tapstate.cloud.sdk.api.SubscriptionApi;
import io.tapstate.cloud.sdk.model.LoginRequest;
import io.tapstate.cloud.sdk.model.LoginResult;
import io.tapstate.cloud.sdk.model.RegisterRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Base class for failsafe integration tests — runs against a LIVE Spring Boot
 * instance started by spring-boot-maven-plugin in pre-integration-test.
 * <p>
 * Each IT gets:
 * <ul>
 *   <li>A fresh {@link ApiClient} pointing at {@code http://localhost:{port}}</li>
 *   <li>Shared API stubs ({@link AuthApi}, {@link ClustersApi}, ...)</li>
 *   <li>Pre/post-test MongoDB cleanup (wipes test DB collections)</li>
 *   <li>Helper methods for register/login and cookie session management</li>
 * </ul>
 * <p>
 * Port resolution: System property {@code it.server.port} (set by pom.xml failsafe
 * config, default 8081). Falls back to 8080 if unset.
 */
public abstract class BaseIT {

    /** Server base URL — resolved from System property or default. */
    protected static String BASE_URL;

    /** MongoDB URI for direct cleanup between tests. */
    protected static String MONGO_URI = "mongodb://localhost:27017";
    protected static String MONGO_DB = "tapstate_cloud_it";

    /** One shared HttpClient for raw HTTP calls (fallback where SDK doesn't cover). */
    protected static HttpClient httpClient;

    // ---- SDK API stubs ----
    protected ApiClient client;
    protected AuthApi authApi;
    protected OrganizationsApi orgsApi;
    protected ClustersApi clustersApi;
    protected ResolveExchangeApi resolveApi;
    protected ExchangeApi exchangeApi;
    protected SubscriptionApi subscriptionApi;

    @BeforeAll
    static void resolveBaseUrl() {
        String port = System.getProperty("it.server.port", "8081");
        BASE_URL = "http://localhost:" + port;
        // Install a default CookieManager so HttpClient.newBuilder().cookieHandler(...) works
        if (java.net.CookieManager.getDefault() == null) {
            java.net.CookieManager.setDefault(new java.net.CookieManager());
        }
        httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .cookieHandler(java.net.CookieManager.getDefault())
            .build();
    }

    @BeforeEach
    void setUp() {
        wipeTestDatabase();
        resetApiClient();
        sessionCookieValue = null;
    }

    /** New ApiClient per test so cookie jar is fresh. */
    protected void resetApiClient() {
        this.client = new ApiClient();
        client.setBasePath(BASE_URL);
        this.authApi = new AuthApi(client);
        this.orgsApi = new OrganizationsApi(client);
        this.clustersApi = new ClustersApi(client);
        this.resolveApi = new ResolveExchangeApi(client);
        this.exchangeApi = new ExchangeApi(client);
        this.subscriptionApi = new SubscriptionApi(client);
    }

    // ---- Auth helpers ----

    /** Register a new user (auto-verified in Week 2 preview). */
    protected void register(String email, String password) throws ApiException {
        authApi.register(new io.tapstate.cloud.sdk.model.RegisterRequest()
            .email(email).password(password).displayName(email.split("@")[0]));
    }

    /** Login — SDK ApiClient uses Apache HttpClient which does NOT auto-persist session
     *  cookies across requests. We extract the TAPSTATE_CLOUD_SESSION cookie from the
     *  Set-Cookie response header and add it as a default cookie on the client. */
    protected LoginResult login(String email, String password) throws ApiException {
        LoginResult result = authApi.login(new io.tapstate.cloud.sdk.model.LoginRequest()
            .email(email).password(password)).getData();
        captureSessionCookie();
        return result;
    }

    /** Last captured session cookie value (TAPSTATE_CLOUD_SESSION). */
    protected String sessionCookieValue;

    /** Extract TAPSTATE_CLOUD_SESSION from the last Set-Cookie response and pin it
     *  on the ApiClient so subsequent requests carry it. */
    protected void captureSessionCookie() {
        var headers = client.getResponseHeaders();
        if (headers == null) return;
        List<String> setCookies = headers.get("Set-Cookie");
        if (setCookies == null) return;
        for (String sc : setCookies) {
            // Set-Cookie format: "NAME=VALUE; Path=/; HttpOnly; ..."
            int eq = sc.indexOf('=');
            int semi = sc.indexOf(';');
            if (eq > 0 && (semi < 0 || semi > eq)) {
                String name = sc.substring(0, eq).trim();
                String value = semi > eq ? sc.substring(eq + 1, semi).trim() : sc.substring(eq + 1).trim();
                if ("TAPSTATE_CLOUD_SESSION".equals(name)) {
                    client.addDefaultCookie(name, value);
                    sessionCookieValue = value;
                    return;
                }
            }
        }
    }

    /** Logout — clears server-side session. Client-side cookie is cleared on next setUp(). */
    protected void logout() throws ApiException {
        authApi.logout();
    }

    /** Convenience: register + login, returns LoginResult. */
    protected LoginResult signupAndLogin(String email, String password) throws ApiException {
        register(email, password);
        return login(email, password);
    }

    /** Create an anonymous AuthApi (no session cookie) — for unauthenticated endpoint tests. */
    protected AuthApi createAnonymousAuthApi() {
        ApiClient anonymous = new ApiClient();
        anonymous.setBasePath(BASE_URL);
        return new AuthApi(anonymous);
    }

    // ---- Raw HTTP helpers (for endpoints the SDK doesn't cover) ----

    protected HttpResponse<String> get(String path, java.util.Map<String, String> headers) {
        try {
            HttpRequest.Builder req = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + path))
                .GET();
            if (headers != null) headers.forEach(req::header);
            return httpClient.send(req.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    protected HttpResponse<String> post(String path, String body, java.util.Map<String, String> headers) {
        try {
            HttpRequest.Builder req = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + path))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json");
            if (headers != null) headers.forEach(req::header);
            return httpClient.send(req.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ---- MongoDB cleanup ----

    /** Wipes all collections in the test DB before each IT class. Idempotent. */
    protected static void wipeTestDatabase() {
        try {
            Process p = new ProcessBuilder("mongosh", "--quiet", "--eval",
                // Drop business collections but preserve:
                //   sessions — Spring Session store
                //   regions — seeded once by RegionSeeder on app startup
                //   atlas_instance_configs — seeded once by AtlasInstanceConfigSeeder
                //   system.indexes — internal MongoDB
                // After dropping, reset currentCustomers on preserved Atlas instances
                //   so each IT test starts with a clean capacity count.
                "const keep = new Set(['sessions','regions','atlas_instance_configs','system.indexes']);" +
                "db.getCollectionNames().filter(c => !keep.has(c)).forEach(c => db.getCollection(c).drop());" +
                "db.atlas_instance_configs.updateMany({}, {$set: {currentCustomers: 0, updatedAt: new Date()}});" +
                "db.atlas_instance_configs.find().forEach(c => print(' [wipe] Atlas instance ' + c._id + ' capacity reset ' + c.currentCustomers + '/' + c.maxCustomers));",
                MONGO_DB)
                .redirectErrorStream(true)
                .start();
            // Consume stdout so it doesn't block the pipe
            new Thread(() -> {
                try { p.getInputStream().readAllBytes(); } catch (Exception ignored) {}
            }).start();
            int rc = p.waitFor();
            if (rc != 0) {
                System.err.println("[BaseIT] mongosh wipe exit code=" + rc);
            }
        } catch (Exception e) {
            System.err.println("[BaseIT] MongoDB cleanup error: " + e.getMessage());
        }
    }

    // ---- Cluster readiness ----

    /**
     * Poll cluster state up to {@code maxAttempts} times, waiting {@code intervalMs} between polls.
     * Throws AssertionError if target state never observed.
     */
    protected void waitForClusterState(String orgId, String clusterId,
                                        String targetState, int maxAttempts, int intervalMs) throws Exception {
        for (int i = 0; i < maxAttempts; i++) {
            try {
                var resp = clustersApi.getCluster(orgId, clusterId);
                String state = resp.getData().getState().getValue();
                if (targetState.equals(state)) return;
            } catch (Exception ignored) { /* keep polling */ }
            Thread.sleep(intervalMs);
        }
        throw new AssertionError("Cluster " + clusterId + " never reached " + targetState
            + " after " + (maxAttempts * intervalMs / 1000) + "s");
    }

    /** Convenience: wait up to 20s (20 × 1s) for cluster to become READY. */
    protected void waitForReady(String orgId, String clusterId) throws Exception {
        waitForClusterState(orgId, clusterId, "READY", 30, 1000);
    }
}
