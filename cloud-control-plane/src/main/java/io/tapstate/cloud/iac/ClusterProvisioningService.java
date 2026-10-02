package io.tapstate.cloud.iac;

import io.tapstate.cloud.cluster.Cluster;
import io.tapstate.cloud.cluster.ClusterRepository;
import io.tapstate.cloud.cluster.ProvisionErrorCode;
import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.iac.config.ClusterRuntimeConfigs;
import io.tapstate.cloud.iac.crypto.SshKeyCrypto;
import io.tapstate.cloud.iac.model.IngressRule;
import io.tapstate.cloud.iac.model.NodeInstance;
import io.tapstate.cloud.iac.model.NodeSpec;
import io.tapstate.cloud.iac.ssh.CommandResult;
import io.tapstate.cloud.iac.ssh.SshClient;
import io.tapstate.cloud.iac.ssh.SshKeyPairGenerator;
import io.tapstate.cloud.iac.ssh.SshSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.URI;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates the full provision and termination of a Cluster node.
 * <p>
 * The methods are blocking and deliberately sequential; callers run them on a
 * virtual thread. Every externally created resource is recorded on the Cluster
 * document as soon as it exists, and a failure triggers reverse-order compensation
 * of the resources already created, so a failed attempt leaves no orphans.
 */
@Service
@Slf4j
public class ClusterProvisioningService {

    private final ClusterRepository clusterRepository;
    private final IacProperties properties;
    private final ComputeInfrastructure compute;
    private final DnsInfrastructure dns;
    private final SshClient sshClient;
    private final SshKeyPairGenerator keyPairGenerator;
    private final SshKeyCrypto keyCrypto;
    private final ClusterRuntimeConfigs configs;

    // ---- Timing budget ----
    private static final Duration STACK_TIMEOUT = Duration.ofMinutes(15);
    private static final Duration SSH_WAIT_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration DNS_WAIT_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration HEALTH_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration COMPOSE_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration CERTBOT_TIMEOUT = Duration.ofMinutes(2);
    private static final int POLL_INTERVAL_MS = 10_000;
    private static final int SSH_SESSION_TIMEOUT_MS = 60_000;
    private static final int HEALTH_INTERVAL_MS = 20_000;
    private static final int SSH_PORT = 22;
    private static final String SSH_USER = "ubuntu";
    private static final String CERTBOT_EMAIL = "ops@tapstate.com";

