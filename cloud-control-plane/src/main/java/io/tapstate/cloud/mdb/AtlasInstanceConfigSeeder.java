package io.tapstate.cloud.mdb;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Seeds an ACTIVE Atlas instance config at app startup if none exists.
 * <p>
 * The IT profile (default dev) seeds a single shared instance for ap-southeast-1
 * with mock Atlas credentials — the AtlasMdbService mock=true flag means no
 * real API calls are made, but the allocation engine's findAndModify capacity
 * reservation logic is exercised end-to-end.
 * <p>
 * Production deployments provide Atlas instance configs via admin API / seed
 * data (NOT via this class — the mock credentials must never reach prod).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AtlasInstanceConfigSeeder implements ApplicationRunner {

    private final AtlasInstanceConfigRepository repo;

    @Override
    public void run(ApplicationArguments args) {
        if (repo.count() > 0) {
            log.info("[AtlasSeeder] {} Atlas instance config(s) already present — skipping seed", repo.count());
            return;
        }

        AtlasInstanceConfig seed = AtlasInstanceConfig.builder()
            .displayName("Singapore Shared M10 (dev/IT seed)")
            .region("ap-southeast-1")
            .atlasProjectId("tapstate-dev-project")
            .atlasClusterName("tapstate-shared-ap-southeast-1")
            .atlasTier("M10")
            .maxCustomers(100)
            .currentCustomers(0)
            // Mock credentials — AtlasMdbService.mock=true means these are never used
            .atlasPublicKey("dev-mock-public-key")
            .atlasPrivateKey("dev-mock-private-key")
            .connectionString("mongodb+srv://tapstate-shared.mongodb.net")
            .status(AtlasInstanceConfig.ConfigStatus.ACTIVE)
            .build();

        AtlasInstanceConfig saved = repo.save(seed);
        log.info("[AtlasSeeder] Seeded Atlas instance {} (region={}, capacity {}/{}, mock credentials)",
            saved.getId(), saved.getRegion(), saved.getCurrentCustomers(), saved.getMaxCustomers());
    }
}
