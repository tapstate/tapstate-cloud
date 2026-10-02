package io.tapstate.cloud.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end Cluster deployment against real AWS infrastructure and the GoDaddy DNS
 * API.
 * <p>
 * Unlike the other IT classes this test creates billable cloud resources: a t3.medium
 * EC2 node with attached security group and disk, plus a public DNS A record, and
 * destroys them at the end. A single full run takes roughly 15–30 minutes.
 * <p>
 * The shared forked application runs in mock mode, so this class launches its own
 * application process on port {@value #REAL_APP_PORT} with real provisioning enabled,
 * and terminates that process in {@link #stopRealApp()}.
 * <p>
 * Prerequisites on the machine running the test:
 * <ul>
 *   <li>Local MongoDB on {@code 127.0.0.1:27017} (BaseIT wipes the IT DB)</li>
 *   <li>AWS credentials available to the default credential chain ({@code ~/.aws/credentials})</li>
 *   <li>GoDaddy credentials exported as {@code GODADDY_API_KEY} / {@code GODADDY_API_SECRET}</li>
 * </ul>
 */
@DisplayName("AwsClusterDeployIT — real AWS EC2 + GoDaddy end-to-end")
class AwsClusterDeployIT extends BaseIT {

    private static final String REGION = "ap-east-1";
    private static final String STACK_NAME_PREFIX = "tapstate-cluster-";
    private static final int REAL_APP_PORT = 8082;

    /** Wait up to 20 minutes for provisioning (60 × 20s). */
    private static final int READY_ATTEMPTS = 60;
    private static final int READY_POLL_MS = 20_000;

    /** Wait up to 10 minutes for termination (60 × 10s). */
    private static final int TERMINATED_ATTEMPTS = 60;
    private static final int TERMINATED_POLL_MS = 10_000;

    private static Process realAppProcess;

    private final ObjectMapper mapper = new ObjectMapper();
    private String orgId;
    private String clusterName;
    private String clusterId;
    private String domain;

    @BeforeAll
    static void startRealApp() throws Exception {
        String moduleDir = System.getProperty("user.dir");
        File jar = new File(moduleDir, "target/tapstate-cloud-control-plane.jar");
        assertTrue(jar.exists(),
            "Packaged application jar must exist: " + jar.getPath());

        String javaBinary = System.getProperty("java.home") + File.separator + "bin"
            + File.separator + "java";
        File appLog = new File(moduleDir, "target/real-app.log");
        ProcessBuilder builder = new ProcessBuilder(
            javaBinary,
            "-Dspring.profiles.active=it",
            "-Dserver.port=" + REAL_APP_PORT,
            "-Dtapstate.cloud.iac.mode=real",
            "-jar", jar.getPath())
            .directory(new File(moduleDir))
            .redirectErrorStream(true)
            .redirectOutput(appLog);
        realAppProcess = builder.start();

        waitForAppStartup();
    }

    @AfterAll
    static void stopRealApp() {
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
    }

    /** Poll the real application health endpoint until it is serving. */
    private static void waitForAppStartup() throws Exception {
        HttpClient probe = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
        URI healthUri = URI.create("http://localhost:" + REAL_APP_PORT + "/health");
        for (int i = 0; i < 60; i++) {
            if (!realAppProcess.isAlive()) {
                fail("Real application process exited during startup; see target/real-app.log");
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
                // Still starting — keep polling.
            }
            Thread.sleep(2_000);
        }
        fail("Real application did not start within 120s; see target/real-app.log");
    }

    @BeforeEach
    void setUpContext() throws Exception {
        signupAndLogin("aws-deploy-" + System.nanoTime() + "@tapstate.io", "StrongPass123!");
        orgId = orgsApi.create(Map.of(
            "name", "AWS-Deploy-IT",
            "slug", "aws-deploy-" + System.nanoTime())).getData().getId();

        clusterName = "aws-it-" + Long.toHexString(System.nanoTime());
        subscriptionApi.createSubscription(orgId, new CreateSubscriptionRequest()
            .region(REGION).clusterName(clusterName).specKey("basic"));

        clusterId = findClusterIdByName(clusterName);
        assertNotNull(clusterId, "Cluster record must exist after subscription");
        domain = clusterId + ".dev.cloud.tapstate.com";
    }

    @Test
    @DisplayName("Provision Cluster on real EC2 with TLS, then terminate and remove DNS")
    void deployAndTerminate() throws Exception {
        // ---------- 1. Provision until READY ----------
        io.tapstate.cloud.sdk.model.ClusterResponse ready = waitForReadyCluster();

        assertNotNull(ready.getEc2PublicIp(), "ec2PublicIp must be recorded");
        assertNotNull(ready.getPublicDns(), "publicDns must be recorded");
        assertEquals(domain, ready.getPublicDns());
        assertNull(ready.getProvisionErrorCode(), "READY cluster must carry no error code");
        String publicIp = ready.getEc2PublicIp();

        // ---------- 2. DNS points at the node ----------
        InetAddress[] addresses = InetAddress.getAllByName(domain);
        boolean resolved = false;
        for (InetAddress address : addresses) {
            if (publicIp.equals(address.getHostAddress())) {
                resolved = true;
            }
        }
        assertTrue(resolved, "Domain " + domain + " must resolve to " + publicIp);

        // ---------- 3. HTTPS endpoint answers through Nginx + TLS ----------
        HttpClient https = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();
        HttpResponse<String> health = https.send(
            HttpRequest.newBuilder(URI.create("https://" + domain + "/health"))
                .timeout(Duration.ofSeconds(20)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, health.statusCode(),
            "HTTPS /health must answer 200, got: " + health.body());

        // ---------- 4. Cancel → TERMINATED ----------
        subscriptionApi.cancelSubscription(orgId);
        waitForClusterState(orgId, clusterId, "TERMINATED",
            TERMINATED_ATTEMPTS, TERMINATED_POLL_MS);

        // ---------- 5. DNS record removed ----------
        assertTrue(waitForRecordGone(),
            "A record for " + domain + " must be removed after termination");

        // ---------- 6. CloudFormation stack removed ----------
        assertTrue(waitForStackGone(),
            "CloudFormation stack for " + clusterId + " must be deleted");
    }

    // ========== Failure safety net ==========

    @org.junit.jupiter.api.AfterEach
    void emergencyCleanup() {
        // Cancel the subscription; if that did not reclaim the resources in time,
        // delete them directly so a Mongo wipe cannot orphan billable resources.
        try {
            subscriptionApi.cancelSubscription(orgId);
        } catch (Exception ignored) {
            // Subscription may already be cancelled — continue to raw cleanup.
        }
        if (clusterId == null) {
            return;
        }

        try {
            Process delete = new ProcessBuilder("aws", "cloudformation", "delete-stack",
                "--stack-name", STACK_NAME_PREFIX + clusterId, "--region", REGION)
                .redirectErrorStream(true).start();
            delete.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // Best effort only.
        }

        String key = System.getenv("GODADDY_API_KEY");
        String secret = System.getenv("GODADDY_API_SECRET");
        if (key != null && secret != null) {
            try {
                Process dnsDelete = new ProcessBuilder("curl", "-sf", "-X", "DELETE",
                    "https://api.godaddy.com/v1/domains/tapstate.com/records/A/"
                        + clusterId + ".dev.cloud",
                    "-H", "authorization: sso-key " + key + ":" + secret)
                    .redirectErrorStream(true).start();
                dnsDelete.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // Best effort only.
            }
        }
    }

    // ========== Helpers ==========

    /** Poll the Cluster until READY; fail fast on PROVISIONING_FAILED. */
    private io.tapstate.cloud.sdk.model.ClusterResponse waitForReadyCluster() throws Exception {
        for (int i = 0; i < READY_ATTEMPTS; i++) {
            io.tapstate.cloud.sdk.model.ClusterResponse response =
                clustersApi.getCluster(orgId, clusterId).getData();
            String state = response.getState().getValue();
            if ("READY".equals(state)) {
                return response;
            }
            if ("PROVISIONING_FAILED".equals(state)) {
                fail("Cluster provisioning failed: " + response.getProvisionErrorCode()
                    + " detail=" + response);
            }
            Thread.sleep(READY_POLL_MS);
        }
        throw new AssertionError("Cluster never reached READY within "
            + (READY_ATTEMPTS * READY_POLL_MS / 1000) + "s");
    }

    /** Poll public DNS until the A record disappears. */
    private boolean waitForRecordGone() throws Exception {
        for (int i = 0; i < 18; i++) {
            Process p = new ProcessBuilder("dig", "@8.8.8.8", "+short", "A", domain)
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

    /** Poll AWS until the stack no longer exists. */
    private boolean waitForStackGone() throws Exception {
        String stackName = STACK_NAME_PREFIX + clusterId;
        for (int i = 0; i < 30; i++) {
            Process p = new ProcessBuilder("aws", "cloudformation", "describe-stacks",
                "--stack-name", stackName, "--region", REGION,
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

    /** Raw GET /clusters, returning the id of a cluster by name. */
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