    public ClusterProvisioningService(
        ClusterRepository clusterRepository,
        IacProperties properties,
        Map<String, ComputeInfrastructure> computeBeans,
        DnsInfrastructure dns,
        SshClient sshClient,
        SshKeyPairGenerator keyPairGenerator,
        SshKeyCrypto keyCrypto,
        ClusterRuntimeConfigs configs) {
        this.clusterRepository = clusterRepository;
        this.properties = properties;
        this.dns = dns;
        this.sshClient = sshClient;
        this.keyPairGenerator = keyPairGenerator;
        this.keyCrypto = keyCrypto;
        this.configs = configs;

        CloudProvider provider = CloudProvider.fromId(properties.getProvider());
        ComputeInfrastructure selected = computeBeans.values().stream()
            .filter(candidate -> candidate.provider() == provider)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "No ComputeInfrastructure bean for provider " + provider));
        this.compute = selected;
    }

    /**
     * Provision every resource required by the Cluster and start its runtime.
     * <p>
     * Never throws for operational failures: they are captured into the result and
     * recorded on the Cluster. Unexpected runtime errors are still converted to a
     * failure result.
     */
    public ProvisionResult provision(Cluster cluster) {
        String clusterId = cluster.getId().toHexString();
        boolean stackCreated = false;
        boolean dnsCreated = false;
        String domain = clusterDomain(clusterId);
        String privateKeyPem = null;

        try {
            log.info("[IaC] provision start cluster={} region={} domain={}",
                clusterId, cluster.getRegion(), domain);

            // 1. Generate the per-Cluster SSH key and protect the private part.
            var generated = keyPairGenerator.generate();
            privateKeyPem = generated.privateKeyPem();
            String encryptedKey = keyCrypto.encrypt(privateKeyPem, properties.getCrypto().getMasterKey());
            cluster.setSshPublicKey(generated.openSshPublicKey());
            cluster.setSshPrivateKeyEnc(encryptedKey);
            persist(cluster);

            // 2. Resolve the control-plane egress allowed to reach port 22.
            String sshCidr = resolveSshSourceCidr();

            // 3. Create the node and its supporting resources.
            NodeSpec spec = new NodeSpec(
                clusterId,
                cluster.getRegion(),
                properties.getAws().getInstanceType(),
                properties.getAws().getDiskSizeGb(),
                generated.openSshPublicKey(),
                List.of(
                    IngressRule.of("tcp", SSH_PORT, sshCidr),
                    IngressRule.of("tcp", 80, "0.0.0.0/0"),
                    IngressRule.of("tcp", 443, "0.0.0.0/0")),
                Map.of(
                    "io.tapstate/cluster-id", clusterId,
                    "io.tapstate/org-id", cluster.getOrgId().toHexString()));

            NodeInstance node = compute.provision(spec);
            stackCreated = true;
            cluster.setEc2InstanceId(node.instanceId());
            cluster.setEc2PublicIp(node.publicIp());
            cluster.setCloudFormationStackStatus("CREATE_COMPLETE");
            persist(cluster);
            log.info("[IaC] node ready cluster={} ip={}", clusterId, node.publicIp());

            // 4. Wait for the node to accept SSH (userData installs finish late).
            waitForSsh(node.publicIp(), privateKeyPem);

            // 5. Publish the DNS name before pushing service configuration.
            dns.upsertARecord(domain, node.publicIp());
            dnsCreated = true;
            cluster.setDnsRecord(domain);
            cluster.setPublicDns(domain);
            persist(cluster);

            // 6. Connect and start the runtime stack.
            try (SshSession session = sshClient.connect(node.publicIp(), SSH_PORT, SSH_USER,
                privateKeyPem, SSH_SESSION_TIMEOUT_MS)) {
                prepareRemoteDirs(session);

                session.uploadFile("/tmp/docker-compose.yml",
                    configs.dockerComposeYaml(cluster, properties.getPublicBaseUrl())
                        .getBytes(StandardCharsets.UTF_8));
                remoteExec(session,
                    "sudo mv /tmp/docker-compose.yml " + ClusterRuntimeConfigs.REMOTE_COMPOSE_PATH
                        + " && sudo chmod 600 " + ClusterRuntimeConfigs.REMOTE_COMPOSE_PATH,
                    Duration.ofSeconds(30));
                log.info("[IaC] compose file in place cluster={}", clusterId);

                remoteExec(session,
                    "cd /opt/tapstate-runtime && sudo docker compose up -d", COMPOSE_TIMEOUT);
                log.info("[IaC] runtime stack started cluster={}", clusterId);

                // 7. Install the Nginx site configuration.
                session.uploadFile("/tmp/tapstate-cluster.conf",
                    configs.nginxConf(domain).getBytes(StandardCharsets.UTF_8));
                remoteExec(session,
                    "sudo mv /tmp/tapstate-cluster.conf " + ClusterRuntimeConfigs.REMOTE_NGINX_CONF_PATH
                        + " && sudo nginx -t"
                        + " && sudo systemctl enable nginx && sudo systemctl restart nginx",
                    Duration.ofSeconds(60));
                log.info("[IaC] nginx configured cluster={}", clusterId);
            }

            // 8. Wait until the name resolves to the node so ACME validation lands here.
            waitForDns(domain, node.publicIp());

            // 9. Obtain the TLS certificate; certbot rewrites the Nginx site for 443.
            try (SshSession session = sshClient.connect(node.publicIp(), SSH_PORT, SSH_USER,
                privateKeyPem, SSH_SESSION_TIMEOUT_MS)) {
                remoteExec(session,
                    "sudo certbot --nginx -d " + domain
                        + " --agree-tos --email " + CERTBOT_EMAIL
                        + " --non-interactive --redirect"
                        + " && sudo systemctl enable certbot.timer"
                        + " && sudo nginx -t && sudo systemctl reload nginx",
                    CERTBOT_TIMEOUT);
            }
            log.info("[IaC] TLS certificate issued cluster={}", clusterId);

            // 10. Verify the public endpoint end to end.
            waitForHealthy(domain);

            log.info("[IaC] provision complete cluster={}", clusterId);
            return ProvisionResult.ok();
        } catch (IacException e) {
            log.error("[IaC] provision failed cluster={} code={} detail={}",
                clusterId, e.errorCode().getCode(), e.getMessage());
            ProvisionResult result = recordFailure(cluster, e);
            compensate(cluster, stackCreated, dnsCreated);
            return result;
        } catch (Exception e) {
            log.error("[IaC] provision failed unexpectedly cluster={}", clusterId, e);
            ProvisionResult result = recordFailure(cluster,
                new IacException(AppErrorCode.SYSTEM_ERROR, e, e.getMessage()));
            compensate(cluster, stackCreated, dnsCreated);
            return result;
        }
    }

    /**
     * Terminate the node and remove its DNS name.
     *
     * @throws IacException when a resource cannot be reclaimed
     */
    public void terminate(Cluster cluster) {
        String clusterId = cluster.getId().toHexString();
        log.info("[IaC] terminate start cluster={}", clusterId);
        try {
            compute.terminate(cluster.getRegion(), clusterId);
            cluster.setCloudFormationStackStatus("DELETE_COMPLETE");
            clusterRepository.save(cluster);

            String domain = cluster.getDnsRecord() != null ? cluster.getDnsRecord() : cluster.getPublicDns();
            if (domain != null && !domain.isBlank()) {
                dns.deleteRecord(domain);
            }
            log.info("[IaC] terminate complete cluster={}", clusterId);
        } catch (IacException e) {
            log.error("[IaC] terminate failed cluster={} code={} detail={}",
                clusterId, e.errorCode().getCode(), e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("[IaC] terminate failed unexpectedly cluster={}", clusterId, e);
            throw new IacException(AppErrorCode.IAC_COMPUTE_TERMINATE_FAILED, e, e.getMessage());
        }
    }

    // ========== Step helpers ==========

    /** Build the Cluster domain from its id and the configured environment. */
    private String clusterDomain(String clusterId) {
        String rootDomain = properties.getGodaddy().getDomain();
        if ("prod".equalsIgnoreCase(properties.getEnvironment())) {
            return clusterId + ".cloud." + rootDomain;
        }
        return clusterId + ".dev.cloud." + rootDomain;
    }

    /** Resolve the CIDR allowed for port 22, discovering the public IP when unset. */
    private String resolveSshSourceCidr() {
        String configured = properties.getSshSourceCidr();
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        try {
            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
            HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create("https://checkip.amazonaws.com")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            String ip = response.body().trim();
            return ip + "/32";
        } catch (Exception e) {
            throw new IacException(AppErrorCode.IAC_COMPUTE_PROVISION_FAILED, e,
                "cannot discover control-plane public IP for SSH whitelist");
        }
    }

    /** Retry SSH connections until the node accepts the key or the timeout elapses. */
    private void waitForSsh(String host, String privateKeyPem) {
        long deadline = System.nanoTime() + SSH_WAIT_TIMEOUT.toNanos();
        IOException last = null;
        while (System.nanoTime() < deadline) {
            try (SshSession session = sshClient.connect(host, SSH_PORT, SSH_USER,
                privateKeyPem, POLL_INTERVAL_MS)) {
                CommandResult result = session.exec("echo ready", Duration.ofSeconds(15));
                if (result.succeeded()) {
                    return;
                }
                last = new IOException("probe exited " + result.exitCode());
            } catch (IOException e) {
                last = e;
            }
            sleep(POLL_INTERVAL_MS);
        }
        throw new IacException(AppErrorCode.IAC_SSH_COMMAND_FAILED,
            "SSH unreachable after " + SSH_WAIT_TIMEOUT + ": " + safeMessage(last));
    }

    /** Wait until the domain resolves to the expected address. */
    private void waitForDns(String domain, String expectedIp) {
        long deadline = System.nanoTime() + DNS_WAIT_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                InetAddress[] addresses = InetAddress.getAllByName(domain);
                for (InetAddress address : addresses) {
                    if (expectedIp.equals(address.getHostAddress())) {
                        return;
                    }
                }
            } catch (Exception ignored) {
                // Not propagated yet — keep polling until the deadline.
            }
            sleep(POLL_INTERVAL_MS);
        }
        throw new IacException(AppErrorCode.IAC_DNS_PROVIDER_FAILED,
            "DNS for " + domain + " did not resolve to " + expectedIp
                + " within " + DNS_WAIT_TIMEOUT);
    }

    /** Poll the HTTPS endpoint until it answers 200 or the timeout elapses. */
    private void waitForHealthy(String domain) {
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
        long deadline = System.nanoTime() + HEALTH_TIMEOUT.toNanos();
        String lastError = "no attempt made";
        while (System.nanoTime() < deadline) {
            try {
                HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create("https://" + domain + "/health"))
                        .timeout(Duration.ofSeconds(15))
                        .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    return;
                }
                lastError = "status " + response.statusCode();
            } catch (Exception e) {
                lastError = safeMessage(e);
            }
            sleep(HEALTH_INTERVAL_MS);
        }
        throw new IacException(AppErrorCode.IAC_HEALTH_CHECK_FAILED,
            "endpoint not healthy within " + HEALTH_TIMEOUT + ": " + lastError);
    }

    /** Create the remote directories used by the runtime and ACME webroot. */
    private void prepareRemoteDirs(SshSession session) {
        remoteExec(session,
            "sudo mkdir -p /opt/tapstate-runtime /var/www/certbot"
                + " && sudo chmod 755 /var/www/certbot",
            Duration.ofSeconds(30));
    }

    /** Run a command, requiring exit code zero. */
    private void remoteExec(SshSession session, String command, Duration timeout) {
        try {
            CommandResult result = session.exec("sudo bash -c " + shellQuote(command), timeout);
            if (!result.succeeded()) {
                throw new IacException(AppErrorCode.IAC_SSH_COMMAND_FAILED,
                    "remote command failed (" + result.exitCode() + "): "
                        + abbreviate(result.stderr()));
            }
        } catch (java.io.IOException e) {
            throw new IacException(AppErrorCode.IAC_SSH_COMMAND_FAILED, e, safeMessage(e));
        }
    }

    /** Reverse-order compensation of resources already created. */
    private void compensate(Cluster cluster, boolean stackCreated, boolean dnsCreated) {
        String clusterId = cluster.getId().toHexString();
        if (dnsCreated) {
            try {
                String domain = cluster.getDnsRecord();
                dns.deleteRecord(domain);
                cluster.setDnsRecord(null);
            } catch (Exception e) {
                log.error("[IaC] compensation failed to delete DNS cluster={}: {}",
                    clusterId, safeMessage(e));
            }
        }
        if (stackCreated) {
            try {
                compute.terminate(cluster.getRegion(), clusterId);
                cluster.setCloudFormationStackStatus("DELETE_COMPLETE");
            } catch (Exception e) {
                log.error("[IaC] compensation failed to delete stack cluster={}: {}",
                    clusterId, safeMessage(e));
            }
        }
        try {
            clusterRepository.save(cluster);
        } catch (Exception e) {
            log.error("[IaC] compensation state persist failed cluster={}: {}",
                clusterId, safeMessage(e));
        }
    }

    /** Map an operational failure, record it on the Cluster and persist the document. */
    private ProvisionResult recordFailure(Cluster cluster, IacException e) {
        ProvisionErrorCode code = switch (e.errorCode()) {
            case IAC_SSH_COMMAND_FAILED -> ProvisionErrorCode.EC2_BOOTSTRAP_TIMEOUT;
            case IAC_HEALTH_CHECK_FAILED -> ProvisionErrorCode.PROVISIONING_TIMEOUT;
            default -> ProvisionErrorCode.CLOUDFORMATION_FAILED;
        };
        String detail = e.errorCode().getCode() + ": " + e.getMessage();
        cluster.setProvisionErrorCode(code);
        cluster.setProvisionErrorDetail(detail);
        clusterRepository.save(cluster);
        return ProvisionResult.failed(code, detail);
    }

    private void persist(Cluster cluster) {
        clusterRepository.save(cluster);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static String safeMessage(Throwable t) {
        return t != null && t.getMessage() != null ? t.getMessage() : t != null ? t.toString() : "unknown";
    }

    private static String abbreviate(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() > 500 ? trimmed.substring(0, 500) : trimmed;
    }

    /**
     * Outcome of a provision attempt.
     *
     * @param success   whether the Cluster became reachable
     * @param errorCode persisted failure category when unsuccessful
     * @param detail    diagnostic detail when unsuccessful
     */
    public record ProvisionResult(boolean success, ProvisionErrorCode errorCode, String detail) {

        public static ProvisionResult ok() {
            return new ProvisionResult(true, null, null);
        }

        public static ProvisionResult failed(ProvisionErrorCode errorCode, String detail) {
            return new ProvisionResult(false, errorCode, detail);
        }
    }
}
