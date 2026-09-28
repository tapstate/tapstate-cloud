package io.tapstate.cloud.region;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Idempotent region seed — ensures Week 2 open region {@code ap-southeast-1}
 * exists on every startup (dev preview, IT, local cluster). Safe to run repeatedly.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RegionSeeder implements ApplicationRunner {

    private final RegionRepository regionRepository;

    @Override
    public void run(ApplicationArguments args) {
        if (!regionRepository.existsById("ap-southeast-1")) {
            Region sg = Region.builder()
                .code("ap-southeast-1")
                .name("Asia Pacific (Singapore)")
                .open(true)
                .defaultSpecKey("basic")
                .sortOrder(1)
                .build();
            regionRepository.save(sg);
            log.info("[RegionSeeder] Seeded ap-southeast-1");
        }
    }
}
