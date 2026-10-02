package io.tapstate.cloud.mdb;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Seeds one ACTIVE MdbCluster at app startup — dev/IT profile only.
 * <p>
 * Production provisions real shared Atlas clusters via admin API (NOT this class).
 * The seeder creates a single ap-southeast-1 cluster with a mock connection string
 * so allocation engine findAndModify slot reservation logic can run end-to-end
 * in IT tests (mockMode=true means no real Atlas HTTP calls).
 */
@Component
@Profile("dev | it")
@RequiredArgsConstructor
@Slf4j
public class MdbClusterSeeder implements ApplicationRunner {

    private final MdbClusterRepository mdbRepo;
    private final AtlasConfigRepository atlasConfigRepo;

    @Override
    public void run(ApplicationArguments args) {
        if (mdbRepo.count() > 0) {
            log.info("[MdbClusterSeeder] {} MdbCluster(s) already present — skipping seed", mdbRepo.count());
            return;
        }

        // Link to whatever AtlasConfig is Activated (dev seeder runs just before this one)
        AtlasConfig cfg = atlasConfigRepo.findFirstByStatus("Activated").orElse(null);
        if (cfg == null) {
            log.warn("[MdbClusterSeeder] No Activated AtlasConfig — MdbCluster will be seeded without atlasConfigId link");
        }

        MdbCluster seed = MdbCluster.builder()
            .atlasConfigId(cfg != null ? cfg.getId() : null)
            .projectId("tapstate-dev-project")
            .name("tapstate-shared-ap-southeast-1")
            .region("ap-southeast-1")
            .maxAllocations(100)
            .currentAllocations(0)
            .connectionString("mongodb+srv://tapstate-shared.mongodb.net")
            .status("ACTIVE")
            .build();

        MdbCluster saved = mdbRepo.save(seed);
        log.info("[MdbClusterSeeder] Seeded MdbCluster {} (region={}, capacity {}/{}, atlasConfigId={})",
            saved.getId(), saved.getRegion(),
            saved.getCurrentAllocations(), saved.getMaxAllocations(),
            saved.getAtlasConfigId());
    }
}
