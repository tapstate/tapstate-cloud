package io.tapstate.cloud.tls;

import io.tapstate.cloud.cluster.Cluster;
import io.tapstate.cloud.cluster.ClusterRepository;
import io.tapstate.cloud.cluster.ClusterState;
import io.tapstate.cloud.iac.IacProperties;
import io.tapstate.cloud.iac.crypto.SshKeyCrypto;
import io.tapstate.cloud.iac.ssh.SshSession;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pushes newer TLS certificates to already-running Cluster nodes.
 * <p>
 * Every sweep selects READY and DEGRADED Clusters, resolves the latest valid
 * certificate covering each Cluster DNS name, and — when it differs from the
 * certificate id recorded on the Cluster document — uploads the new material
 * over SSH and reloads Nginx. Per-Cluster failures never abort the sweep; the
 * unchanged {@code tlsCertificateId} makes the next sweep retry automatically.
 * <p>
 * The sweep only runs in real provisioning mode: in mock mode nodes do not
 * exist. ShedLock serializes sweeps across control-plane replicas.
 */
@Service
@Slf4j
public class TlsCertificateDistributorService {

    /** Cluster states whose node is live and must receive certificate updates. */
    private static final List<ClusterState> ONLINE_STATES = List.of(
        ClusterState.READY, ClusterState.DEGRADED);

    private final ClusterRepository clusterRepository;
    private final TlsCertificateService certificateService;
    private final TlsNodeInstaller installer;
    private final SshKeyCrypto keyCrypto;
    private final IacProperties properties;
    private final MongoTemplate mongoTemplate;

    public TlsCertificateDistributorService(ClusterRepository clusterRepository,
                                            TlsCertificateService certificateService,
                                            TlsNodeInstaller installer,
                                            SshKeyCrypto keyCrypto,
                                            IacProperties properties,
                                            MongoTemplate mongoTemplate) {
        this.clusterRepository = clusterRepository;
        this.certificateService = certificateService;
        this.installer = installer;
        this.keyCrypto = keyCrypto;
        this.properties = properties;
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * Distribution sweep. Runs with a configurable fixed delay; the distributed
     * lock prevents two replicas pushing certificates concurrently.
     */
    @Scheduled(fixedDelayString = "${tapstate.cloud.iac.tls.distribution-interval-ms:60000}")
    @SchedulerLock(name = "tlsCertificateDistributionLock",
        lockAtMostFor = "5m", lockAtLeastFor = "0s")
    public void distributeNewCertificates() {
        if (!"real".equalsIgnoreCase(properties.getMode())) {
            return;
        }
        List<Cluster> clusters;
        try {
            clusters = clusterRepository
                .findByStateInAndEc2PublicIpIsNotNullAndSshPrivateKeyEncIsNotNull(ONLINE_STATES);
        } catch (Exception e) {
            log.error("[TLS-distribution] sweep failed to load online clusters: {}", e.getMessage());
            return;
        }
        if (clusters.isEmpty()) {
            return;
        }

        int updated = 0;
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<Boolean>> results = clusters.stream()
                .map(cluster -> pool.submit(() -> distributeToCluster(cluster)))
                .toList();
            for (java.util.concurrent.Future<Boolean> result : results) {
                try {
                    if (Boolean.TRUE.equals(result.get())) {
                        updated++;
                    }
                } catch (Exception e) {
                    log.error("[TLS-distribution] cluster update task failed: {}", e.getMessage());
                }
            }
        }
        if (updated > 0) {
            log.info("[TLS-distribution] pushed new TLS certificates to {}/{} online cluster(s)",
                updated, clusters.size());
        }
    }

    /**
     * Update one Cluster node when a newer valid certificate exists.
     *
     * @return {@code true} when a new certificate was installed and recorded
     */
    private boolean distributeToCluster(Cluster cluster) {
        String clusterId = cluster.getId().toHexString();
        String host = cluster.getPublicDns() != null && !cluster.getPublicDns().isBlank()
            ? cluster.getPublicDns() : cluster.getDnsRecord();
        if (host == null || host.isBlank()) {
            return false;
        }

        Optional<TlsCertificate> latest = certificateService.findValidForHost(host);
        if (latest.isEmpty()) {
            return false;
        }
        TlsCertificate certificate = latest.get();
        if (certificate.getId().equals(cluster.getTlsCertificateId())) {
            return false;
        }

        String privateKeyPem;
        try {
            privateKeyPem = keyCrypto.decrypt(
                cluster.getSshPrivateKeyEnc(), properties.getCrypto().getMasterKey());
        } catch (Exception e) {
            log.warn("[TLS-distribution] cluster {} skipped: cannot decrypt SSH key: {}",
                clusterId, e.getMessage());
            return false;
        }

        try (SshSession session = installer.connect(cluster.getEc2PublicIp(), privateKeyPem)) {
            installer.install(session, host, certificate);
        } catch (Exception e) {
            log.warn("[TLS-distribution] cluster {} cert push to {} failed: {}",
                clusterId, cluster.getEc2PublicIp(), e.getMessage());
            return false;
        }

        try {
            if (mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(cluster.getId())
                    .and("state").in(ONLINE_STATES).and("ec2InstanceId").is(cluster.getEc2InstanceId())
                    .and("ec2PublicIp").is(cluster.getEc2PublicIp())),
                new Update().set("tlsCertificateId", certificate.getId()), Cluster.class).getMatchedCount() == 0) {
                return false;
            }
            log.info("[TLS-distribution] cluster {} now serves certificate {} ({})",
                clusterId, certificate.getId().toHexString(), certificate.getDomain());
            return true;
        } catch (Exception e) {
            log.error("[TLS-distribution] cluster {} certificate installed but state save failed: {}",
                clusterId, e.getMessage());
            return false;
        }
    }
}
