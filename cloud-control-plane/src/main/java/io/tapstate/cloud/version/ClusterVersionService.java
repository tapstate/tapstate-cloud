package io.tapstate.cloud.version;

import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.BusinessException;
import io.tapstate.cloud.counter.CounterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Publish and query the global Cluster version catalog.
 * <p>
 * The catalog is operator-managed configuration: listing returns only ACTIVE
 * versions ordered by descending {@code versionNumber}, "latest" is the first
 * row of that list, and delisting a version never invalidates a Cluster that
 * already pinned it at subscription time.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ClusterVersionService {

    /** Name of the sequence backing {@link ClusterVersion#getVersionNumber()}. */
    public static final String COUNTER_NAME = "cluster_version";

    private final ClusterVersionRepository repository;
    private final CounterService counterService;

    /** All ACTIVE versions, newest first. */
    public List<ClusterVersion> listActiveVersions() {
        return repository.findByStateOrderByVersionNumberDesc(ClusterVersionState.ACTIVE);
    }

    /** The newest ACTIVE version, or empty when none is listed. */
    public Optional<ClusterVersion> findLastActive() {
        return repository.findFirstByStateOrderByVersionNumberDesc(ClusterVersionState.ACTIVE);
    }

    /**
     * The newest ACTIVE version — the default for new subscriptions that do not
     * specify one.
     *
     * @throws BusinessException 503 {@link AppErrorCode#CLUSTER_VERSION_UNAVAILABLE} when none is listed
     */
    public ClusterVersion findClusterLastVersion() {
        return findLastActive()
            .orElseThrow(() -> new BusinessException(AppErrorCode.CLUSTER_VERSION_UNAVAILABLE, 503));
    }

    /**
     * Resolve a version explicitly requested for a new subscription.
     *
     * @throws BusinessException 404 when the version does not exist
     * @throws BusinessException 409 when it exists but is OFFLINE
     */
    public ClusterVersion findActiveByVersion(String version) {
        ClusterVersion found = repository.findByVersion(version)
            .orElseThrow(() -> new BusinessException(AppErrorCode.CLUSTER_VERSION_NOT_FOUND, 404, version));
        if (found.getState() != ClusterVersionState.ACTIVE) {
            throw new BusinessException(AppErrorCode.CLUSTER_VERSION_NOT_ACTIVE, 409, version);
        }
        return found;
    }

    /**
     * Publish a new Cluster version. The version string is accepted in relaxed
     * mode — non-empty after trim, no format whitelist; it must be unique.
     * {@code versionNumber} is allocated from the atomic counter, the record is
     * inserted ACTIVE with the current time as release date.
     *
     * @throws BusinessException 400 for a blank version
     * @throws BusinessException 409 when the version already exists
     */
    public ClusterVersion releaseCluster(ReleaseClusterRequest request) {
        String version = request.version() == null ? "" : request.version().trim();
        if (version.isEmpty()) {
            throw new BusinessException(AppErrorCode.BAD_REQUEST, 400, "version is required");
        }
        if (repository.existsByVersion(version)) {
            throw new BusinessException(AppErrorCode.CLUSTER_VERSION_DUPLICATE, 409, version);
        }

        ClusterVersion document = ClusterVersion.builder()
            .version(version)
            .versionNumber(counterService.getNextSeq(COUNTER_NAME))
            .releaseCode(request.releaseCode())
            .releaseDate(Instant.now())
            .releaseNotes(request.releaseNotes())
            .state(ClusterVersionState.ACTIVE)
            .git(request.git())
            .build();

        try {
            ClusterVersion saved = repository.save(document);
            log.info("Cluster version released: version={} versionNumber={} releaseCode={}",
                saved.getVersion(), saved.getVersionNumber(), saved.getReleaseCode());
            return saved;
        } catch (DuplicateKeyException e) {
            // Concurrent release of the same version or a counter collision.
            throw new BusinessException(AppErrorCode.CLUSTER_VERSION_DUPLICATE, 409, version);
        }
    }

    /**
     * Flip the listing state of a version (list / delist).
     *
     * @throws BusinessException 404 when the version does not exist
     */
    public ClusterVersion updateState(String version, ClusterVersionState target) {
        ClusterVersion found = repository.findByVersion(version)
            .orElseThrow(() -> new BusinessException(AppErrorCode.CLUSTER_VERSION_NOT_FOUND, 404, version));
        found.setState(target);
        return repository.save(found);
    }
}
