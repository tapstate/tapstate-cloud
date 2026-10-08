package io.tapstate.cloud.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tapstate.cloud.atlas.AtlasApiClient;
import io.tapstate.cloud.sdk.model.CreateSubscriptionRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Full lifecycle against REAL AWS + REAL Atlas, joining the two automated flows:
 * <pre>
 * subscribe
 *   → EC2 + CloudFormation + DNS provisioned (real IaC)
 *   → Atlas database + dbUser provisioned (real Admin API), slot reserved
 *   → compose file rendered with the per-allocation TAPSTATE_CLOUD_ATLAS_URI
 *   → runtime starts, HTTPS endpoint healthy
 * cancel
 *   → databases dropped (dbUser auth), dbUser deleted, slot released
 *   → CloudFormation stack deleted, DNS A record removed
 * </pre>
 * A single run creates billable resources and takes roughly 15–30 minutes. It
 * launches its own application process (port {@value #REAL_APP_PORT}) with
 * {@code iac.mode=real} and {@code mdb.atlas.mock=false}, repointing the
 * preserved IT seed documents at the real Atlas project, and restores the mock
 * seeds in {@link #restoreSeedsAndStopApp()}.
 * <p>
 * Credential prerequisites — the test is SKIPPED (not failed) when any is absent:
 * <ul>
 *   <li>{@code ~/.aws/credentials} or {@code AWS_ACCESS_KEY_ID} /
 *       {@code AWS_SECRET_ACCESS_KEY} (CloudFormation/EC2 + GoDaddy-based DNS)</li>
 *   <li>{@code GODADDY_API_KEY} (a {@code gd_pat_} PAT needs no secret; a classic
 *       sso-key also needs {@code GODADDY_API_SECRET})</li>
 *   <li>{@code TAPSTATE_ATLAS_PUBLIC_KEY} / {@code TAPSTATE_ATLAS_PRIVATE_KEY}</li>
 *   <li>{@code TAPSTATE_ATLAS_PROJECT_ID}, {@code TAPSTATE_ATLAS_CLUSTER_NAME},
 *       {@code TAPSTATE_ATLAS_CONNECTION_STRING} (Atlas SRV URI — a full URI
 *       embedding admin credentials is accepted and sanitized to host-only)</li>
 * </ul>
 */
@DisplayName("AwsAtlasFullLifecycleIT — real EC2 + real Atlas joined lifecycle")
class AwsAtlasFullLifecycleIT extends BaseIT {

    private static final String REGION = "ap-southeast-1";
    private static final String STACK_NAME_PREFIX = "tapstate-cluster-";
    private static final int REAL_APP_PORT = 8083;

    private static final String PROXY_HOST = System.getProperty("iac.proxyHost", "127.0.0.1");
    private static final String PROXY_PORT = System.getProperty("iac.proxyPort", "1235");

    private static final int READY_ATTEMPTS = 60;
    private static final int READY_POLL_MS = 20_000;
    private static final int TERMINATED_ATTEMPTS = 60;
    private static final int TERMINATED_POLL_MS = 10_000;
    private static final int ATLAS_POLL_ATTEMPTS = 30;

    private static Process realAppProcess;
    private static boolean seedsReplaced;

    private final ObjectMapper mapper = new ObjectMapper();
    private String orgId;
    private String clusterName;
    private String clusterId;
    private String domain;

    @BeforeAll
    static void startRealApp() throws Exception {
        // CI injects AWS credentials as environment variables; local runs use
        // the ~/.aws/credentials file.
        boolean awsCredentials = new File(System.getProperty("user.home"), ".aws/credentials").isFile()
            || (env("AWS_ACCESS_KEY_ID") != null && env("AWS_SECRET_ACCESS_KEY") != null);
        assumeTrue(awsCredentials,
            "AWS credentials missing (~/.aws/credentials or AWS_ACCESS_KEY_ID/AWS_SECRET_ACCESS_KEY) "
                + "— skipping real AWS+Atlas lifecycle IT");
        // A GoDaddy Personal Access Token (gd_pat_…) authenticates as Bearer
        // and needs no secret; the classic sso-key form needs both key+secret.
        String godaddyKey = env("GODADDY_API_KEY");
        assumeTrue(godaddyKey != null && !godaddyKey.isBlank(),
            "GODADDY_API_KEY missing — skipping real AWS+Atlas lifecycle IT");
        assumeTrue(godaddyKey.startsWith("gd_pat_") || env("GODADDY_API_SECRET") != null,
            "GODADDY_API_SECRET missing for classic sso-key — skipping real AWS+Atlas lifecycle IT");
        assumeTrue(env("TAPSTATE_ATLAS_PUBLIC_KEY") != null && env("TAPSTATE_ATLAS_PRIVATE_KEY") != null,
            "Atlas API keys missing — skipping real AWS+Atlas lifecycle IT");
        assumeTrue(env("TAPSTATE_ATLAS_PROJECT_ID") != null
                && env("TAPSTATE_ATLAS_CLUSTER_NAME") != null
                && env("TAPSTATE_ATLAS_CONNECTION_STRING") != null,
            "Atlas project/cluster/connection-string env missing — skipping real AWS+Atlas lifecycle IT");

        pointSeedsAtRealAtlas();
        seedsReplaced = true;

        File moduleDir = new File(System.getProperty("user.dir"));
        File jar = new File(moduleDir, "target/tapstate-cloud-control-plane.jar");
        assertTrue(jar.exists(), "Packaged application jar must exist: " + jar.getPath());

        String javaBinary = System.getProperty("java.home") + File.separator + "bin"
            + File.separator + "java";
        java.util.List<String> command = new java.util.ArrayList<>(java.util.List.of(
            javaBinary,
            "-Dspring.profiles.active=it",
            "-Dserver.port=" + REAL_APP_PORT,
            "-Dtapstate.cloud.iac.mode=real",
            "-Dtapstate.cloud.mdb.atlas.mock=false"));
        addProxyArgs(command);
        command.addAll(java.util.List.of("-jar", jar.getPath()));

        File appLog = new File(moduleDir, "target/real-app-atlas.log");
        realAppProcess = new ProcessBuilder(command)
            .directory(moduleDir)
            .redirectErrorStream(true)
            .redirectOutput(appLog)
            .start();

        waitForAppStartup();
    }

    @AfterAll
    static void restoreSeedsAndStopApp() {
        if (realAppProcess != null && realAppProcess.isAlive()) {
            realAppProcess.destroy();
            try {
                if (!realAppProcess.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) {
                    realAppProcess.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                realAppProcess.destroyForcibly();
            }
        }
        if (seedsReplaced) {
            restoreMockSeeds();
        }
    }

    @BeforeEach
    void setUpContext() throws Exception {
        signupAndLogin("aws-atlas-" + System.nanoTime() + "@tapstate.io", "StrongPass123!");
        orgId = orgsApi.create(Map.of(
            "name", "AWS-Atlas-IT",
            "slug", "aws-atlas-" + System.nanoTime())).getData().getId();

        clusterName = "aws-atlas-" + Long.toHexString(System.nanoTime());
        subscriptionApi.createSubscription(orgId, new CreateSubscriptionRequest()
            .region(REGION).clusterName(clusterName).specKey("basic"));

        clusterId = findClusterIdByName(clusterName);
        assertNotNull(clusterId, "Cluster record must exist after subscription");
        domain = clusterId + ".dev.cloud.tapstate.com";
    }

    @Test
    @DisplayName("Real EC2 + Atlas: provision with injected Atlas URI, then release everything")
    void provisionServeAndRelease() throws Exception {
        // ---------- 1. Cluster + allocation converge to READY ----------
        waitForClusterState(orgId, clusterId, "READY", READY_ATTEMPTS, READY_POLL_MS);

        JsonNode allocation = waitForAllocationState("READY");
        String dbUsername = allocation.path("dbUsername").asText();
        String mdbClusterId = allocation.path("mdbClusterId").asText();
        JsonNode mdbCluster = mongoOne(
            "print(JSON.stringify(db.mdb_clusters.findOne({_id: ObjectId('" + mdbClusterId + "')})))");
        assertEquals(1, mdbCluster.path("currentAllocations").asInt(),
            "exactly one Atlas slot must be reserved");

        JsonNode cluster = rawCluster();
        String publicIp = cluster.path("ec2PublicIp").asText();
        assertEquals(domain, cluster.path("publicDns").asText());

        // ---------- 2. DNS resolves and the runtime answers over HTTPS ----------
        boolean resolved = false;
        for (InetAddress address : InetAddress.getAllByName(domain)) {
            if (publicIp.equals(address.getHostAddress())) {
                resolved = true;
            }
        }
        assertTrue(resolved, "Domain " + domain + " must resolve to " + publicIp);

        HttpClient httpsClient = buildHttpsClient();
        HttpResponse<String> health = httpsClient.send(
            HttpRequest.newBuilder(URI.create("https://" + domain + "/healthz"))
                .timeout(Duration.ofSeconds(20)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, health.statusCode(),
            "HTTPS /healthz must answer 200, got: " + health.body());

        // ---------- 3. The credentials injected into the compose URI authenticate
        // against Atlas from outside the node — the same URI the runtime uses. -----
        String runtimeUri = buildRuntimeUri(allocation, mdbCluster);
        assertEquals("1", mongoshPing(runtimeUri),
            "allocation dbUser must authenticate against Atlas with the runtime URI");

        // ---------- 4. Cancel cascades to AWS and Atlas ----------
        subscriptionApi.cancelSubscription(orgId);
        waitForClusterState(orgId, clusterId, "TERMINATED",
            TERMINATED_ATTEMPTS, TERMINATED_POLL_MS);
        assertEquals("DELETED", waitForAllocationState("DELETED").path("status").asText());

        assertTrue(waitForRecordGone(), "DNS A record for " + domain + " must be removed");
        assertTrue(waitForStackGone(), "CloudFormation stack tapstate-cluster-" + clusterId
            + " must be deleted");
        assertTrue(waitForAtlasUserGone(dbUsername),
            "Atlas dbUser " + dbUsername + " must be deleted");
        assertEquals(0, mongoOne("const c = db.mdb_clusters.findOne({_id: ObjectId('"
            + mdbClusterId + "')}); print(JSON.stringify(c.currentAllocations))").asInt(),
            "Atlas slot must be released");
    }

    // ========== Failure safety net ==========

    @org.junit.jupiter.api.AfterEach
    void emergencyCleanup() {
        try {
            subscriptionApi.cancelSubscription(orgId);
        } catch (Exception ignored) {
            // Subscription may already be cancelled.
        }
        if (clusterId == null) {
            return;
        }
        runQuietly(new ProcessBuilder("aws", "cloudformation", "delete-stack",
            "--stack-name", STACK_NAME_PREFIX + clusterId, "--region", REGION)
            .redirectErrorStream(true));
        if (env("GODADDY_API_KEY") != null) {
            String key = env("GODADDY_API_KEY");
            String secret = env("GODADDY_API_SECRET");
            String auth = key.startsWith("gd_pat_")
                ? "Bearer " + key
                : "sso-key " + key + ":" + secret;
            java.util.List<String> cmd = new java.util.ArrayList<>();
            cmd.add("curl");
            cmd.add("-sf");
            if (!"none".equalsIgnoreCase(PROXY_HOST)) {
                cmd.add("-x");
                cmd.add("http://" + PROXY_HOST + ":" + PROXY_PORT);
            }
            cmd.add("-X");
            cmd.add("DELETE");
            cmd.add("https://api.godaddy.com/v1/domains/tapstate.com/records/A/"
                + clusterId + ".dev.cloud");
            cmd.add("-H");
            cmd.add("authorization: " + auth);
            runQuietly(new ProcessBuilder(cmd).redirectErrorStream(true));
        }
        try {
            JsonNode allocation = mongoOne(
                "print(JSON.stringify(db.mdb_allocations.findOne({clusterId: ObjectId('"
                    + clusterId + "')})))");
            if (!allocation.isNull() && allocation.path("dbUsername").isTextual()) {
                new AtlasApiClient(env("TAPSTATE_ATLAS_PUBLIC_KEY"),
                    env("TAPSTATE_ATLAS_PRIVATE_KEY"))
                    .dropDatabaseUser(env("TAPSTATE_ATLAS_PROJECT_ID"),
                        allocation.path("dbUsername").asText());
            }
        } catch (Exception ignored) {
            // Best effort only.
        }
    }

    // ========== Helpers ==========

    private static String env(String name) {
        return System.getenv(name);
    }

    private static void addProxyArgs(java.util.List<String> command) {
        if ("none".equalsIgnoreCase(PROXY_HOST)) {
            return;
        }
        command.add("-Dhttp.proxyHost=" + PROXY_HOST);
        command.add("-Dhttp.proxyPort=" + PROXY_PORT);
        command.add("-Dhttps.proxyHost=" + PROXY_HOST);
        command.add("-Dhttps.proxyPort=" + PROXY_PORT);
        command.add("-Dhttp.nonProxyHosts=localhost|127.0.0.1");
    }

    private static void waitForAppStartup() throws Exception {
        HttpClient probe = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        URI healthUri = URI.create("http://localhost:" + REAL_APP_PORT + "/health");
        for (int i = 0; i < 60; i++) {
            if (!realAppProcess.isAlive()) {
                throw new IllegalStateException(
                    "Real application process exited during startup; see target/real-app-atlas.log");
            }
            try {
                HttpResponse<String> response = probe.send(
                    HttpRequest.newBuilder(healthUri).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    BASE_URL = "http://localhost:" + REAL_APP_PORT;
                    return;
                }
            } catch (Exception ignored) {
                // Still starting.
            }
            Thread.sleep(2_000);
        }
        throw new IllegalStateException(
            "Real application did not start within 120s; see target/real-app-atlas.log");
    }

    /**
     * Repoint the IT-preserved seed documents at the real Atlas project. The
     * seeders skip when documents already exist, so the real app reads these.
     * The Atlas connection string is sanitized to {@code scheme://hostList}:
     * the CI secret carries a full Atlas URI with embedded admin credentials,
     * which must never land in the seeded document.
     */
    private static void pointSeedsAtRealAtlas() throws Exception {
        String hostOnlyUri = hostOnlyAtlasUri(env("TAPSTATE_ATLAS_CONNECTION_STRING"));
        String eval =
            "db.atlas_configs.updateMany({status: 'Activated'}, {$set: {"
                + "publicKey: '" + env("TAPSTATE_ATLAS_PUBLIC_KEY") + "',"
                + "privateKey: '" + env("TAPSTATE_ATLAS_PRIVATE_KEY") + "',"
                + "domain: 'cloud.mongodb.com', status: 'Activated'}});"
                + "db.mdb_clusters.updateMany({}, {$set: {"
                + "projectId: '" + env("TAPSTATE_ATLAS_PROJECT_ID") + "',"
                + "name: '" + env("TAPSTATE_ATLAS_CLUSTER_NAME") + "',"
                + "region: '" + REGION + "',"
                + "connectionString: '" + hostOnlyUri + "',"
                + "status: 'ACTIVE', currentAllocations: 0, maxAllocations: 100},"
                + "$unset: {cluster: ''}});"
                + "print('seeds repointed')";
        assertEquals("seeds repointed", mongoshEval(eval).trim());
    }

    /** Restore the deterministic dev/it mock seeds so other ITs stay cloud-free. */
    private static void restoreMockSeeds() {
        try {
            String eval =
                "db.atlas_configs.updateMany({status: 'Activated'}, {$set: {"
                    + "publicKey: 'dev-mock-public-key',"
                    + "privateKey: 'dev-mock-private-key-NEVER-USED-IN-IT',"
                    + "domain: 'cloud.mongodb.com', orgId: 'dev-org-id', status: 'Activated'}});"
                    + "db.mdb_clusters.updateMany({}, {$set: {"
                    + "projectId: 'tapstate-dev-project',"
                    + "name: 'tapstate-shared-ap-southeast-1',"
                    + "region: 'ap-southeast-1',"
                    + "connectionString: 'mongodb+srv://tapstate-shared.mongodb.net',"
                    + "status: 'ACTIVE', currentAllocations: 0, maxAllocations: 100}});"
                    + "print('seeds restored')";
            mongoshEval(eval);
        } catch (Exception e) {
            System.err.println("[AwsAtlasFullLifecycleIT] failed to restore mock seeds: "
                + e.getMessage());
        }
    }

    private static void runQuietly(ProcessBuilder builder) {
        try {
            Process p = builder.start();
            p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // Best effort only.
        }
    }

    private JsonNode mongoOne(String eval) throws Exception {
        String out = mongoshEval(eval).trim();
        return mapper.readTree(out.isBlank() ? "null" : out);
    }

    private JsonNode rawCluster() throws Exception {
        return mongoOne("print(JSON.stringify(db.clusters.findOne({_id: ObjectId('"
            + clusterId + "')})))");
    }

    private JsonNode waitForAllocationState(String target) throws Exception {
        for (int i = 0; i < READY_ATTEMPTS; i++) {
            JsonNode allocation = mongoOne(
                "print(JSON.stringify(db.mdb_allocations.findOne({clusterId: ObjectId('"
                    + clusterId + "')})))");
            if (target.equals(allocation.path("status").asText())) {
                return allocation;
            }
            Thread.sleep(READY_POLL_MS);
        }
        throw new AssertionError("MdbAllocation never reached " + target);
    }

    /**
     * Reduce any Atlas connection string — clean or a full Atlas URI embedding
     * admin credentials and a default database — to {@code scheme://hostList}.
     * Per-allocation credentials are inserted only by the application itself.
     */
    static String hostOnlyAtlasUri(String seed) {
        int schemeEnd = seed.indexOf("://");
        if (schemeEnd < 0) {
            throw new IllegalArgumentException(
                "Invalid Atlas connection string (no scheme): " + seed);
        }
        String scheme = seed.substring(0, schemeEnd + 3);
        String rest = seed.substring(schemeEnd + 3);

        int cut = -1;
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '?' || c == '#') {
                cut = i;
                break;
            }
        }
        String authority = cut >= 0 ? rest.substring(0, cut) : rest;

        int at = authority.lastIndexOf('@');
        String hostPart = at >= 0 ? authority.substring(at + 1) : authority;

        int slash = hostPart.indexOf('/');
        if (slash >= 0) {
            hostPart = hostPart.substring(0, slash);
        }
        if (hostPart.isBlank()) {
            throw new IllegalArgumentException(
                "Invalid Atlas connection string (no host): " + seed);
        }
        return scheme + hostPart;
    }

    /** Reconstruct the same per-allocation URI the node compose file receives. */
    private String buildRuntimeUri(JsonNode allocation, JsonNode mdbCluster) {
        String hostOnly = hostOnlyAtlasUri(mdbCluster.path("connectionString").asText());
        int schemeEnd = hostOnly.indexOf("://") + 3;
        return hostOnly.substring(0, schemeEnd)
            + allocation.path("dbUsername").asText() + ":"
            + allocation.path("dbPassword").asText() + "@"
            + hostOnly.substring(schemeEnd) + "/"
            + allocation.path("databaseName").asText() + "?authSource=admin";
    }

    /** Authenticate against Atlas exactly as the Cluster runtime would. */
    private String mongoshPing(String runtimeUri) throws Exception {
        String out = mongoshUri(runtimeUri, "print(db.runCommand({ping: 1}).ok)").trim();
        return out.lines().reduce((first, second) -> second).orElse("").trim();
    }

    private HttpClient buildHttpsClient() {
        HttpClient.Builder builder = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15));
        if (!"none".equalsIgnoreCase(PROXY_HOST)) {
            builder.proxy(java.net.ProxySelector.of(
                new java.net.InetSocketAddress(PROXY_HOST, Integer.parseInt(PROXY_PORT))));
        }
        return builder.build();
    }

    private boolean waitForRecordGone() throws Exception {
        // Query the zone's AUTHORITATIVE nameservers. A public recursive
        // resolver (8.8.8.8) keeps a positive answer cached for the record TTL
        // (600s here), so a bounded poll right after the provider delete can
        // observe the stale cached record even though the record is already
        // gone at the source; the authoritative answer reflects the delete
        // immediately without waiting out the TTL.
        String nameserver = resolveAuthoritativeNameserver(domain);
        for (int i = 0; i < 18; i++) {
            Process p = new ProcessBuilder("dig", "@" + nameserver, "+short", "+norecurse", "A", domain)
                .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            if (out.isEmpty()) {
                return true;
            }
            Thread.sleep(10_000);
        }
        return false;
    }

    /**
     * Resolve an authoritative nameserver for the record's zone by walking
     * labels upward until a zone with an NS delegation is found. Falls back to
     * 8.8.8.8 (recursive) when NS discovery fails locally.
     */
    private static String resolveAuthoritativeNameserver(String fqdn) throws Exception {
        String zone = fqdn;
        int dot = zone.indexOf('.');
        if (dot >= 0) {
            zone = zone.substring(dot + 1); // strip the host label first
        }
        while (zone.contains(".")) {
            Process p = new ProcessBuilder("dig", "+short", "NS", zone)
                .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            for (String line : out.split("\\R")) {
                String ns = line.trim();
                if (!ns.isEmpty()) {
                    return ns.endsWith(".") ? ns.substring(0, ns.length() - 1) : ns;
                }
            }
            dot = zone.indexOf('.');
            if (dot < 0) {
                break;
            }
            zone = zone.substring(dot + 1);
        }
        return "8.8.8.8";
    }

    private boolean waitForStackGone() throws Exception {
        for (int i = 0; i < 30; i++) {
            Process p = new ProcessBuilder("aws", "cloudformation", "describe-stacks",
                "--stack-name", STACK_NAME_PREFIX + clusterId, "--region", REGION,
                "--query", "Stacks[0].StackStatus", "--output", "text")
                .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            if (out.contains("does not exist")) {
                return true;
            }
            Thread.sleep(10_000);
        }
        return false;
    }

    private boolean waitForAtlasUserGone(String dbUsername) throws Exception {
        AtlasApiClient atlas = new AtlasApiClient(
            env("TAPSTATE_ATLAS_PUBLIC_KEY"), env("TAPSTATE_ATLAS_PRIVATE_KEY"));
        for (int i = 0; i < ATLAS_POLL_ATTEMPTS; i++) {
            JsonNode user = atlas.queryDatabaseUser(env("TAPSTATE_ATLAS_PROJECT_ID"), dbUsername);
            if (user == null || user.isNull()) {
                return true;
            }
            Thread.sleep(10_000);
        }
        return false;
    }

    private String findClusterIdByName(String name) throws Exception {
        HttpResponse<String> resp = get("/v1/api/orgs/" + orgId + "/clusters",
            Map.of("Cookie", "TAPSTATE_CLOUD_SESSION=" + sessionCookieValue));
        JsonNode clusters = mapper.readTree(resp.body()).path("data");
        if (!clusters.isArray()) {
            return null;
        }
        for (JsonNode cluster : clusters) {
            if (name.equals(cluster.path("name").asText())) {
                return cluster.path("id").asText();
            }
        }
        return null;
    }
}
