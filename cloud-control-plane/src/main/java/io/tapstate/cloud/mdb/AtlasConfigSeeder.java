package io.tapstate.cloud.mdb;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Seeds an Activated AtlasConfig at app startup — dev/IT profile only.
 * <p>
 * Production provides the real Atlas API key via admin DB write (NOT this class).
 * This class uses mock-looking credentials so AtlasMdbService.mock=true tests
 * still exercise allocation engine logic without real HTTP calls.
 * <p>
 * The partialFilterExpression unique index on atlas_configs {status: "Activated"}
 * ensures we never end up with two Activated keys even if seeder runs again.
 */
@Component
@Profile("dev | it")
@RequiredArgsConstructor
@Slf4j
public class AtlasConfigSeeder implements ApplicationRunner {

    private final AtlasConfigRepository repo;

    @Override
    public void run(ApplicationArguments args) {
        if (repo.findFirstByStatus("Activated").isPresent()) {
            log.info("[AtlasConfigSeeder] Activated AtlasConfig already present — skipping seed");
            return;
        }

        AtlasConfig seed = AtlasConfig.builder()
            .publicKey("dev-mock-public-key")
            .privateKey("dev-mock-private-key-NEVER-USED-IN-IT")
            .domain("cloud.mongodb.com")
            .orgId("dev-org-id")
            .status("Activated")
            .build();

        AtlasConfig saved = repo.save(seed);
        log.info("[AtlasConfigSeeder] Seeded dev AtlasConfig {} (Activated, mock credentials)", saved.getId());
    }
}
