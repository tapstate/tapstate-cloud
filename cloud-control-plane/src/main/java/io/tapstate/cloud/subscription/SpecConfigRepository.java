package io.tapstate.cloud.subscription;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for the platform-owned spec catalog ({@code spec_config} collection).
 * <p>
 * Spec keys are the {@code key} field (e.g. "2c8g", "4c16g", "8c32g") and form a
 * unique index. Every {@code key} also carries an {@code enabled} flag — when
 * {@code false}, no new Subscription can be created with that spec key.
 * <p>
 * The collection is bootstrapped from {@code classpath:spec-config.json} on
 * startup by {@link SpecConfigBootstrap}, and periodically refreshed from
 * the same source.
 */
public interface SpecConfigRepository extends MongoRepository<SpecConfig, String> {

    Optional<SpecConfig> findByKey(String key);

    List<SpecConfig> findByEnabledTrue();

    boolean existsByKeyAndEnabledTrue(String key);
}
