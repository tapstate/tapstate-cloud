package io.tapstate.cloud.it;

import io.tapstate.cloud.iac.crypto.SshKeyCrypto;
import io.tapstate.cloud.it.support.ClusterVersionITSupport;
import io.tapstate.cloud.it.support.WildcardTlsFixture;
import io.tapstate.cloud.it.support.WildcardTlsFixture.SeededCertificate;
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

    // Region seeded in the IT database; it also has a default VPC for CloudFormation.
    private static final String REGION = "ap-southeast-1";
    private static final String STACK_NAME_PREFIX = "tapstate-cluster-";
    private static final int REAL_APP_PORT = 8082;

    /**
     * Outbound proxy used when direct HTTPS to GoDaddy is blocked. Override with
     * {@code -Diac.proxyHost=… -Diac.proxyPort=…}; set host to {@code none} to disable.
     */
    private static final String PROXY_HOST =
        System.getProperty("iac.proxyHost", "127.0.0.1");
    private static final String PROXY_PORT =
        System.getProperty("iac.proxyPort", "1235");

    /** Wait up to 20 minutes for provisioning (60 × 20s). */
    private static final int READY_ATTEMPTS = 60;
    private static final int READY_POLL_MS = 20_000;

    /** Wait up to 10 minutes for termination (60 × 10s). */
    private static final int TERMINATED_ATTEMPTS = 60;
    private static final int TERMINATED_POLL_MS = 10_000;

    private static Process realAppProcess;

    /**
     * The real app's server-side health check validates HTTPS with its JVM
     * trust store, so it must trust the self-signed IT wildcard certificate.
     */
    private static File tlsTrustStore;
    private static SeededCertificate tlsFixture;

    private String orgId;
    private String subscriptionId;
    private String clusterName;
    private String clusterId;
    private String domain;

    @BeforeAll
    static void startRealApp() throws Exception {
        String moduleDir = System.getProperty("user.dir");
        File jar = new File(moduleDir, "target/tapstate-cloud-control-plane.jar");
        assertTrue(jar.exists(),
            "Packaged application jar must exist: " + jar.getPath());

        // One certificate for the whole class: the forked app pins it as a
        // trust anchor; each test re-seeds the same material after the wipe.
        tlsFixture = WildcardTlsFixture.generate("*.dev.cloud.tapstate.com");
        tlsTrustStore = new File(moduleDir, "target/it-wildcard-truststore.p12");
        tlsFixture.writeJvmTrustStore(tlsTrustStore);

        String javaBinary = System.getProperty("java.home") + File.separator + "bin"
            + File.separator + "java";

        java.util.List<String> command = new java.util.ArrayList<>(java.util.List.of(
            javaBinary,
            "-Dspring.profiles.active=it",
            "-Dserver.port=" + REAL_APP_PORT,
            "-Dtapstate.cloud.iac.mode=real",
            "-Djavax.net.ssl.trustStore=" + tlsTrustStore.getPath(),
            "-Djavax.net.ssl.trustStorePassword=changeit"));
        addProxyArgs(command);
        command.addAll(java.util.List.of("-jar", jar.getPath()));

        File appLog = new File(moduleDir, "target/real-app.log");
        ProcessBuilder builder = new ProcessBuilder(command)
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

    /**
     * Append JVM proxy arguments when a proxy is configured. localhost bypasses
     * the proxy; checkip.amazonaws.com must go through it so the discovered SSH
     * source CIDR matches the proxy egress used to tunnel the SSH connections.
     */
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

        // Provisioning resolves the wildcard certificate before any cloud
        // resource is created, so the document must exist before subscribing.
        tlsFixture.seed(MONGO_URI);

        // Pin the bootstrap version explicitly: the catalog survives IT wipes and
        // other IT classes release newer (nano-suffixed) versions into the same
        // database, so an unspecified subscription would resolve to "latest"
        // nondeterministically. The real lane verifies the 0.6.0 image end to end.
        new ClusterVersionITSupport(httpClient, BASE_URL).ensureBootstrapActive();

        clusterName = "aws-it-" + Long.toHexString(System.nanoTime());
        var created = subscriptionApi.createSubscription(orgId, new CreateSubscriptionRequest()
            .region(REGION).clusterName(clusterName).specKey("basic")
            .version(ClusterVersionITSupport.BOOTSTRAP_VERSION)).getData();
        subscriptionId = created.getId();

        clusterId = created.getClusterId();
        assertNotNull(clusterId, "Cluster record must exist after subscription");
        domain = io.tapstate.cloud.cluster.DnsLabels.normalize(clusterName)
            + ".dev.cloud.tapstate.com";
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
        assertEquals(ClusterVersionITSupport.BOOTSTRAP_VERSION, ready.getClusterVersion(),
            "the READY Cluster must pin the version requested at subscription time");
        String publicIp = ready.getEc2PublicIp();

        // ---------- 1a. Pinned version renders and runs the matching image tag ----------
        verifyRuntimeImageOnNode(publicIp,
            "ghcr.io/tapstate/tapstate-cloud:" + ClusterVersionITSupport.BOOTSTRAP_VERSION);

        // ---------- 1b. Install deployment progress fully recorded ----------
        io.tapstate.cloud.sdk.model.DeploymentProgressResponse installProgress =
            progressApi.getProgress(orgId, subscriptionId, "INSTALL").getData();
        assertEquals("SUCCEEDED", installProgress.getOverallStatus());
        assertEquals(100, installProgress.getPercent());
        assertEquals(14, installProgress.getOperations().size());
        assertTrue(installProgress.getOperations().stream()
            .allMatch(op -> "SUCCEEDED".equals(op.getStatus())));
        assertTrue(installProgress.getOperations().stream()
            .allMatch(op -> op.getMessage() != null && op.getFinishedAt() != null));
        assertNotNull(installProgress.getStartedAt());
        assertNotNull(installProgress.getFinishedAt());

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
        HttpClient.Builder httpsBuilder = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .sslContext(tlsFixture.sslContext())
            .connectTimeout(Duration.ofSeconds(15));
        if (!"none".equalsIgnoreCase(PROXY_HOST)) {
            httpsBuilder.proxy(java.net.ProxySelector.of(
                new java.net.InetSocketAddress(PROXY_HOST, Integer.parseInt(PROXY_PORT))));
        }
        HttpResponse<String> health = httpsBuilder.build().send(
            HttpRequest.newBuilder(URI.create("https://" + domain + "/healthz"))
                .timeout(Duration.ofSeconds(20)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, health.statusCode(),
            "HTTPS /healthz must answer 200, got: " + health.body());

        // ---------- 4. Cancel → TERMINATED ----------
        subscriptionApi.cancelSubscription(orgId, subscriptionId);
        waitForClusterState(orgId, clusterId, "TERMINATED",
            TERMINATED_ATTEMPTS, TERMINATED_POLL_MS);

        // ---------- 5. DNS record removed ----------
        assertTrue(waitForRecordGone(),
            "A record for " + domain + " must be removed after termination");

        // ---------- 6. CloudFormation stack removed ----------
        assertTrue(waitForStackGone(),
            "CloudFormation stack for " + clusterId + " must be deleted");

        // ---------- 7. Uninstall deployment progress fully recorded ----------
        io.tapstate.cloud.sdk.model.DeploymentProgressResponse uninstallProgress =
            progressApi.getProgress(orgId, subscriptionId, "UNINSTALL").getData();
        assertEquals("SUCCEEDED", uninstallProgress.getOverallStatus());
        assertEquals(100, uninstallProgress.getPercent());
        assertEquals(4, uninstallProgress.getOperations().size());
        assertTrue(uninstallProgress.getOperations().stream()
            .allMatch(op -> "SUCCEEDED".equals(op.getStatus())));
        assertNotNull(uninstallProgress.getFinishedAt());
    }

    // ========== Failure safety net ==========

    @org.junit.jupiter.api.AfterEach
    void emergencyCleanup() {
        // Cancel the subscription; if that did not reclaim the resources in time,
        // delete them directly so a Mongo wipe cannot orphan billable resources.
        try {
            subscriptionApi.cancelSubscription(orgId, subscriptionId);
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
        if (key != null) {
            try {
                String auth = key.startsWith("gd_pat_")
                    ? "Bearer " + key
                    : "sso-key " + key + ":" + secret;
                java.util.List<String> dnsDeleteCmd = new java.util.ArrayList<>();
                dnsDeleteCmd.add("curl");
                dnsDeleteCmd.add("-sf");
                if (!"none".equalsIgnoreCase(PROXY_HOST)) {
                    dnsDeleteCmd.add("-x");
                    dnsDeleteCmd.add("http://" + PROXY_HOST + ":" + PROXY_PORT);
                }
                dnsDeleteCmd.add("-X");
                dnsDeleteCmd.add("DELETE");
                dnsDeleteCmd.add("https://api.godaddy.com/v1/domains/tapstate.com/records/A/"
                    + clusterId + ".dev.cloud");
                dnsDeleteCmd.add("-H");
                dnsDeleteCmd.add("authorization: " + auth);
                Process dnsDelete = new ProcessBuilder(dnsDeleteCmd)
                    .redirectErrorStream(true).start();
                dnsDelete.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // Best effort only.
            }
        }
    }

    // ========== Helpers ==========

    /** Fixed it-profile crypto master key (base64 of bytes 0x00..0x1f). */
    private static final String IT_CRYPTO_MASTER_KEY =
        "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

    /**
     * SSH into the provisioned node and assert the rendered compose file and the
     * running runtime container both reference the image built from the Cluster's
     * pinned version (no legacy hardcoded tag).
     */
    private void verifyRuntimeImageOnNode(String publicIp, String expectedImage) throws Exception {
        String out = mongoshEval(
            "print('DOCKEY=' + db.clusters.findOne({_id: ObjectId('" + clusterId
                + "')}, {sshPrivateKeyEnc: 1}).sshPrivateKeyEnc)");
        String encryptedKey = out.lines()
            .filter(l -> l.startsWith("DOCKEY="))
            .map(l -> l.substring("DOCKEY=".length()).trim())
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "encrypted SSH key not found on Cluster document:\n" + out));
        String pem = new SshKeyCrypto().decrypt(encryptedKey, IT_CRYPTO_MASTER_KEY);

        File keyFile = File.createTempFile("aws-it-key-", ".pem");
        keyFile.deleteOnExit();
        java.nio.file.Files.writeString(keyFile.toPath(), pem);
        assertTrue(keyFile.setReadable(false, false) && keyFile.setReadable(true, true),
            "temp SSH key permissions must be restricted to the runner user");

        java.util.List<String> command = java.util.List.of(
            "ssh", "-i", keyFile.getPath(),
            "-o", "StrictHostKeyChecking=no",
            "-o", "UserKnownHostsFile=/dev/null",
            "-o", "ConnectTimeout=20",
            "-o", "BatchMode=yes",
            "ubuntu@" + publicIp,
            "grep -m1 'image:' /opt/tapstate-runtime/docker-compose.yml; "
                + "sudo docker inspect -f '{{.Config.Image}}' tapstate-runtime");
        Process ssh = new ProcessBuilder(command).redirectErrorStream(true).start();
        String sshOut = new String(ssh.getInputStream().readAllBytes());
        assertTrue(ssh.waitFor(60, java.util.concurrent.TimeUnit.SECONDS),
            "SSH verification timed out; output:\n" + sshOut);
        assertEquals(0, ssh.exitValue(), "SSH verification command failed, output:\n" + sshOut);
        assertTrue(sshOut.contains(expectedImage),
            "node must render and run " + expectedImage + ", SSH output:\n" + sshOut);
    }

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
}
