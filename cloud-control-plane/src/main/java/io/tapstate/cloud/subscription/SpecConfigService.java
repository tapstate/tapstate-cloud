package io.tapstate.cloud.subscription;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Service facade over {@link SpecConfigRepository} — reads enabled specs and
 * validates subscription creation inputs against the platform catalog.
 * <p>
 * The source of truth for spec keys and their technical parameters is
 * {@code spec_config} (bootstrapped from {@code classpath:spec-config.json}).
 * Pricing is NOT here — that lives in per-channel collections
 * (e.g. {@code stripe_products}) keyed by the same {@code key}.
 */
@Service
public class SpecConfigService {

    private final SpecConfigRepository repository;

    public SpecConfigService(SpecConfigRepository repository) {
        this.repository = repository;
    }

    public Optional<SpecConfig> findByKey(String key) {
        return repository.findByKey(key);
    }

    public Optional<SpecConfig> findEnabledByKey(String key) {
        return repository.findByKey(key).filter(SpecConfig::isEnabled);
    }

    public List<SpecConfig> findAll() {
        return repository.findAll();
    }

    public List<SpecConfig> findAllEnabled() {
        return repository.findByEnabledTrue();
    }

    public boolean existsEnabled(String key) {
        return repository.existsByKeyAndEnabledTrue(key);
    }
}
