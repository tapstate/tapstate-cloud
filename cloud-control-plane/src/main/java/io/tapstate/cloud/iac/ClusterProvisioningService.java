package io.tapstate.cloud.iac;

import io.tapstate.cloud.cluster.Cluster;
import io.tapstate.cloud.cluster.ClusterEgressAddressStatus;
import io.tapstate.cloud.cluster.ClusterRepository;
import io.tapstate.cloud.cluster.ClusterState;
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
import io.tapstate.cloud.subscription.SpecConfig;
import io.tapstate.cloud.subscription.SpecConfigService;
import io.tapstate.cloud.tls.TlsCertificate;
import io.tapstate.cloud.tls.TlsCertificateService;
import io.tapstate.cloud.tls.TlsNodeInstaller;
import io.tapstate.cloud.version.ClusterVersion;
import io.tapstate.cloud.version.ClusterVersionService;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

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
import java.time.Instant;
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
    private final ClusterDomainName clusterDomainName;
    private final ComputeInfrastructure compute;
    private final ClusterDnsRecordCoordinator dnsRecords;
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
    private final MongoTemplate mongoTemplate;
    private final SpecConfigService specConfigService;

    // ---- Timing budget ----
    private static final Duration STACK_TIMEOUT = Duration.ofMinutes(15);
    private static final Duration SSH_WAIT_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration DNS_WAIT_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration HEALTH_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration COMPOSE_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration PULL_TIMEOUT = Duration.ofMinutes(10);
    private static final int POLL_INTERVAL_MS = 10_000;
    private static final int SSH_SESSION_TIMEOUT_MS = 60_000;
    private static final int HEALTH_INTERVAL_MS = 20_000;
    private static final int SSH_PORT = 22;
    private static final String SSH_USER = "ubuntu";

    public ClusterProvisioningService(
        ClusterRepository clusterRepository,
        IacProperties properties,
        ClusterDomainName clusterDomainName,
        Map<String, ComputeInfrastructure> computeBeans,
        ClusterDnsRecordCoordinator dnsRecords,
        SshClient sshClient,
        SshKeyPairGenerator keyPairGenerator,
        SshKeyCrypto keyCrypto,
        ClusterRuntimeConfigs configs,
        RuntimeAtlasUriResolver runtimeAtlasUriResolver,
        DeploymentStepRecorder recorder,
        TlsCertificateService tlsCertificateService,
        TlsNodeInstaller tlsInstaller,
        ClusterVersionService clusterVersionService,
        ClusterImageResolver imageResolver,
        MongoTemplate mongoTemplate,
        SpecConfigService specConfigService) {
        this.clusterRepository = clusterRepository;
        this.properties = properties;
        this.clusterDomainName = clusterDomainName;
        this.dnsRecords = dnsRecords;
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
        this.mongoTemplate = mongoTemplate;
        this.specConfigService = specConfigService;

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
        String dnsLabel = cluster.getDnsLabel();
        if (dnsLabel == null || dnsLabel.isBlank()) {
            // Every Cluster created by the current code carries a label; a
            // missing one means a document the startup backfill never reached.
            throw new IllegalStateException("Cluster " + clusterId + " has no persisted dnsLabel");
        }
        String domain = clusterDomainName.fqdn(dnsLabel);
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
            persist(cluster, new Update().set("clusterVersion", fallback.getVersion()));
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
            // A resumed run must reuse the stored key: the node may already exist
            // with the previous public key baked into its authorized_keys, and a
            // rotated key can never authenticate against it.
            var generated = runStep(cluster, DeploymentOperation.GENERATE_SSH_KEY,
                "SSH key pair generated", () -> {
                    String masterKey = properties.getCrypto().getMasterKey();
                    if (cluster.getSshPrivateKeyEnc() != null && !cluster.getSshPrivateKeyEnc().isBlank()
                            && cluster.getSshPublicKey() != null && !cluster.getSshPublicKey().isBlank()) {
                        return new SshKeyPairGenerator.GeneratedSshKeyPair(
                            cluster.getSshPublicKey(),
                            keyCrypto.decrypt(cluster.getSshPrivateKeyEnc(), masterKey));
                    }
                    var keyPair = keyPairGenerator.generate();
                    String encryptedKey = keyCrypto.encrypt(keyPair.privateKeyPem(), masterKey);
                    cluster.setSshPublicKey(keyPair.openSshPublicKey());
                    cluster.setSshPrivateKeyEnc(encryptedKey);
                    persist(cluster, new Update().set("sshPublicKey", cluster.getSshPublicKey())
                        .set("sshPrivateKeyEnc", encryptedKey));
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
                        instanceTypeFor(cluster),
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
                    java.util.Map<String, String> inventory = new java.util.HashMap<>();
                    String stackName = "tapstate-cluster-" + clusterId;
                    inventory.put("cloudFormationStackName", stackName);
                    inventory.put("cloudFormationStackId", stackName);
                    cluster.setCleanupInventory(inventory);
                    persist(cluster, new Update().set("ec2InstanceId", provisioned.instanceId())
                        .set("ec2PublicIp", provisioned.publicIp())
                        .set("egressAddressAllocationId", provisioned.egressAddressAllocationId())
                        .set("egressAddressStatus", cluster.getEgressAddressStatus())
                        .set("cloudFormationStackStatus", "CREATE_COMPLETE")
                        .set("cleanupInventory", inventory));
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
                    dnsRecords.publish(domain, node.publicIp(), cluster.getId(), () -> {
                        cluster.setDnsRecord(domain);
                        cluster.setPublicDns(domain);
                        persist(cluster, new Update().set("dnsRecord", domain).set("publicDns", domain));
                    });
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

                        // Pull first with retries, then start. Split pull from up so a
                        // stalled image download (fresh EC2 hitting registry hiccups)
                        // is distinguishable from an actual runtime bootstrap failure.
                        // The two operations also benefit from separate timeouts — a
                        // large image pull can legitimately take >5 min on a cold node.
                        pullWithRetries(session, clusterId);

                        remoteExec(session,
                            "cd /opt/tapstate-runtime && sudo docker compose up -d",
                            COMPOSE_TIMEOUT);
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
                    persist(cluster, new Update().set("tlsCertificateId", tlsCertificate.getId()));
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
     * <p>
     * Before deleting any stack this method queries the provider's remote state
     * via {@link ComputeInfrastructure#describeInfrastructureStatus} (when
     * available). If the remote stack is already healthy (e.g. the watcher timed
     * out on post-SSH steps but CloudFormation itself succeeded), we SKIP the
     * stack deletion so we do not destroy a healthy, billable resource. DNS
     * records are still cleaned independently of the stack.
     */
    private void compensateFromDurableState(String clusterId) {
        Cluster latest = clusterRepository.findById(new ObjectId(clusterId)).orElse(null);
        if (latest == null) {
            return;
        }
        if (latest.getState() == ClusterState.TERMINATED) {
            try {
                terminate(latest);
            } catch (Exception cleanupFailure) {
                log.warn("[IaC] cancelled provisioning cleanup will be retried cluster={} type={}",
                    clusterId, cleanupFailure.getClass().getSimpleName());
            }
            return;
        }
        boolean stackCreated = "CREATE_COMPLETE".equals(latest.getCloudFormationStackStatus())
            || latest.getEc2InstanceId() != null;
        boolean dnsCreated = latest.getDnsRecord() != null;

        // Populate cleanup inventory before touching any resource — retry can
        // later resume from exactly what we know we created.
        java.util.Map<String, String> inventory = new java.util.HashMap<>();
        if (stackCreated) {
            String stackName = "tapstate-cluster-" + clusterId;
            inventory.put("cloudFormationStackName", stackName);
            if (latest.getCloudFormationStackId() != null) {
                inventory.put("cloudFormationStackId", latest.getCloudFormationStackId());
            }
        }
        if (dnsCreated) {
            inventory.put("dnsRecord", latest.getDnsRecord());
        }
        if (latest.getMdbAllocationId() != null) {
            inventory.put("mdbAllocationId", latest.getMdbAllocationId().toHexString());
        }
        latest.setCleanupInventory(inventory);
        latest.setCleanupErrorCode(null);
        latest.setCleanupErrorDetail(null);
        latest.setCleanupFailedAt(null);
        if (!persistCleanupState(latest)) {
            Cluster current = clusterRepository.findById(latest.getId()).orElse(null);
            if (current != null && current.getState() == ClusterState.TERMINATED) {
                try {
                    terminate(current);
                } catch (Exception cleanupFailure) {
                    log.warn("[IaC] cancelled provisioning cleanup will be retried cluster={} type={}",
                        clusterId, cleanupFailure.getClass().getSimpleName());
                }
            }
            return;
        }

        // T2: query remote state before deciding whether to delete the stack.
        boolean skipStackDelete = false;
        if (stackCreated) {
            java.util.Optional<String> remoteStatus = compute.describeInfrastructureStatus(
                latest.getRegion(), clusterId);
            if (remoteStatus.isPresent() && "CREATE_COMPLETE".equals(remoteStatus.get())) {
                // Remote stack is healthy — we must be here because a post-stack
                // step (SSH wait, health check, TLS install, etc.) failed after
                // CloudFormation succeeded. Deleting it would destroy a working
                // resource the user just paid to create. Skip stack cleanup and
                // surface a distinct "remote healthy, post-stack failed" marker
                // so retry can resume safely.
                skipStackDelete = true;
                log.warn("[IaC] compensate skipping stack deletion — remote stack is CREATE_COMPLETE, "
                    + "post-stack step failed. cluster={}", clusterId);
            } else if (remoteStatus.isEmpty()) {
                // Provider cannot report remote state (default for non-AWS, or
                // AWS describe call itself failed). Trust durable state only.
                log.debug("[IaC] compensate has no remote state for cluster={}, trusting durable state", clusterId);
            } else {
                // Remote state is NOT CREATE_COMPLETE (e.g. ROLLBACK_COMPLETE,
                // DELETE_COMPLETE already, or unknown failure). Proceed with
                // cleanup — deleting a failed stack is always safe.
                log.info("[IaC] compensate remote stack status={} cluster={}, proceeding with cleanup",
                    remoteStatus.get(), clusterId);
            }
        }

        compensate(latest, stackCreated, dnsCreated, skipStackDelete);
    }

    /**
     * Terminate the node and remove its DNS name.
     * <p>
     * On failure, persists a cleanup error on the Cluster document so the user
     * can discover and retry. The exception still propagates so callers can
     * decide whether to retry or escalate.
     *
     * @throws IacException when a resource cannot be reclaimed
     */
    public void terminate(Cluster cluster) {
        terminate(cluster, false);
    }

    /** Clean up a failed attempt without releasing the Cluster's allowlisted identity. */
    public void prepareForRetry(Cluster cluster) {
        terminate(cluster, true);
    }

    private void terminate(Cluster cluster, boolean retry) {
        String clusterId = cluster.getId().toHexString();
        log.info("[IaC] terminate start cluster={}", clusterId);

        // T5: ensure cleanup inventory is populated before we start — callers
        // (scheduleRetry, startAsyncTermination, compensate) rely on it existing
        // before they attempt cleanup. Re-write is idempotent.
        java.util.Map<String, String> inventory = cluster.getCleanupInventory();
        if (inventory == null) {
            inventory = new java.util.HashMap<>();
            if ("CREATE_COMPLETE".equals(cluster.getCloudFormationStackStatus())
                || cluster.getEc2InstanceId() != null) {
                inventory.put("cloudFormationStackName", "tapstate-cluster-" + clusterId);
                if (cluster.getCloudFormationStackId() != null) {
                    inventory.put("cloudFormationStackId", cluster.getCloudFormationStackId());
                }
            }
            if (cluster.getDnsRecord() != null) {
                inventory.put("dnsRecord", cluster.getDnsRecord());
            }
            if (cluster.getMdbAllocationId() != null) {
                inventory.put("mdbAllocationId", cluster.getMdbAllocationId().toHexString());
            }
            cluster.setCleanupInventory(inventory);
            persistCleanupState(cluster);
        }

        try {
            runStep(cluster, DeploymentOperation.TERMINATE_EC2,
                "EC2 node terminated", () -> {
                    if (retry) {
                        compute.terminateNodeForRetry(cluster.getRegion(), clusterId);
                    } else {
                        compute.terminate(cluster.getRegion(), clusterId);
                    }
                    cluster.setCloudFormationStackStatus("DELETE_COMPLETE");
                    cluster.setCleanupInventory(null);
                    cluster.setCleanupErrorCode(null);
                    cluster.setCleanupErrorDetail(null);
                    cluster.setCleanupFailedAt(null);
                    persistCleanupState(cluster);
                });

            String domain = dnsRecords.recordName(cluster);
            runStep(cluster, DeploymentOperation.DELETE_DNS_RECORD,
                domain != null && !domain.isBlank() ? "DNS record deleted" : "No DNS record to delete",
                () -> {
                    if (domain != null && !domain.isBlank() && !dnsRecords.deleteIfUnowned(cluster)) {
                        throw new IacException(AppErrorCode.IAC_DNS_PROVIDER_FAILED,
                            "DNS record is being updated by another Cluster operation");
                    }
                });
            log.info("[IaC] terminate complete cluster={}", clusterId);
        } catch (IacException e) {
            // T5: persist cleanup failure on Cluster — user can discover and retry.
            persistCleanupFailure(cluster, "IAC_COMPUTE_TERMINATE_FAILED", safeMessage(e));
            persistCleanupState(cluster);
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
                // cloud-init writes the SSH key early (before apt), so SSH
                // opens ~20s after boot — but packages (docker, nginx) are still
                // installing in the background. Block until cloud-init fully
                // finishes AND docker is actually on PATH, otherwise the next
                // step fails with "sudo: docker: command not found".
                CommandResult result = session.exec(
                    "cloud-init status --wait >/dev/null && docker --version >/dev/null && echo ready; echo \"$SSH_CONNECTION\"",
                    Duration.ofMinutes(2));
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

    /**
     * Pull runtime images with up to 3 retries. Fresh EC2 nodes sometimes hit a
     * transient registry hiccup (node-to-registry TCP handshake fails, rate
     * limit, or ECR auth races for the first pull on the new node). The retry
     * is cheap — a failed pull leaves nothing half-applied — so a node that
     * can pull on attempt 2 or 3 is not actually "broken enough to fail the
     * whole deploy" even if the first attempt timed out.
     */
    private void pullWithRetries(SshSession session, String clusterId) throws java.io.IOException {
        int maxAttempts = 3;
        Duration attemptTimeout = PULL_TIMEOUT.dividedBy(maxAttempts);
        Exception lastFailure = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (attempt > 1) {
                log.warn("[IaC] docker compose pull retry attempt {}/3 cluster={}", attempt, clusterId);
            }
            try {
                CommandResult result = session.exec(
                    "cd /opt/tapstate-runtime && sudo docker compose pull",
                    attemptTimeout);
                if (result.succeeded()) {
                    if (attempt > 1) {
                        log.info("[IaC] docker compose pull succeeded on attempt {} cluster={}", attempt, clusterId);
                    }
                    return;
                }
                lastFailure = new IacException(AppErrorCode.IAC_SSH_COMMAND_FAILED,
                    "docker compose pull failed (attempt " + attempt + "/" + maxAttempts
                        + "): exit=" + result.exitCode() + " " + abbreviate(result.stderr()));
            } catch (java.io.IOException e) {
                lastFailure = e;
            }
            // Brief backoff before retry so a throttled registry has time to settle
            try { Thread.sleep(2_000L); } catch (InterruptedException ignored) { }
        }
        throw new IacException(AppErrorCode.IAC_SSH_COMMAND_FAILED,
            lastFailure, "docker compose pull failed after " + maxAttempts
                + " attempts — giving up. Last error: " + safeMessage(lastFailure));
    }

    /** Reverse-order compensation of resources already created. */
    private void compensate(Cluster cluster, boolean stackCreated, boolean dnsCreated, boolean skipStackDelete) {
        String clusterId = cluster.getId().toHexString();
        if (dnsCreated) {
            try {
                if (dnsRecords.deleteIfUnowned(cluster)) {
                    cluster.setDnsRecord(null);
                } else {
                    log.info("[IaC] DNS cleanup deferred while another operation owns hostname cluster={}",
                        clusterId);
                }
            } catch (Exception e) {
                // DNS cleanup failure is logged but not surfaced to the user —
                // the terminated-resource sweeper or a later retry can reclaim it.
                log.error("[IaC] compensation failed to delete DNS cluster={}: {}",
                    clusterId, safeMessage(e));
            }
        }
        if (stackCreated) {
            if (skipStackDelete) {
                // Preserve a healthy stack after a post-stack failure so retry can resume it.
                cluster.setCleanupErrorCode("REMOTE_STACK_HEALTHY");
                cluster.setCleanupErrorDetail(
                    "CloudFormation stack is CREATE_COMPLETE on AWS but post-stack provisioning step failed. "
                        + "Stack deletion skipped to preserve healthy infrastructure. Retry will resume from this stack.");
                cluster.setCleanupFailedAt(Instant.now());
                cluster.setCloudFormationStackStatus("CREATE_COMPLETE");
                log.warn("[IaC] compensate stack deletion skipped — remote stack healthy cluster={}", clusterId);
            } else {
                try {
                    // This is a failed provisioning attempt, not Cluster deletion:
                    // preserve the tagged EIP so a later retry reuses the same address.
                    compute.terminateNodeForRetry(cluster.getRegion(), clusterId);
                    cluster.setCloudFormationStackStatus("DELETE_COMPLETE");
                    cluster.setCleanupInventory(null);
                    cluster.setCleanupErrorCode(null);
                    cluster.setCleanupErrorDetail(null);
                    cluster.setCleanupFailedAt(null);
                } catch (Exception e) {
                    // Do not mask the original provisioning error; expose the cleanup
                    // failure so the user can repair its cause and retry.
                    persistCleanupFailure(cluster, "IAC_COMPUTE_TERMINATE_FAILED", safeMessage(e));
                    log.error("[IaC] compensation failed to delete stack cluster={}: {}",
                        clusterId, safeMessage(e));
                }
            }
        }
        try {
            persistCleanupState(cluster);
        } catch (Exception e) {
            log.error("[IaC] compensation state persist failed cluster={}: {}",
                clusterId, safeMessage(e));
        }
    }

    /** Persist a cleanup failure onto the Cluster document. Idempotent: last write wins. */
    private void persistCleanupFailure(Cluster cluster, String errorCode, String detail) {
        cluster.setCleanupErrorCode(errorCode);
        cluster.setCleanupErrorDetail(detail);
        cluster.setCleanupFailedAt(Instant.now());
        // Keep cleanupInventory — it was written before we started cleaning and
        // is exactly what retryCleanup needs to know which resources to target.
    }

    /**
     * Query the remote infrastructure status for a Cluster. Public because callers
     * outside this class (e.g. FSM scheduleRetry) need the same "should we delete?"
     * decision that compensate() uses.
     *
     * @return infrastructure status string if provider reports it, empty otherwise
     */
    public java.util.Optional<String> describeRemoteStackStatus(String region, String clusterId) {
        return compute.describeInfrastructureStatus(region, clusterId);
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
        mongoTemplate.updateFirst(provisioningQuery(cluster),
            new Update().set("provisionErrorCode", code).set("provisionErrorDetail", detail), Cluster.class);
        return ProvisionResult.failed(code, detail);
    }

    /**
     * EC2 type for this Cluster.
     * <p>
     * The spec the customer bought decides it, so a 4C16G subscription is
     * provisioned on a 4-vCPU machine instead of whatever the environment default
     * happens to be. The configured default only covers Clusters whose spec
     * predates the catalogue carrying a provider type.
     */
    String instanceTypeFor(Cluster cluster) {
        String specKey = cluster.getSpecKey();
        if (specKey != null && !specKey.isBlank()) {
            String fromSpec = specConfigService.findByKey(specKey)
                .map(SpecConfig::getAwsEc2Spec)
                .filter(type -> type != null && !type.isBlank())
                .orElse(null);
            if (fromSpec != null) {
                return fromSpec;
            }
        }
        log.warn("[IaC] spec '{}' carries no awsEc2Spec (cluster={}) — using the environment default {}",
            specKey, cluster.getId(), properties.getAws().getInstanceType());
        return properties.getAws().getInstanceType();
    }

    private void persist(Cluster cluster, Update update) {
        if (mongoTemplate.updateFirst(provisioningQuery(cluster), update, Cluster.class).getMatchedCount() == 0) {
            throw new IacException(AppErrorCode.IAC_COMPUTE_PROVISION_FAILED,
                "Cluster provisioning was cancelled before its resource update could be recorded");
        }
    }

    /** Persist only cleanup-owned fields while the SDK identity still matches this Cluster. */
    private boolean persistCleanupState(Cluster cluster) {
        Instant updatedAt = Instant.now();
        cluster.setUpdatedAt(updatedAt);
        Update update = new Update().set("updatedAt", updatedAt);
        setOrUnset(update, "dnsRecord", cluster.getDnsRecord());
        setOrUnset(update, "cloudFormationStackStatus", cluster.getCloudFormationStackStatus());
        setOrUnset(update, "cleanupInventory", cluster.getCleanupInventory());
        setOrUnset(update, "cleanupErrorCode", cluster.getCleanupErrorCode());
        setOrUnset(update, "cleanupErrorDetail", cluster.getCleanupErrorDetail());
        setOrUnset(update, "cleanupFailedAt", cluster.getCleanupFailedAt());
        Query query = Query.query(Criteria.where("_id").is(cluster.getId())
            .and("sdkStaticToken").is(cluster.getSdkStaticToken()));
        return mongoTemplate.updateFirst(query, update, Cluster.class).getMatchedCount() == 1;
    }

    private static void setOrUnset(Update update, String field, Object value) {
        if (value == null) {
            update.unset(field);
        } else {
            update.set(field, value);
        }
    }

    private static Query provisioningQuery(Cluster cluster) {
        return Query.query(Criteria.where("_id").is(cluster.getId())
            .and("state").is(ClusterState.PROVISIONING)
            .and("sdkStaticToken").is(cluster.getSdkStaticToken()));
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
