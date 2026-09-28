package io.tapstate.cloud.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.KeyPair;
import java.security.KeyPairGenerator;

/**
 * Shared RSA keypair for resolve JWT signing + JWKS publication (ADR-0122 D5).
 * <p>
 * Week 2 scaffold: in-memory generated at startup.
 * Production: replace with PEM-encoded private key from k3s Secret (CLOUD_JWKS_SIGNING_KEY).
 * Both {@code ResolveController} (JWKS publisher) and {@code ExchangeService} (JWT signer)
 * MUST use the same KeyPair — otherwise signed JWTs fail JWKS verification.
 */
@Configuration
public class JwtKeysConfig {

    /** RSA KeyPair shared across all JWT signing + JWKS publication. */
    @Bean
    public KeyPair jwtSigningKeyPair() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            return kpg.generateKeyPair();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate RSA signing keypair", e);
        }
    }

    /** Key ID — stable identifier for the current key in JWKS. */
    public static final String KID = "key-2026-09-27";
}
