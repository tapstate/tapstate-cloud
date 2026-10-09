package io.tapstate.cloud.version;

import io.tapstate.cloud.counter.CounterService;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Bootstraps the global Cluster version catalog in every environment.
 * <p>
 * On startup, an empty {@code cluster_versions} collection is seeded with a
 * single ACTIVE {@code 0.6.0} STABLE record mapping to the runtime image tag
 * already in use before version management existed. The count guard makes the
 * seed idempotent; if the bootstrap record is ever deleted, the next startup
 * heals the catalog automatically (allocating a new sequence number, which is
 * fine — only ordering matters, never the number itself).
 * <p>
 * Runs with no profile restriction on purpose: dev, test and production all
 * need a deployable version to exist.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ClusterVersionSeeder {

    /** Bootstrap version: the image tag in use before version management. */
    public static final String BOOTSTRAP_VERSION = "0.6.0";

    private static final String BOOTSTRAP_BRANCH = "main";
    private static final String BOOTSTRAP_NOTES =
        "Bootstrap version mapping to the runtime image in use before version management existed";

    private final ClusterVersionRepository repository;
    private final CounterService counterService;

    @PostConstruct
    public void seedBootstrapVersion() {
        if (repository.count() > 0) {
            return;
        }

        ClusterVersion bootstrap = ClusterVersion.builder()
            .version(BOOTSTRAP_VERSION)
            .versionNumber(counterService.getNextSeq(ClusterVersionService.COUNTER_NAME))
            .releaseCode(ClusterReleaseCode.STABLE)
            .releaseDate(Instant.now())
            .releaseNotes(BOOTSTRAP_NOTES)
            .state(ClusterVersionState.ACTIVE)
            .git(GitInfo.builder()
                .branch(BOOTSTRAP_BRANCH)
                .commitId(BOOTSTRAP_VERSION)
                .extra(Map.of())
                .build())
            .build();

        repository.save(bootstrap);
        log.info("Seeded bootstrap Cluster version: {}", BOOTSTRAP_VERSION);
    }
}
