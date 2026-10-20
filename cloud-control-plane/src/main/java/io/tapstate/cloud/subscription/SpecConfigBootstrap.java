package io.tapstate.cloud.subscription;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Bootstraps the {@code spec_config} collection from {@code classpath:spec-config.json}
 * on application start.
 * <p>
 * Execution order is deterministic (see below) and the bootstrap is idempotent —
 * re-running after a JSON edit upserts updated records, marking DB records whose
 * key disappeared from the JSON as {@code enabled=false} rather than deleting them
 * (they may have active Subscriptions still referencing the old key).
 *
 * <h3>Semantics</h3>
 * <ul>
 *   <li>Keys present in the JSON: upsert into {@code spec_config} with {@code enabled=true}.</li>
 *   <li>Keys that were present before but are no longer in the JSON: set {@code enabled=false} (deprecated, not deleted).</li>
 *   <li>All other fields follow the JSON — {@code enabled} defaulted to {@code true} if absent in the source.</li>
 * </ul>
 *
 * <h3>Execution order</h3>
 * Runs after {@code MongoTemplate} is available but before any bean that depends on
 * the spec catalog (StripeProductService, SubscriptionService, etc.) performs a
 * refresh or creates Subscriptions. Implemented as {@link ApplicationRunner} with
 * a very early order — the framework guarantees all {@code ApplicationRunner}s
 * and {@code CommandLineRunner}s execute after {@code @PostConstruct}s, so placing
 * this bootstrap in the {@code ApplicationRunner} phase naturally avoids a race
 * with any bean that might {@code @PostConstruct}-pull from the catalog.
 */
@Slf4j
@Component
@Order(1)
public class SpecConfigBootstrap implements ApplicationRunner {

    private static final String SPEC_CONFIG_CLASSPATH = "classpath:spec-config.json";

    private final MongoTemplate mongoTemplate;
    private final ObjectMapper objectMapper;

    public SpecConfigBootstrap(MongoTemplate mongoTemplate, ObjectMapper objectMapper) {
        this.mongoTemplate = mongoTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources(SPEC_CONFIG_CLASSPATH);
        if (resources.length == 0 || !resources[0].exists()) {
            log.warn("spec-config.json not found on classpath — skipping spec_config bootstrap");
            return;
        }

        List<SpecConfig> configs = objectMapper.readValue(
                resources[0].getInputStream(),
                new TypeReference<List<SpecConfig>>() {});

        log.info("SpecConfigBootstrap: loading {} spec definitions from {}",
                configs.size(), SPEC_CONFIG_CLASSPATH);

        Set<String> fileKeys = configs.stream()
                .map(SpecConfig::getKey).collect(Collectors.toSet());

        int upserted = 0;
        for (SpecConfig sc : configs) {
            Query query = Query.query(Criteria.where("key").is(sc.getKey()));
            Update update = new Update()
                    .set("displayName", sc.getDisplayName())
                    .set("cpuCores", sc.getCpuCores())
                    .set("memoryGb", sc.getMemoryGb())
                    .set("trialDays", sc.getTrialDays())
                    .set("mdbSizeGb", sc.getMdbSizeGb())
                    .set("enabled", sc.isEnabled())
                    .set("updatedAt", Instant.now())
                    .setOnInsert("_id", new ObjectId())
                    .setOnInsert("createdAt", Instant.now());
            mongoTemplate.upsert(query, update, SpecConfig.class);
            upserted++;
        }

        // Mark DB records NOT in the JSON as deprecated (enabled=false),
        // but DO NOT DELETE — existing Subscriptions still reference the old specKey.
        List<SpecConfig> existing = mongoTemplate.findAll(SpecConfig.class);
        int deprecated = 0;
        Set<String> existingKeys = existing.stream()
                .map(SpecConfig::getKey).collect(Collectors.toSet());
        for (String dbKey : new HashSet<>(existingKeys)) {
            if (!fileKeys.contains(dbKey)) {
                mongoTemplate.updateFirst(
                        Query.query(Criteria.where("key").is(dbKey)),
                        new Update().set("enabled", false).set("updatedAt", Instant.now()),
                        SpecConfig.class);
                deprecated++;
                log.info("SpecConfigBootstrap: key '{}' removed from spec-config.json — marking enabled=false", dbKey);
            }
        }

        // Mark keys that WERE deprecated but are back in the JSON — re-enable.
        // The upsert above already sets enabled=true; nothing extra needed.

        log.info("SpecConfigBootstrap: upserted {} spec definitions, deprecated {}",
                upserted, deprecated);
    }
}
