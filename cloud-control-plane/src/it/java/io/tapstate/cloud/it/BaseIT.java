package io.tapstate.cloud.it;

import io.tapstate.cloud.sdk.ApiClient;
import io.tapstate.cloud.sdk.ApiException;
import io.tapstate.cloud.sdk.api.AuthApi;
import io.tapstate.cloud.sdk.api.ClustersApi;
import io.tapstate.cloud.sdk.api.DeploymentProgressApi;
import io.tapstate.cloud.sdk.api.ExchangeApi;
import io.tapstate.cloud.sdk.api.OrganizationsApi;
import io.tapstate.cloud.sdk.api.ResolveExchangeApi;
import io.tapstate.cloud.sdk.api.SubscriptionApi;
import io.tapstate.cloud.sdk.model.LoginRequest;
import io.tapstate.cloud.sdk.model.LoginResult;
import io.tapstate.cloud.sdk.model.RegisterRequest;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
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
import java.util.Map;
import java.time.Instant;
import java.util.Date;

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

    /**
     * Control-plane store for the IT run. Local mongod by default; CI injects a
     * full Atlas connection string (database name included) via
     * {@code TAPSTATE_CLOUD_MONGO_URI}. The forked application and every
     * mongosh invocation in the ITs use the same value.
     */
    protected static final String MONGO_URI = System.getenv()
        .getOrDefault("TAPSTATE_CLOUD_MONGO_URI", "mongodb://localhost:27017/tapstate_cloud_it");
    protected static final String MONGO_DB = resolveDatabaseName();

    private static String resolveDatabaseName() {
        String override = System.getenv("TAPSTATE_CLOUD_MONGO_DB");
        if (override != null && !override.isBlank()) {
            return override;
        }
        // Take the path component of a full URI: mongodb://host[:port]/<db>[?opts]
        String afterHost = MONGO_URI.substring(MONGO_URI.indexOf("://") + 3);
        int slash = afterHost.indexOf('/');
        if (slash < 0) {
            return "tapstate_cloud_it";
        }
        String dbAndOptions = afterHost.substring(slash + 1);
        int question = dbAndOptions.indexOf('?');
        String db = question >= 0 ? dbAndOptions.substring(0, question) : dbAndOptions;
        return db.isBlank() ? "tapstate_cloud_it" : db;
    }

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
    protected DeploymentProgressApi progressApi;

    @BeforeAll
    static void resolveBaseUrl() {
        String port = System.getProperty("it.server.port", "8081");
        BASE_URL = "http://localhost:" + port;
        // Deliberately NO CookieHandler/CookieManager: every test manages its
        // TAPSTATE_CLOUD_SESSION explicitly via the Cookie header. A shared jar
        // here silently stores Set-Cookie values from other responses — and the
        // server even issues a fresh session cookie on anonymous 401 responses
        // (e.g. JwksIT probing a protected endpoint). The JDK client then sends
        // the jar cookie AHEAD of the explicit one, producing two session
        // cookies in one request; Spring Session resolves the wrong (anonymous
        // or another test's) identity and every later protected GET fails with
        // 403 org.not-member. Keep this client cookie-jar free.
        httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
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
        this.progressApi = new DeploymentProgressApi(client);
    }

    // ---- Auth helpers ----

    /** Seed an already-verified account for tests that exercise authenticated APIs. */
    protected void register(String email, String password) throws ApiException {
        try (MongoClient mongo = MongoClients.create(MONGO_URI)) {
            mongo.getDatabase(MONGO_DB).getCollection("users").insertOne(new Document()
                .append("email", email.trim().toLowerCase(java.util.Locale.ROOT))
                .append("passwordHash", new BCryptPasswordEncoder().encode(password))
                .append("displayName", email.split("@")[0])
                .append("emailVerified", true)
                .append("status", "active")
                .append("createdAt", Date.from(Instant.now()))
                .append("updatedAt", Date.from(Instant.now())));
        }
    }

    protected long countUsers(String email) {
        try (MongoClient mongo = MongoClients.create(MONGO_URI)) {
            return mongo.getDatabase(MONGO_DB).getCollection("users")
                .countDocuments(new Document("email", email.trim().toLowerCase(java.util.Locale.ROOT)));
        }
    }

    protected long countMembers(String email) {
        try (MongoClient mongo = MongoClients.create(MONGO_URI)) {
            return mongo.getDatabase(MONGO_DB).getCollection("org_members")
                .countDocuments(new Document("userEmail", email.trim().toLowerCase(java.util.Locale.ROOT)));
        }
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

    /**
     * Register + login a fresh user and create one Organization — the common
     * starting point for single-org ITs. Returns the new Organization id.
     */
    protected String signupWithOrg(String orgName) throws Exception {
        long suffix = System.nanoTime();
        signupAndLogin("it-" + suffix + "@tapstate.io", "StrongPass123!");
        return orgsApi.create(Map.of(
            "name", orgName,
            "slug", orgName.toLowerCase().replaceAll("[^a-z0-9]+", "-") + "-" + suffix
        )).getData().getId();
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
            HttpResponse<String> resp = httpClient.send(req.build(), HttpResponse.BodyHandlers.ofString());
            traceResponse("GET", path, resp);
            return resp;
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
            HttpResponse<String> resp = httpClient.send(req.build(), HttpResponse.BodyHandlers.ofString());
            traceResponse("POST", path, resp);
            return resp;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    protected HttpResponse<String> patch(String path, String body, java.util.Map<String, String> headers) {
        try {
            HttpRequest.Builder req = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + path))
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json");
            if (headers != null) headers.forEach(req::header);
            HttpResponse<String> resp = httpClient.send(req.build(), HttpResponse.BodyHandlers.ofString());
            traceResponse("PATCH", path, resp);
            return resp;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Surface non-2xx raw responses in failsafe output. Polling helpers used to
     * treat every error envelope as "resource not visible yet", which hid the
     * real cause (401/403/500) behind an assertion timeout. Bodies are capped so
     * an unexpected HTML error page cannot flood the log.
     */
    private static void traceResponse(String method, String path, HttpResponse<String> resp) {
        if (resp.statusCode() / 100 == 2) return;
        String body = resp.body() == null ? "" : resp.body();
        if (body.length() > 300) {
            body = body.substring(0, 300) + "...";
        }
        System.out.println("[it-http] " + method + " " + path + " -> " + resp.statusCode() + " body=" + body);
    }

    // ---- MongoDB shell helpers ----

    /**
     * Run a mongosh script against the IT control-plane store ({@link #MONGO_URI},
     * which may be a local mongod or an Atlas cluster) and return merged output.
     * Scripts print their own result; prefer {@code print(JSON.stringify(...))}
     * because plain {@code printjson} emits shell-style EJSON (unquoted keys,
     * Long("...")) that is not valid JSON.
     */
    protected static String mongoshEval(String eval) throws Exception {
        return mongoshUri(MONGO_URI, eval);
    }

    /**
     * Run a mongosh script against an explicit connection string (e.g. a
     * per-allocation Atlas URI). A non-zero exit (connection refused, auth
     * failure, SRV resolution error, ...) throws with the merged shell output
     * so a broken helper never looks like a successful test setup.
     */
    protected static String mongoshUri(String connectionUri, String eval) throws Exception {
        Process p = new ProcessBuilder("mongosh", "--quiet", connectionUri, "--eval", eval)
            .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        int exit = p.waitFor();
        if (exit != 0) {
            String redacted = connectionUri.replaceAll("://[^@/]+@", "://***:***@");
            throw new IllegalStateException(
                "mongosh failed (exit " + exit + ") against " + redacted + ":\n" + out.strip());
        }
        return out;
    }

    // ---- Subscription fixtures ----

    /**
     * Insert a provisionable subscription straight into the store.
     * <p>
     * There is no API that creates a subscription without a payment channel, and
     * the integration suite has no channel — so a test that needs one starts from
     * the state the webhook would have produced (TRIAL, spec snapshot, the two
     * billable items) and then goes through the real {@code finalize-provisioning}
     * call. What is skipped is the payment, not the behaviour under test.
     *
     * @return hex id of the inserted subscription
     */
    protected String seedSubscription(String orgId, String specKey) throws Exception {
        int[] shape = switch (specKey) {
            case "2c8g" -> new int[]{2, 8, 7, 10};
            case "4c16g" -> new int[]{4, 16, 0, 20};
            case "8c32g" -> new int[]{8, 32, 0, 50};
            default -> throw new IllegalArgumentException("unknown spec " + specKey);
        };
        String out = mongoshEval("""
            const sid = new ObjectId();
            const now = new Date();
            db.subscriptions.insertOne({
              _id: sid,
              orgId: ObjectId('%s'),
              status: 'TRIAL',
              provisionInitiated: true,
              specConfig: {key: '%s', displayName: '%s', cpuCores: %d, memoryGb: %d,
                           trialDays: %d, mdbSizeGb: %d, enabled: true,
                           _class: 'io.tapstate.cloud.subscription.SpecConfig'},
              currentPeriodStart: now,
              currentPeriodEnd: new Date(now.getTime() + 30 * 86400000),
              createdAt: now,
              updatedAt: now,
              _class: 'io.tapstate.cloud.subscription.Subscription'
            });
            db.subscription_items.insertMany([
              {_id: new ObjectId(), subscriptionId: sid, orgId: ObjectId('%s'),
               itemType: 'CLUSTER_PIPELINE_RUNTIME', includedQuantity: 0, unitPriceCents: NumberLong(0),
               metadata: {}, createdAt: now, updatedAt: now,
               _class: 'io.tapstate.cloud.subscription.SubscriptionItem'},
              {_id: new ObjectId(), subscriptionId: sid, orgId: ObjectId('%s'),
               itemType: 'ATLAS_DATABASE_STORAGE', includedQuantity: 0, unitPriceCents: NumberLong(0),
               metadata: {}, createdAt: now, updatedAt: now,
               _class: 'io.tapstate.cloud.subscription.SubscriptionItem'}
            ]);
            print('SEEDED=' + sid.toHexString());
            """.formatted(orgId, specKey, specKey, shape[0], shape[1], shape[2], shape[3], orgId, orgId));
        // mongosh can prepend diagnostics to stdout; take the id line only, so a
        // stray warning never becomes part of the request path.
        return out.lines()
            .map(String::strip)
            .filter(line -> line.startsWith("SEEDED="))
            .map(line -> line.substring("SEEDED=".length()).strip())
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "seedSubscription produced no id, mongosh output:\n" + out));
    }

    /**
     * Seed a subscription and finalize it through the API, so the Cluster and
     * MdbAllocation exist and provisioning has started.
     */
    protected io.tapstate.cloud.sdk.model.SubscriptionResponse seedProvisionedSubscription(
            String orgId, String specKey, String region, String clusterName) throws Exception {
        return seedProvisionedSubscription(orgId, specKey, region, clusterName, null);
    }

    /** Same, pinning the Cluster version instead of resolving the latest ACTIVE one. */
    protected io.tapstate.cloud.sdk.model.SubscriptionResponse seedProvisionedSubscription(
            String orgId, String specKey, String region, String clusterName, String version) throws Exception {
        String subscriptionId = seedSubscription(orgId, specKey);
        subscriptionApi.finalizeProvisioning(orgId, subscriptionId,
            new io.tapstate.cloud.sdk.model.FinalizeProvisioningRequest()
                .region(region).clusterName(clusterName).version(version));
        return subscriptionApi.getSubscription(orgId, subscriptionId).getData();
    }

    // ---- MongoDB cleanup ----

    /**
     * Wipe business collections before each test. Failures propagate: a wipe
     * that silently no-ops (wrong database, lost credentials) makes every test
     * fail with confusing duplicate-data errors instead of the real cause.
     */
    protected static void wipeTestDatabase() {
        // Drop business collections but preserve:
        //   sessions — Spring Session store
        //   regions — seeded once by RegionSeeder on app startup
        //   mdb_clusters + atlas_configs — seeded by MdbClusterSeeder / AtlasConfigSeeder
        //   jwks_keys — seeded by JwksRotationService on startup
        //   cluster_versions + counters — global operator catalog seeded once by
        //       ClusterVersionSeeder (bootstrap 0.6.0) and its sequence counter;
        //       tests release uniquely suffixed versions and restore any mutated
        //       bootstrap state instead of relying on a reseed
        //   cluster_status_report_history — time-series collection, must NOT be dropped
        //       (MongoInitListener only creates it on app startup — a drop would
        //       silently recreate it as a plain collection on next insert)
        //   clusters — its documents are cleared below, not dropped. A forked
        //       application instance starts its own cluster set empty, and
        //       MongoInitListener verifies on startup that the active-name and
        //       active-DNS-label indexes are in place. Dropping the collection
        //       destroys indexes a live instance has already verified, so the
        //       check fails and that instance aborts; keeping the collection also
        //       means the indexes still enforce uniqueness for the tests that
        //       exercise a conflicting name.
        //   system.buckets.* — physical buckets backing time-series collections
        //       (getCollectionNames lists them; their logical name is not enough
        //       to protect them). They must be kept for the same startup-only reason.
        //   system.views — dropping it is rejected while any time-series
        //       collection exists, and listCollections order is not stable, so
        //       never rely on dropping the buckets first.
        //   system.indexes — internal MongoDB
        // After dropping, reset currentAllocations on preserved MdbClusters
        //   so each IT test starts with a clean capacity count.
        long startedAt = System.currentTimeMillis();
        System.out.println("[it-wipe] start database=" + MONGO_DB);
        try {
            String output = mongoshEval(
                "print(' [wipe] database=' + db.getName());" +
                "const keep = new Set(['sessions','regions','mdb_clusters','atlas_configs','jwks_keys'," +
                "'cluster_versions','counters','cluster_status_report_history','clusters','system.indexes']);" +
                // Time-series logical names are discovered at runtime (their physical
                // system.buckets.* collections are filtered by prefix) so the
                // wipe never depends on listCollections ordering.
                "const tsNames = new Set(db.getCollectionInfos({type: 'timeseries'}).map(i => i.name));" +
                "let dropped = 0;" +
                "db.getCollectionNames().filter(c => !keep.has(c) && !tsNames.has(c) && " +
                "!c.startsWith('system.buckets.') && c !== 'system.views')" +
                ".forEach(c => { if (db.getCollection(c).drop()) dropped++; });" +
                "print(' [wipe] droppedCollections=' + dropped);" +
                "db.mdb_clusters.updateMany({}, {$set: {currentAllocations: 0, updatedAt: new Date()}});" +
                "print(' [wipe] historyRemoved=' + db.cluster_status_report_history.deleteMany({}).deletedCount);" +
                "print(' [wipe] clustersRemoved=' + db.clusters.deleteMany({}).deletedCount);" +
                "db.mdb_clusters.find().forEach(c => print(' [wipe] MdbCluster ' + c._id + ' capacity reset ' + c.currentAllocations + '/' + c.maxAllocations));");
            if (output.toLowerCase().contains("error")) {
                throw new IllegalStateException("mongosh wipe reported an error:\n" + output.strip());
            }
            System.out.println("[it-wipe] done database=" + MONGO_DB
                + " elapsedMs=" + (System.currentTimeMillis() - startedAt) + "\n" + output.strip().indent(2));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("IT control-plane wipe failed against " + MONGO_URI, e);
        }
    }

    // ---- Cluster readiness ----

    /**
     * Poll cluster state up to {@code maxAttempts} times, waiting {@code intervalMs} between polls.
     * Throws AssertionError if target state never observed.
     */
    /**
     * Poll the Cluster until it reaches {@code targetState}, a terminal failure state,
     * or the deadline. Fails immediately on terminal failure states so the test does
     * not wait the full deadline and discards the actual provision error message.
     *
     * Terminal failure states that short-circuit (only when waiting for READY; if the
     * target IS one of these we return normally):
     * <ul>
     *   <li>{@code PROVISIONING_FAILED} — CloudFormation / Atlas failure during provisioning</li>
     *   <li>{@code TERMINATED} while provisioning — a concurrent cancel</li>
     * </ul>
     */
    protected void waitForClusterState(String orgId, String clusterId,
                                        String targetState, int maxAttempts, int intervalMs) throws Exception {
        java.util.Set<String> terminalFailures = java.util.Set.of(
            "PROVISIONING_FAILED", "TERMINATED");
        boolean waitingForReady = "READY".equals(targetState);

        for (int i = 0; i < maxAttempts; i++) {
            try {
                var resp = clustersApi.getCluster(orgId, clusterId).getData();
                String state = resp.getState().getValue();
                if (targetState.equals(state)) return;
                if (waitingForReady && terminalFailures.contains(state)) {
                    // Surface the provisioning error code instead of waiting out the deadline.
                    // SDK ClusterResponse has provisionErrorCode (enum) and cleanupErrorCode/DebugDetail.
                    var code = resp.getProvisionErrorCode();
                    String codeStr = code != null ? code.getValue() : "(null)";
                    throw new AssertionError("Cluster " + clusterId + " reached terminal failure state: " + state
                        + " (expected READY). provisionErrorCode=" + codeStr);
                }
            } catch (AssertionError a) {
                throw a; // re-raise our own fail-fast
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
