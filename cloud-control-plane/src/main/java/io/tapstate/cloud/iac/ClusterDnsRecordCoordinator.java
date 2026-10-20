package io.tapstate.cloud.iac;

import io.tapstate.cloud.cluster.Cluster;
import io.tapstate.cloud.cluster.ClusterRepository;
import io.tapstate.cloud.cluster.ClusterState;
import io.tapstate.cloud.common.api.AppErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class ClusterDnsRecordCoordinator {

    private static final Duration LOCK_AT_MOST_FOR = Duration.ofMinutes(2);

    private final LockProvider lockProvider;
    private final ClusterRepository clusterRepository;
    private final DnsInfrastructure dns;
    private final ClusterDomainName clusterDomainName;

    public void publish(String fqdn, String ipv4, ObjectId ownerClusterId, Runnable persistOwner) {
        if (!withRecordLock(fqdn, () -> {
            boolean ownerIsLive = clusterRepository.findById(ownerClusterId)
                .map(cluster -> cluster.getState() != ClusterState.TERMINATED)
                .orElse(false);
            if (!ownerIsLive) {
                throw new IacException(AppErrorCode.IAC_DNS_PROVIDER_FAILED,
                    "A terminated Cluster cannot publish its DNS record");
            }
            // Persist the recoverable hostname before publication so a crash cannot strand an untracked record.
            persistOwner.run();
            dns.upsertARecord(fqdn, ipv4);
        })) {
            throw new IacException(AppErrorCode.IAC_DNS_PROVIDER_FAILED,
                "DNS record is being updated by another Cluster operation");
        }
    }

    /** Deletes a record only when no other live Cluster has claimed its hostname. */
    public boolean deleteIfUnowned(String fqdn, ObjectId terminatingClusterId) {
        return withRecordLock(fqdn, () -> {
            if (clusterRepository.existsByPublicDnsAndStateNotAndIdNot(
                    fqdn, ClusterState.TERMINATED, terminatingClusterId)) {
                log.info("[DNS] preserving reused Cluster hostname fqdn={} terminatingCluster={}",
                    fqdn, terminatingClusterId.toHexString());
                return;
            }
            dns.deleteRecord(fqdn);
        });
    }

    public String recordName(Cluster cluster) {
        if (cluster.getDnsRecord() != null && !cluster.getDnsRecord().isBlank()) {
            return cluster.getDnsRecord();
        }
        if (cluster.getPublicDns() != null && !cluster.getPublicDns().isBlank()) {
            return cluster.getPublicDns();
        }
        if (cluster.getDnsLabel() == null || cluster.getDnsLabel().isBlank()) return null;
        return clusterDomainName.fqdn(cluster.getDnsLabel());
    }

    public boolean deleteIfUnowned(Cluster cluster) {
        String fqdn = recordName(cluster);
        return fqdn == null || deleteIfUnowned(fqdn, cluster.getId());
    }

    private boolean withRecordLock(String fqdn, Runnable operation) {
        Optional<SimpleLock> acquired = lockProvider.lock(new LockConfiguration(
            Instant.now(), lockName(fqdn), LOCK_AT_MOST_FOR, Duration.ZERO));
        if (acquired.isEmpty()) return false;
        try {
            operation.run();
            return true;
        } finally {
            acquired.get().unlock();
        }
    }

    private static String lockName(String fqdn) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(fqdn.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            return "tapstate-cluster-dns-" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
