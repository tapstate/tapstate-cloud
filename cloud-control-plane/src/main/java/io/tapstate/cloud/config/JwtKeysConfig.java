package io.tapstate.cloud.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Optional;

/**
 * Shared RSA keypair for resolve JWT signing + JWKS publication (ADR-0122 D5).
 * <p>
 * Production: load active key from MongoDB on startup.
 * Falls back to in-memory generated key if MongoDB has no keys.
 */
@Configuration
@Slf4j
public class JwtKeysConfig {

    private final JwksRotationService jwksRotationService;

    @Value("${tapstate.cloud.jwks.rotation-enabled:true}")
    private boolean rotationEnabled;

    @Value("${tapstate.cloud.jwks.encryption-enabled:false}")
    private boolean encryptionEnabled;

    @Value("${tapstate.cloud.jwks.encryption-key:}")
    private String encryptionKey;

    public JwtKeysConfig(JwksRotationService jwksRotationService) {
        this.jwksRotationService = jwksRotationService;
    }

    /**
     * RSA KeyPair shared across all JWT signing + JWKS publication.
     * Loads from MongoDB if rotation is enabled, otherwise generates in-memory.
     */
    @Bean
    public KeyPair jwtSigningKeyPair() {
        if (rotationEnabled) {
            // Try to load active key from MongoDB
            Optional<JwksKey> activeKey = jwksRotationService.getActiveKey();
            if (activeKey.isPresent()) {
                try {
                    JwksKey key = activeKey.get();
                    log.info("Loading active JWT signing key from MongoDB with kid: {}", key.getKid());

                    PublicKey publicKey = parsePublicKeyFromPem(key.getPublicKeyPem());

                    String privateKeyPem = key.getPrivateKeyPem();
                    // Decrypt private key if encryption is enabled
                    if (encryptionEnabled && privateKeyPem != null) {
                        privateKeyPem = decryptPrivateKey(privateKeyPem);
                    }

                    PrivateKey privateKey = parsePrivateKeyFromPem(privateKeyPem);

                    return new KeyPair(publicKey, privateKey);
                } catch (Exception e) {
                    log.error("Failed to load JWT signing key from MongoDB, falling back to in-memory generation", e);
                }
            } else {
                log.info("No active key found in MongoDB, will fall back to in-memory generation");
            }
        }

        // Fallback: generate in-memory key pair
        log.info("Generating in-memory JWT signing key pair");
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            return kpg.generateKeyPair();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate RSA signing keypair", e);
        }
    }

    private PublicKey parsePublicKeyFromPem(String publicKeyPem) throws Exception {
        String[] lines = publicKeyPem.split("\n");
        StringBuilder base64Builder = new StringBuilder();
        for (String line : lines) {
            if (!line.startsWith("-----") && !line.trim().isEmpty()) {
                base64Builder.append(line.trim());
            }
        }
        byte[] encoded = Base64.getDecoder().decode(base64Builder.toString());
        java.security.KeyFactory keyFactory = java.security.KeyFactory.getInstance("RSA");
        X509EncodedKeySpec keySpec = new X509EncodedKeySpec(encoded);
        return keyFactory.generatePublic(keySpec);
    }

    private PrivateKey parsePrivateKeyFromPem(String privateKeyPem) throws Exception {
        String[] lines = privateKeyPem.split("\n");
        StringBuilder base64Builder = new StringBuilder();
        for (String line : lines) {
            if (!line.startsWith("-----") && !line.trim().isEmpty()) {
                base64Builder.append(line.trim());
            }
        }
        byte[] encoded = Base64.getDecoder().decode(base64Builder.toString());
        java.security.KeyFactory keyFactory = java.security.KeyFactory.getInstance("RSA");
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(encoded);
        return keyFactory.generatePrivate(keySpec);
    }

    private String decryptPrivateKey(String encryptedPrivateKeyPem) {
        if (encryptionKey == null || encryptionKey.isEmpty()) {
            log.warn("Decryption requested but encryption key is not set. Returning encrypted key.");
            return encryptedPrivateKeyPem;
        }

        try {
            byte[] keyBytes = encryptionKey.getBytes("UTF-8");
            byte[] paddedKey = new byte[32];
            System.arraycopy(keyBytes, 0, paddedKey, 0, Math.min(keyBytes.length, 32));

            javax.crypto.spec.SecretKeySpec keySpec = new javax.crypto.spec.SecretKeySpec(paddedKey, "AES");
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, keySpec);

            byte[] decrypted = cipher.doFinal(Base64.getDecoder().decode(encryptedPrivateKeyPem));
            return new String(decrypted, "UTF-8");
        } catch (Exception e) {
            log.error("Failed to decrypt private key", e);
            return encryptedPrivateKeyPem;
        }
    }

    /** Key ID — stable identifier for the current key in JWKS. */
    public static final String KID = "key-2026-09-27";
}
