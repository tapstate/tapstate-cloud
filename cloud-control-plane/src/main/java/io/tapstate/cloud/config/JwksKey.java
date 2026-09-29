package io.tapstate.cloud.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * MongoDB document for JWKS key material.
 * Stores both active and previous keys to support key rotation with overlap period.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "jwks_keys")
public class JwksKey {

    @Id
    private String id;

    /** Key identifier (kid) for JWT verification */
    @Indexed(unique = true)
    private String kid;

    /** PEM-encoded RSA public key */
    private String publicKeyPem;

    /** PEM-encoded RSA private key (encrypted at rest) */
    private String privateKeyPem;

    /** Key status: ACTIVE, PREVIOUS, or EXPIRED */
    private KeyStatus status;

    /** Creation timestamp */
    private Instant createdAt;

    /** Expiration timestamp */
    private Instant expiresAt;

    public enum KeyStatus {
        ACTIVE,
        PREVIOUS,
        EXPIRED
    }
}
