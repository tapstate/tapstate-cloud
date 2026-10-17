package io.tapstate.cloud.iac;

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

    public void publish(String fqdn, String ipv4, ObjectId ownerClusterId, Runnable persistOwner) {
        if (!withRecordLock(fqdn, () -> {
            boolean ownerIsLive = clusterRepository.findById(ownerClusterId)
                .map(cluster -> cluster.getState() != ClusterState.TERMINATED)
                .orElse(false);
            if (!ownerIsLive) {
                throw new IacException(AppErrorCode.IAC_DNS_PROVIDER_FAILED,
                    "A terminated Cluster cannot publish its DNS record");
            }
            dns.upsertARecord(fqdn, ipv4);
            persistOwner.run();
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
