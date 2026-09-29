package io.tapstate.cloud.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.mongo.MongoLockProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Duration;

/**
 * ShedLock configuration for distributed scheduling.
 * Uses MongoDB as the lock provider to prevent duplicate JWKS rotation jobs.
 */
@Configuration
public class ShedLockConfig {

    /**
     * Lock provider using MongoDB for distributed scheduling.
     */
    @Bean
    public LockProvider lockProvider(MongoTemplate mongoTemplate) {
        return new MongoLockProvider(mongoTemplate.getDb());
    }
}
