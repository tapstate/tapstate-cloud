package io.tapstate.cloud.iac;

import io.tapstate.cloud.cluster.Cluster;
import io.tapstate.cloud.cluster.ClusterEgressAddressStatus;
import io.tapstate.cloud.cluster.ClusterRepository;
import io.tapstate.cloud.cluster.ProvisionErrorCode;
import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.iac.config.ClusterImageResolver;
import io.tapstate.cloud.iac.config.ClusterRuntimeConfigs;
import io.tapstate.cloud.iac.config.RuntimeAtlasUriResolver;
import io.tapstate.cloud.iac.crypto.SshKeyCrypto;
import io.tapstate.cloud.iac.model.IngressRule;
import io.tapstate.cloud.iac.model.NodeInstance;
import io.tapstate.cloud.iac.model.NodeSpec;
import io.tapstate.cloud.iac.ssh.CommandResult;
import io.tapstate.cloud.iac.ssh.SshClient;
import io.tapstate.cloud.iac.ssh.SshKeyPairGenerator;
import io.tapstate.cloud.iac.ssh.SshSession;
import io.tapstate.cloud.progress.DeploymentOperation;
import io.tapstate.cloud.progress.DeploymentStepRecorder;
import io.tapstate.cloud.tls.TlsCertificate;
import io.tapstate.cloud.tls.TlsCertificateService;
import io.tapstate.cloud.tls.TlsNodeInstaller;
import io.tapstate.cloud.version.ClusterVersion;
import io.tapstate.cloud.version.ClusterVersionService;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

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
    private final RuntimeAtlasUriResolver runtimeAtlasUriResolver;
    private final DeploymentStepRecorder recorder;
    private final TlsCertificateService tlsCertificateService;
    private final TlsNodeInstaller tlsInstaller;
    private final ClusterVersionService clusterVersionService;
    private final ClusterImageResolver imageResolver;

    // ---- Timing budget ----
    private static final Duration STACK_TIMEOUT = Duration.ofMinutes(15);
    private static final Duration SSH_WAIT_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration DNS_WAIT_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration HEALTH_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration COMPOSE_TIMEOUT = Duration.ofMinutes(5);
    private static final int POLL_INTERVAL_MS = 10_000;
    private static final int SSH_SESSION_TIMEOUT_MS = 60_000;
    private static final int HEALTH_INTERVAL_MS = 20_000;
    private static final int SSH_PORT = 22;
    private static final String SSH_USER = "ubuntu";

    public ClusterProvisioningService(
        ClusterRepository clusterRepository,
        IacProperties properties,
        Map<String, ComputeInfrastructure> computeBeans,
        DnsInfrastructure dns,
        SshClient sshClient,
        SshKeyPairGenerator keyPairGenerator,
        SshKeyCrypto keyCrypto,
        ClusterRuntimeConfigs configs,
        RuntimeAtlasUriResolver runtimeAtlasUriResolver,
        DeploymentStepRecorder recorder,
        TlsCertificateService tlsCertificateService,
        TlsNodeInstaller tlsInstaller,
        ClusterVersionService clusterVersionService,
        ClusterImageResolver imageResolver) {
        this.clusterRepository = clusterRepository;
        this.properties = properties;
        this.dns = dns;
        this.sshClient = sshClient;
        this.keyPairGenerator = keyPairGenerator;
        this.keyCrypto = keyCrypto;
        this.configs = configs;
        this.runtimeAtlasUriResolver = runtimeAtlasUriResolver;
        this.recorder = recorder;
        this.tlsCertificateService = tlsCertificateService;
        this.tlsInstaller = tlsInstaller;
        this.clusterVersionService = clusterVersionService;
        this.imageResolver = imageResolver;

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
        String domain = clusterDomain(clusterId);
        ProxyConfig proxy = resolveProxy();

        // Resolve the deployable Cluster version before touching any cloud
        // resource. A subscription normally pins the version before this runs;
        // a blank field is a defensive fallback to the latest ACTIVE version,
        // persisted onto the Cluster for an auditable, reproducible image.
        // A pinned version is deployed even if it has since been delisted —
        // delisting affects only new subscriptions.
        String clusterVersion = cluster.getClusterVersion();
        if (clusterVersion == null || clusterVersion.isBlank()) {
            ClusterVersion fallback = clusterVersionService.findLastActive().orElse(null);
            if (fallback == null) {
                IacException unavailable = new IacException(AppErrorCode.IAC_CLUSTER_VERSION_UNAVAILABLE,
                    "No active Cluster version is available for Cluster provisioning");
                recorder.fail(cluster, DeploymentOperation.START_RUNTIME_STACK, unavailable.getMessage());
                log.error("[IaC] provision aborted cluster={}: {}", clusterId, unavailable.getMessage());
                return recordFailure(cluster, unavailable);
            }
            cluster.setClusterVersion(fallback.getVersion());
            persist(cluster);
            clusterVersion = fallback.getVersion();
        }
        final String runtimeImage = imageResolver.runtimeImage(clusterVersion);

        // Resolve the centrally managed wildcard certificate before touching any
        // cloud resource: a missing certificate fails provisioning instantly
        // instead of after a billable EC2 node has been provisioned.
        TlsCertificate tlsCertificate = tlsCertificateService.findValidForHost(domain).orElse(null);
        if (tlsCertificate == null) {
            IacException unavailable = new IacException(AppErrorCode.IAC_TLS_CERTIFICATE_UNAVAILABLE,
                "No unexpired, valid TLS certificate covering " + domain
                    + " is available for Cluster provisioning");
            recorder.fail(cluster, DeploymentOperation.ISSUE_TLS_CERTIFICATE, unavailable.getMessage());
            log.error("[IaC] provision aborted cluster={}: {}", clusterId, unavailable.getMessage());
            return recordFailure(cluster, unavailable);
        }

        try {
            log.info("[IaC] provision start cluster={} region={} domain={} tlsCertificate={}",
                clusterId, cluster.getRegion(), domain, tlsCertificate.getId().toHexString());

            // 1. Generate the per-Cluster SSH key and protect the private part.
            var generated = runStep(cluster, DeploymentOperation.GENERATE_SSH_KEY,
                "SSH key pair generated", () -> {
                    var keyPair = keyPairGenerator.generate();
                    String encryptedKey = keyCrypto.encrypt(
                        keyPair.privateKeyPem(), properties.getCrypto().getMasterKey());
                    cluster.setSshPublicKey(keyPair.openSshPublicKey());
                    cluster.setSshPrivateKeyEnc(encryptedKey);
                    persist(cluster);
                    return keyPair;
                });
            String privateKeyPem = generated.privateKeyPem();

            // 2. Create the node; port 22 is open to any source only until the
            // real control-plane egress ranges have been observed and narrowed.
            NodeInstance node = runStep(cluster, DeploymentOperation.PROVISION_EC2,
                "EC2 node provisioned", () -> {
                    NodeSpec spec = new NodeSpec(
                        clusterId,
                        cluster.getRegion(),
                        properties.getAws().getInstanceType(),
                        properties.getAws().getDiskSizeGb(),
                        generated.openSshPublicKey(),
                        List.of(
                            IngressRule.of("tcp", 80, "0.0.0.0/0"),
                            IngressRule.of("tcp", 443, "0.0.0.0/0")),
                        Map.of(
                            "io.tapstate/cluster-id", clusterId,
                            "io.tapstate/org-id", cluster.getOrgId().toHexString()),
                        properties.getAws().getVpcId(),
                        properties.getAws().getSubnetId());

                    NodeInstance provisioned = compute.provision(spec);
                    if (compute.provider() == CloudProvider.AWS
                            && (provisioned.egressAddressAllocationId() == null
                                || provisioned.egressAddressAllocationId().isBlank())) {
                        throw new IacException(AppErrorCode.IAC_COMPUTE_PROVISION_FAILED,
                            "AWS Cluster provisioning completed without a stable egress address allocation");
                    }
                    cluster.setEc2InstanceId(provisioned.instanceId());
                    cluster.setEc2PublicIp(provisioned.publicIp());
                    cluster.setEgressAddressAllocationId(provisioned.egressAddressAllocationId());
                    cluster.setEgressAddressStatus(compute.provider() == CloudProvider.AWS
                        ? ClusterEgressAddressStatus.STABLE : ClusterEgressAddressStatus.DYNAMIC);
                    cluster.setCloudFormationStackStatus("CREATE_COMPLETE");
                    persist(cluster);
                    log.info("[IaC] node ready cluster={} ip={}", clusterId, provisioned.publicIp());
                    return provisioned;
                });

            // 4. Wait for the node to accept SSH and collect the real control-plane
            // egress ranges as observed on the wire, then narrow the firewall.
            List<String> sshCidrs = runStep(cluster, DeploymentOperation.WAIT_SSH_READY,
                "SSH is ready", () -> waitForSsh(node.publicIp(), privateKeyPem, proxy));
            runStep(cluster, DeploymentOperation.RESTRICT_SSH_INGRESS,
                "SSH ingress restricted to control-plane ranges", () ->
                    compute.restrictSshIngress(cluster.getRegion(), clusterId, sshCidrs));

            // 5. Publish the DNS name before pushing service configuration.
            runStep(cluster, DeploymentOperation.CREATE_DNS_RECORD,
                "DNS record created", () -> {
                    dns.upsertARecord(domain, node.publicIp());
                    cluster.setDnsRecord(domain);
                    cluster.setPublicDns(domain);
                    persist(cluster);
                });

            // 6. The Atlas allocation runs in parallel with the EC2 provisioning;
            // wait for its database + db user and build the per-allocation runtime
            // URI before rendering the compose file. Resolve outside the SSH
            // session so the wait does not hold a connection to the node.
            String runtimeAtlasUri = runStep(cluster, DeploymentOperation.WAIT_MDB_ALLOCATION,
                "MongoDB allocation ready", () -> runtimeAtlasUriResolver.resolve(cluster));

            // 7. Connect and start the runtime stack.
            runStep(cluster, DeploymentOperation.START_RUNTIME_STACK,
                "Runtime stack started", () -> {
                    try (SshSession session = connectSsh(node, proxy, privateKeyPem)) {
                        prepareRemoteDirs(session);

                        session.uploadFile("/tmp/docker-compose.yml",
                            configs.dockerComposeYaml(cluster, properties.getPublicBaseUrl(),
                                runtimeAtlasUri, runtimeImage)
                                .getBytes(StandardCharsets.UTF_8));
                        remoteExec(session,
                            "sudo mv /tmp/docker-compose.yml " + ClusterRuntimeConfigs.REMOTE_COMPOSE_PATH
                                + " && sudo chmod 600 " + ClusterRuntimeConfigs.REMOTE_COMPOSE_PATH,
                            Duration.ofSeconds(30));
                        log.info("[IaC] compose file in place cluster={}", clusterId);

                        remoteExec(session,
                            "cd /opt/tapstate-runtime && sudo docker compose up -d", COMPOSE_TIMEOUT);
                        log.info("[IaC] runtime stack started cluster={}", clusterId);
                    } catch (java.io.IOException e) {
                        throw new IacException(AppErrorCode.IAC_SSH_COMMAND_FAILED, e, safeMessage(e));
                    }
                });

            // 8. Install the Nginx http-context tuning drop-in. The TLS site
            // configuration is written together with the certificate in step 10,
            // since Nginx refuses to load a site whose cert files are missing.
            runStep(cluster, DeploymentOperation.CONFIGURE_NGINX,
                "Nginx configured", () -> {
                    try (SshSession session = connectSsh(node, proxy, privateKeyPem)) {
                        session.uploadFile("/tmp/00-tapstate-http.conf",
                            configs.nginxHttpConf().getBytes(StandardCharsets.UTF_8));
                        remoteExec(session,
                            "sudo mv /tmp/00-tapstate-http.conf "
                                + ClusterRuntimeConfigs.REMOTE_NGINX_HTTP_CONF_PATH
                                + " && sudo nginx -t && sudo systemctl enable nginx",
                            Duration.ofSeconds(30));
                        log.info("[IaC] nginx http tuning installed cluster={}", clusterId);
                    } catch (java.io.IOException e) {
                        throw new IacException(AppErrorCode.IAC_SSH_COMMAND_FAILED, e, safeMessage(e));
                    }
                });

            // 9. Wait until the name resolves to the node so the health check lands here.
            runStep(cluster, DeploymentOperation.WAIT_DNS_PROPAGATION,
                "DNS propagated", () -> waitForDns(domain, node.publicIp()));

            // 10. Install the centrally managed wildcard certificate and the TLS
            // site configuration, then start serving HTTPS.
            runStep(cluster, DeploymentOperation.ISSUE_TLS_CERTIFICATE,
                "TLS certificate installed", () -> {
                    try (SshSession session = connectSsh(node, proxy, privateKeyPem)) {
                        tlsInstaller.install(session, domain, tlsCertificate);
                    } catch (java.io.IOException e) {
                        throw new IacException(AppErrorCode.IAC_SSH_COMMAND_FAILED, e, safeMessage(e));
                    }
                    cluster.setTlsCertificateId(tlsCertificate.getId());
                    persist(cluster);
                    log.info("[IaC] TLS certificate {} installed cluster={}",
                        tlsCertificate.getId().toHexString(), clusterId);
                });

            // 11. Verify the public endpoint end to end.
            runStep(cluster, DeploymentOperation.HEALTH_CHECK,
                "Endpoint is healthy", () -> {
                    try {
                        waitForHealthy(domain, proxy);
                    } catch (IacException e) {
                        // Capture the node side before compensation terminates the EC2
                        // instance: a 502 means the runtime container failed to boot and
                        // its logs are the only evidence of why.
                        throw new IacException(AppErrorCode.IAC_HEALTH_CHECK_FAILED,
                            e.getMessage() + collectNodeDiagnostics(node.publicIp(), proxy, privateKeyPem));
                    }
                });

            log.info("[IaC] provision complete cluster={}", clusterId);
            return ProvisionResult.ok();
        } catch (IacException e) {
            log.error("[IaC] provision failed cluster={} code={} detail={}",
                clusterId, e.errorCode().getCode(), e.getMessage());
            ProvisionResult result = recordFailure(cluster, e);
            compensateFromDurableState(clusterId);
            return result;
        } catch (Exception e) {
            log.error("[IaC] provision failed unexpectedly cluster={}", clusterId, e);
            ProvisionResult result = recordFailure(cluster,
                new IacException(AppErrorCode.SYSTEM_ERROR, e, e.getMessage()));
            compensateFromDurableState(clusterId);
            return result;
        }
    }

    /**
     * Compensate resources based on the durable Cluster state, not on
     * in-memory flags — the worker runs inside lambdas and cannot carry mutable
     * locals across them.
     */
    private void compensateFromDurableState(String clusterId) {
        Cluster latest = clusterRepository.findById(new ObjectId(clusterId)).orElse(null);
        if (latest == null) {
            return;
        }
        boolean stackCreated = "CREATE_COMPLETE".equals(latest.getCloudFormationStackStatus())
            || latest.getEc2InstanceId() != null;
        boolean dnsCreated = latest.getDnsRecord() != null;
        compensate(latest, stackCreated, dnsCreated);
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
            runStep(cluster, DeploymentOperation.TERMINATE_EC2,
                "EC2 node terminated", () -> {
                    compute.terminate(cluster.getRegion(), clusterId);
                    cluster.setCloudFormationStackStatus("DELETE_COMPLETE");
                    clusterRepository.save(cluster);
                });

            String domain = cluster.getDnsRecord() != null ? cluster.getDnsRecord() : cluster.getPublicDns();
            runStep(cluster, DeploymentOperation.DELETE_DNS_RECORD,
                domain != null && !domain.isBlank() ? "DNS record deleted" : "No DNS record to delete",
                () -> {
                    if (domain != null && !domain.isBlank()) {
                        dns.deleteRecord(domain);
                    }
                });
            log.info("[IaC] terminate complete cluster={}", clusterId);
        } catch (IacException e) {
            log.error("[IaC] terminate failed cluster={} code={} detail={}",
                clusterId, e.errorCode().getCode(), e.getMessage());
            throw e;
        }
    }

    // ========== Progress recording template ==========

    /**
     * Run one provisioning operation while recording its progress. On failure
     * the failure detail is recorded and the original exception propagates so
     * the caller can compensate; recording itself never throws.
     */
    private <T> T runStep(Cluster cluster, DeploymentOperation operation,
                          String successMessage, Supplier<T> action) {
        recorder.start(cluster, operation);
        try {
            T result = action.get();
            recorder.succeed(cluster, operation, successMessage);
            return result;
        } catch (RuntimeException e) {
            recorder.fail(cluster, operation, safeMessage(e));
            throw e;
        }
    }

    /** Void variant of {@link #runStep(Cluster, DeploymentOperation, String, Supplier)}. */
    private void runStep(Cluster cluster, DeploymentOperation operation,
                         String successMessage, Runnable action) {
        runStep(cluster, operation, successMessage, () -> {
            action.run();
            return null;
        });
    }

    /** Open an SSH session to the node using the provisioned key and proxy settings. */
    private SshSession connectSsh(NodeInstance node, ProxyConfig proxy, String privateKeyPem)
        throws java.io.IOException {
        return sshClient.connect(node.publicIp(), SSH_PORT, SSH_USER,
            privateKeyPem, SSH_SESSION_TIMEOUT_MS, proxyHost(proxy), proxyPort(proxy));
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

    /**
     * Resolve the HTTP proxy the JVM is configured to use, if any. SSH tunnels and
     * IP discovery must use the same egress so the discovered CIDR matches the
     * actual SSH source address.
     */
    private static ProxyConfig resolveProxy() {
        String host = System.getProperty("https.proxyHost", System.getProperty("http.proxyHost"));
        if (host == null || host.isBlank()) {
            return null;
        }
        String port = System.getProperty("https.proxyPort",
            System.getProperty("http.proxyPort", "8080"));
        return new ProxyConfig(host, Integer.parseInt(port.trim()));
    }

    private static String proxyHost(ProxyConfig proxy) {
        return proxy != null ? proxy.host() : null;
    }

    private static int proxyPort(ProxyConfig proxy) {
        return proxy != null ? proxy.port() : 0;
    }

    /**
     * Number of successful probes collected before narrowing the firewall. A
     * rotating proxy may assign a different egress per connection, so several
     * rapid successes are observed; a single-egress network simply repeats one.
     */
    private static final int SSH_PROBE_SUCCESSES = 6;

    /**
     * Wait for the node to accept SSH and collect the distinct control-plane
     * source addresses, as observed on the wire, over several rapid probes.
     *
     * @return the observed source CIDRs, one per distinct egress address
     */
    private List<String> waitForSsh(String host, String privateKeyPem, ProxyConfig proxy) {
        long deadline = System.nanoTime() + SSH_WAIT_TIMEOUT.toNanos();
        java.util.LinkedHashSet<String> observed = new java.util.LinkedHashSet<>();
        int successes = 0;
        IOException last = null;
        while (System.nanoTime() < deadline) {
            try (SshSession session = sshClient.connect(host, SSH_PORT, SSH_USER,
                privateKeyPem, POLL_INTERVAL_MS, proxyHost(proxy), proxyPort(proxy))) {
                CommandResult result = session.exec(
                    "echo ready; echo \"$SSH_CONNECTION\"", Duration.ofSeconds(15));
                if (result.succeeded()) {
                    successes++;
                    String clientIp = parseSshClientIp(result.stdout());
                    if (clientIp != null) {
                        observed.add(clientIp + "/32");
                    }
                    if (successes >= SSH_PROBE_SUCCESSES) {
                        return List.copyOf(observed);
                    }
                    // Probe again immediately — spacing would only slow discovery.
                    continue;
                }
                last = new IOException("probe exited " + result.exitCode());
            } catch (IOException e) {
                last = e;
            }
            sleep(POLL_INTERVAL_MS);
        }
        if (!observed.isEmpty()) {
            return List.copyOf(observed);
        }
        throw new IacException(AppErrorCode.IAC_SSH_COMMAND_FAILED,
            "SSH unreachable after " + SSH_WAIT_TIMEOUT + ": " + safeMessage(last));
    }

    /** Extract the client IPv4 from the second whitespace-separated SSH_CONNECTION line. */
    private static String parseSshClientIp(String stdout) {
        String[] lines = stdout.split("\\R");
        for (String line : lines) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length == 4 && parts[0].matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
                return parts[0];
            }
        }
        return null;
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
    private void waitForHealthy(String domain, ProxyConfig proxy) {
        HttpClient.Builder builder = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL);
        if (proxy != null) {
            builder.proxy(ProxySelector.of(
                new InetSocketAddress(proxy.host(), proxy.port())));
        }
        HttpClient client = builder.build();
        long deadline = System.nanoTime() + HEALTH_TIMEOUT.toNanos();
        String lastError = "no attempt made";
        while (System.nanoTime() < deadline) {
            try {
                HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create("https://" + domain + "/healthz"))
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

    /**
     * Best-effort snapshot of the runtime stack taken on the node after a failed
     * health check. Compensation terminates the EC2 instance immediately after a
     * failed provisioning, so without this capture the container evidence is lost
     * and diagnosis needs another full provisioning cycle.
     * <p>
     * Never throws: a diagnostics failure must not mask the original failure.
     */
    private String collectNodeDiagnostics(String nodeIp, ProxyConfig proxy, String privateKeyPem) {
        String command = "cd /opt/tapstate-runtime"
            + " && echo '=== docker compose ps ===' && sudo docker compose ps"
            + " && echo '=== runtime logs (tail) ==='"
            + " && sudo docker compose logs --tail=200 tapstate-runtime"
            + " && echo '=== local healthz ==='"
            + " && curl -sS -m 10 -o /dev/null -w 'local healthz HTTP %{http_code}\\n'"
            + " http://127.0.0.1:8080/healthz";
        try (SshSession session = sshClient.connect(nodeIp, SSH_PORT, SSH_USER,
            privateKeyPem, SSH_SESSION_TIMEOUT_MS, proxyHost(proxy), proxyPort(proxy))) {
            CommandResult result = session.exec(
                "sudo bash -c " + shellQuote(command), Duration.ofSeconds(90));
            String output = result.stdout();
            if (result.stderr() != null && !result.stderr().isBlank()) {
                output = output + System.lineSeparator() + result.stderr();
            }
            return System.lineSeparator() + "node diagnostics:" + System.lineSeparator()
                + truncate(output.strip(), 8000);
        } catch (Exception e) {
            return System.lineSeparator() + "node diagnostics unavailable: " + safeMessage(e);
        }
    }

    /** Create the remote directories used by the runtime and Nginx TLS material. */
    private void prepareRemoteDirs(SshSession session) {
        remoteExec(session,
            "sudo mkdir -p /opt/tapstate-runtime " + ClusterRuntimeConfigs.REMOTE_NGINX_SSL_DIR
                + " && sudo chmod 755 " + ClusterRuntimeConfigs.REMOTE_NGINX_SSL_DIR,
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
            case IAC_MDB_NOT_READY -> ProvisionErrorCode.ATLAS_DB_CREATE_FAILED;
            case IAC_TLS_CERTIFICATE_UNAVAILABLE -> ProvisionErrorCode.TLS_CERTIFICATE_UNAVAILABLE;
            case IAC_TLS_CERTIFICATE_INSTALL_FAILED -> ProvisionErrorCode.TLS_CERTIFICATE_INSTALL_FAILED;
            case IAC_CLUSTER_VERSION_UNAVAILABLE -> ProvisionErrorCode.CLUSTER_VERSION_UNAVAILABLE;
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

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() > max ? value.substring(0, max) + "...[truncated]" : value;
    }

    /**
     * HTTP proxy configuration resolved from the JVM network properties.
     *
     * @param host proxy host
     * @param port proxy port
     */
    private record ProxyConfig(String host, int port) {
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
