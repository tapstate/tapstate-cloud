package io.tapstate.cloud.it;

import io.tapstate.cloud.sdk.ApiException;
import io.tapstate.cloud.sdk.model.CreateOrganizationRequest;
import io.tapstate.cloud.sdk.model.LoginResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Base class for tests that run against a pre-deployed Control Plane —
 * no local application process is forked. The target environment is
 * selected entirely from environment variables, so the same test class
 * runs against awsdev, a manual deploy, or any reachable instance.
 * <p>
 * Required environment variables (all resolved at class-load time):
 * <ul>
 *   <li>{@code CP_BASE_URL} — Control Plane base URL, e.g.
 *       {@code https://dev.cloud.tapstate.com} (no trailing slash).
 *       HTTP is accepted; HTTPS recommended.</li>
 *   <li>{@code CP_EMAIL} / {@code CP_PASSWORD} — existing Control Plane user
 *       credentials. We login as-is; no self-registration against a shared
 *       env, because week-3 shared envs may require invitations / SSO.</li>
 *   <li>{@code MONGO_URI} — optional — a MongoDB connection string with a
 *       database name included. When present the tests use {@code mongosh}
 *       to verify Cluster / cleanup documents on the store directly; when
 *       absent, store-visibility tests are SKIPPED (auth/API-only scenarios
 *       still run). A full URI with embedded credentials is sanitized to
 *       {@code scheme://hostList} when written to test output.</li>
 * </ul>
 * Optional environment variables:
 * <ul>
 *   <li>{@code CP_PROXY_HOST} / {@code CP_PROXY_PORT} — outgoing HTTP proxy
 *       for environments that require one (e.g. a GitHub Actions runner
 *       inside a restricted egress policy). {@code PROXY_HOST=none} disables.</li>
 *   <li>{@code CP_ORG_ID} — Organization UUID to use for Cluster operations.
 *       When absent the test registers + creates one implicitly, which works
 *       only on envs where self-service is open (dev).</li>
 * </ul>
 * <p>
 * Tests subclass this, add the scenario-specific assertions, and get:
 * <ul>
 *   <li>A fresh SDK {@link io.tapstate.cloud.sdk.ApiClient} pointing at
 *       {@code CP_BASE_URL}.</li>
 *   <li>Login + session-cookie capture in {@link #loginAsConfiguredUser()}.</li>
 *   <li>Optional mongosh helpers via {@link BaseIT} (activated only when
 *       {@code MONGO_URI} is set).</li>
 * </ul>
 * <p>
 * Unlike {@link AwsAtlasFullLifecycleIT}, this class does NOT fork an
 * application jar — it assumes the target Control Plane is already running.
 * Unlike {@link BaseIT}, the default {@code @BeforeEach wipeTestDatabase()} is
 * suppressed because we never own the target store; subclasses opt into
 * per-test cleanup explicitly when needed.
 */
public abstract class RealEnvIT {

    // ---- Environment bindings ----

    /** Resolved from {@code CP_BASE_URL} env var. */
    protected static String BASE_URL;

    /** {@code CP_EMAIL} — existing account we authenticate as. */
    protected static String USER_EMAIL;

    /** {@code CP_PASSWORD}. */
    protected static String USER_PASSWORD;

    /**
     * {@code MONGO_URI} from env. Optional — when absent the store-visibility
     * assertions are SKIPPED, but API-only scenarios still run.
     */
    protected static String MONGO_URI;

    /** Mongo database name — extracted from {@link #MONGO_URI} when set. */
    protected static String MONGO_DB;

    /** Optional org id to use instead of creating one fresh. */
    protected static String ORG_ID;

    // ---- SDK stubs (inherited/duplicated from BaseIT) ----

    protected io.tapstate.cloud.sdk.ApiClient client;
    protected io.tapstate.cloud.sdk.api.AuthApi authApi;
    protected io.tapstate.cloud.sdk.api.OrganizationsApi orgsApi;
    protected io.tapstate.cloud.sdk.api.ClustersApi clustersApi;
    protected io.tapstate.cloud.sdk.api.SubscriptionApi subscriptionApi;
    protected io.tapstate.cloud.sdk.api.DeploymentProgressApi progressApi;

    /** Captured session cookie value (TAPSTATE_CLOUD_SESSION). */
    protected String sessionCookieValue;

    // ---- Lifecycle ----

    @BeforeAll
    static void bindEnvironment() {
        BASE_URL = System.getenv("CP_BASE_URL");
        USER_EMAIL = System.getenv("CP_EMAIL");
        USER_PASSWORD = System.getenv("CP_PASSWORD");
        MONGO_URI = System.getenv("MONGO_URI");
        ORG_ID = System.getenv("CP_ORG_ID");

        // Hard requirements — skip all tests if missing (assumeTrue → SKIP).
        assumeTrue(BASE_URL != null && !BASE_URL.isBlank(),
            "CP_BASE_URL is not set — skipping real-env test");
        assumeTrue(USER_EMAIL != null && !USER_EMAIL.isBlank(),
            "CP_EMAIL is not set — skipping real-env test");
        assumeTrue(USER_PASSWORD != null,
            "CP_PASSWORD is not set — skipping real-env test");

        BASE_URL = BASE_URL.endsWith("/") ? BASE_URL.substring(0, BASE_URL.length() - 1) : BASE_URL;

        // Optional Mongo binding — extract db name if a full URI was supplied.
        if (MONGO_URI != null && !MONGO_URI.isBlank()) {
            String afterHost = MONGO_URI.substring(MONGO_URI.indexOf("://") + 3);
            int slash = afterHost.indexOf('/');
            MONGO_DB = slash < 0 ? "tapstate_cloud"
                : afterHost.substring(slash + 1).split("\\?")[0];
        } else {
            MONGO_DB = null;
        }

        System.out.println("[real-env] BASE_URL=" + BASE_URL
            + " USER=" + USER_EMAIL + " MONGO=" + (MONGO_URI != null ? "yes(" + MONGO_DB + ")" : "no")
            + " ORG=" + ORG_ID);
    }

    /**
     * Fresh ApiClient per test so the cookie jar is clean.
     * Uses the SDK's Apache HttpClient — session cookies are NOT auto-persisted,
     * so every login must call {@link #captureSessionCookie()}.
     */
    @BeforeEach
    void setUp() {
        this.client = new io.tapstate.cloud.sdk.ApiClient();
        client.setBasePath(BASE_URL);
        this.authApi = new io.tapstate.cloud.sdk.api.AuthApi(client);
        this.orgsApi = new io.tapstate.cloud.sdk.api.OrganizationsApi(client);
        this.clustersApi = new io.tapstate.cloud.sdk.api.ClustersApi(client);
        this.subscriptionApi = new io.tapstate.cloud.sdk.api.SubscriptionApi(client);
        this.progressApi = new io.tapstate.cloud.sdk.api.DeploymentProgressApi(client);
        this.sessionCookieValue = null;
    }

    // ---- Auth helpers ----

    /** Login with the env-provided credentials and capture the session cookie. */
    protected io.tapstate.cloud.sdk.model.LoginResult loginAsConfiguredUser() throws ApiException {
        var result = authApi.login(new io.tapstate.cloud.sdk.model.LoginRequest()
            .email(USER_EMAIL).password(USER_PASSWORD)).getData();
        captureSessionCookie();
        return result;
    }

    /** Extract TAPSTATE_CLOUD_SESSION from the last Set-Cookie response and pin it on the ApiClient. */
    protected void captureSessionCookie() {
        var headers = client.getResponseHeaders();
        if (headers == null) return;
        java.util.List<String> setCookies = headers.get("Set-Cookie");
        if (setCookies == null) return;
        for (String sc : setCookies) {
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

    /**
     * Resolve an Organization id — either the env-provided one, or a fresh
     * org created under the logged-in account. Callers that pass {@code ORG_ID}
     * explicitly use {@link orgsApi} directly and ignore this method.
     */
    protected String resolveOrgId() throws ApiException {
        if (ORG_ID != null && !ORG_ID.isBlank()) return ORG_ID;
        long suffix = System.nanoTime();
        return orgsApi.create(organizationRequest("real-env-test-" + suffix)).getData().getId();
    }

    protected CreateOrganizationRequest organizationRequest(String name) {
        return new CreateOrganizationRequest().name(name);
    }

    /** Register + login a brand-new user (self-service; works only on envs that allow it). */
    protected io.tapstate.cloud.sdk.model.LoginResult signupAndLogin(String email, String password) throws ApiException {
        authApi.register(new io.tapstate.cloud.sdk.model.RegisterRequest()
            .email(email).password(password).displayName(email.split("@")[0]));
        var result = authApi.login(new io.tapstate.cloud.sdk.model.LoginRequest()
            .email(email).password(password)).getData();
        captureSessionCookie();
        return result;
    }

    protected void logout() throws ApiException {
        authApi.logout();
    }

    // ---- Mongo helpers (only active when MONGO_URI is set) ----

    protected boolean mongoAvailable() {
        return MONGO_URI != null && !MONGO_URI.isBlank();
    }

    /**
     * Run a mongosh script against {@link #MONGO_URI}. Throws with the merged
     * shell output on non-zero exit. Scripts should use
     * {@code print(JSON.stringify(...))} for clean parseable output.
     */
    protected String mongoshEval(String eval) throws Exception {
        assumeTrue(mongoAvailable(), "MONGO_URI not set — skipping store visibility assertion");
        Process p = new ProcessBuilder("mongosh", "--quiet", MONGO_URI, "--eval", eval)
            .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        int exit = p.waitFor();
        if (exit != 0) {
            String redacted = MONGO_URI.replaceAll("://[^@/]+@", "://***:***@");
            throw new IllegalStateException(
                "mongosh failed (exit " + exit + ") against " + redacted + ":\n" + out.strip());
        }
        return out;
    }

    // ---- Cluster readiness poller ----

    /**
     * Poll Cluster state up to {@code maxAttempts} times, waiting
     * {@code intervalMs} between polls. Throws {@link AssertionError} if
     * {@code targetState} is never observed. {@code 0} as maxAttempts means
     * "once-shot read — fail if wrong".
     */
    protected void waitForClusterState(String orgId, String clusterId,
                                        String targetState, int maxAttempts, int intervalMs) throws Exception {
        for (int i = 0; i < maxAttempts; i++) {
            try {
                var resp = clustersApi.getCluster(orgId, clusterId).getData();
                String state = resp.getState().getValue();
                if (targetState.equals(state)) return;
            } catch (Exception ignored) { /* keep polling */ }
            Thread.sleep(intervalMs);
        }
        throw new AssertionError("Cluster " + clusterId + " never reached " + targetState
            + " after " + (maxAttempts * intervalMs / 1000) + "s");
    }
}
