package io.tapstate.cloud.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.KeyPair;
import java.security.KeyPairGenerator;

/**
 * JWT signing KeyPair bean for the in-memory (rotation-disabled) fallback path.
 * <p>
 * When {@code tapstate.cloud.jwks.rotation-enabled=true}, production signing
 * goes through {@link JwksRotationService#buildActiveSigningKeyPair()} — it
 * rebuilds the KeyPair from MongoDB on every call and stays rotation-aware.
 * This bean is ONLY the dev-mode in-memory fallback and the ResolveController
 * JWKS endpoint fallback when MongoDB has no keys.
 * <p>
 * The {@code KID} constant was REMOVED — JWT signers must read kid from the
 * currently-active JWKS key's {@code kid} field, not a hardcoded string.
 */
@Configuration
@Slf4j
public class JwtKeysConfig {

    /**
     * Dev-mode in-memory fallback KeyPair — rotation is disabled or MongoDB
     * has no active keys. Callers that sign JWTs / callbacks must prefer
     * {@link JwksRotationService#buildActiveSigningKeyPair()} when rotation is
     * enabled; this bean is only used by ResolveController.jwks() and by
     * ExchangeController's JWKS overlap verification tests.
     */
    @Bean
    public KeyPair jwtSigningKeyPair() {
        log.info("Generating in-memory fallback JWT signing key pair");
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            return kpg.generateKeyPair();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate RSA signing keypair", e);
        }
    }
}
