package io.tapstate.cloud.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
/**
 * Service for JWKS key rotation.
 * Generates new RSA key pairs daily, stores them in MongoDB, and cleans up expired keys.
 */
@Service
@Slf4j
public class JwksRotationService {

    private final JwksKeyRepository repository;

    @Value("${tapstate.cloud.jwks.rotation-enabled:true}")
    private boolean rotationEnabled;

    @Value("${tapstate.cloud.jwks.encryption-enabled:false}")
    private boolean encryptionEnabled;

    @Value("${tapstate.cloud.jwks.encryption-key:}")
    private String encryptionKey;

    private static final String AES_ALGORITHM = "AES";
    private static final String ECB_MODE = "AES/ECB/PKCS5Padding";
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final long ROTATION_INTERVAL_HOURS = 24;
    private static final long OVERLAP_HOURS = 1;
    private static final long KEY_VALIDITY_HOURS = ROTATION_INTERVAL_HOURS + OVERLAP_HOURS * 2;

    public JwksRotationService(JwksKeyRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    public void init() {
        if (rotationEnabled) {
            log.info("JWKS rotation service initialized. Encryption enabled: {}", encryptionEnabled);
            // Ensure there is at least one active key on startup
            ensureActiveKey();
        }
    }

    /**
     * Run key rotation job daily at midnight.
     */
    @Scheduled(cron = "0 0 0 * * *")
    @SchedulerLock(name = "jwksRotationLock", lockAtMostFor = "10m")
    public void rotateKeys() {
        if (!rotationEnabled) {
            log.debug("JWKS rotation is disabled, skipping rotation job");
            return;
        }

        log.info("Starting JWKS key rotation job");

        try {
            // Generate new key pair
            KeyPair newKeyPair = generateRsaKeyPair();
            String newKid = generateKeyId();
            String publicKeyPem = encodePublicKeyToPem(newKeyPair.getPublic());
            String privateKeyPem = encodePrivateKeyToPem(newKeyPair.getPrivate());

            // Encrypt private key if encryption is enabled
            if (encryptionEnabled) {
                privateKeyPem = encryptPrivateKey(privateKeyPem);
            }

            // Mark current active key as PREVIOUS
            Optional<JwksKey> currentActive = repository.findFirstByStatusOrderByCreatedAtDesc(JwksKey.KeyStatus.ACTIVE);
            if (currentActive.isPresent()) {
                JwksKey previousKey = currentActive.get();
                previousKey.setStatus(JwksKey.KeyStatus.PREVIOUS);
                repository.save(previousKey);
                log.info("Marked key {} as PREVIOUS", previousKey.getKid());
            }

            // Create new active key
            Instant now = Instant.now();
            Instant expiresAt = now.plusSeconds(KEY_VALIDITY_HOURS * 3600);

            JwksKey newKey = JwksKey.builder()
                    .kid(newKid)
                    .publicKeyPem(publicKeyPem)
                    .privateKeyPem(privateKeyPem)
                    .status(JwksKey.KeyStatus.ACTIVE)
                    .createdAt(now)
                    .expiresAt(expiresAt)
                    .build();

            repository.save(newKey);
            log.info("Created new active key with kid: {}", newKid);

            // Cleanup expired keys
            cleanupExpiredKeys();

            log.info("JWKS key rotation completed successfully");
        } catch (Exception e) {
            log.error("Failed to rotate JWKS keys", e);
            throw new RuntimeException("Key rotation failed", e);
        }
    }

    /**
     * Generate new RSA key pair with 2048-bit key size.
     */
    private KeyPair generateRsaKeyPair() throws NoSuchAlgorithmException {
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(2048);
        return keyPairGenerator.generateKeyPair();
    }

    /**
     * Generate key ID in format: key-YYYY-MM-DD
     */
    private String generateKeyId() {
        String today = LocalDate.now().format(DATE_FORMATTER);
        return "key-" + today;
    }

    /**
     * Encode public key to PEM format.
     */
    private String encodePublicKeyToPem(java.security.PublicKey publicKey) {
        byte[] encoded = publicKey.getEncoded();
        String base64 = java.util.Base64.getEncoder().encodeToString(encoded);
        StringBuilder pem = new StringBuilder();
        pem.append("-----BEGIN PUBLIC KEY-----\n");
        for (int i = 0; i < base64.length(); i += 64) {
            pem.append(base64.substring(i, Math.min(i + 64, base64.length()))).append("\n");
        }
        pem.append("-----END PUBLIC KEY-----\n");
        return pem.toString();
    }

    /**
     * Encode private key to PEM format.
     */
    private String encodePrivateKeyToPem(java.security.PrivateKey privateKey) {
        byte[] encoded = privateKey.getEncoded();
        String base64 = java.util.Base64.getEncoder().encodeToString(encoded);
        StringBuilder pem = new StringBuilder();
        pem.append("-----BEGIN PRIVATE KEY-----\n");
        for (int i = 0; i < base64.length(); i += 64) {
            pem.append(base64.substring(i, Math.min(i + 64, base64.length()))).append("\n");
        }
        pem.append("-----END PRIVATE KEY-----\n");
        return pem.toString();
    }

    /**
     * Encrypt private key using AES-256.
     */
    private String encryptPrivateKey(String privateKeyPem) {
        if (encryptionKey == null || encryptionKey.isEmpty()) {
            log.warn("Encryption enabled but encryption key is not set. Returning unencrypted key.");
            return privateKeyPem;
        }

        try {
            // Use first 32 bytes for AES-256
            byte[] keyBytes = encryptionKey.getBytes("UTF-8");
            byte[] paddedKey = new byte[32];
            System.arraycopy(keyBytes, 0, paddedKey, 0, Math.min(keyBytes.length, 32));

            SecretKeySpec keySpec = new SecretKeySpec(paddedKey, AES_ALGORITHM);
            Cipher cipher = Cipher.getInstance(ECB_MODE);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec);

            byte[] encrypted = cipher.doFinal(privateKeyPem.getBytes("UTF-8"));
            return java.util.Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception e) {
            log.error("Failed to encrypt private key", e);
            return privateKeyPem; // Return unencrypted on error
        }
    }

    /**
     * Ensure there is at least one active key.
     */
    private void ensureActiveKey() {
        Optional<JwksKey> activeKey = repository.findFirstByStatusOrderByCreatedAtDesc(JwksKey.KeyStatus.ACTIVE);
        if (activeKey.isEmpty()) {
            log.info("No active key found, generating initial key pair");
            try {
                KeyPair keyPair = generateRsaKeyPair();
                String publicKeyPem = encodePublicKeyToPem(keyPair.getPublic());
                String privateKeyPem = encodePrivateKeyToPem(keyPair.getPrivate());

                if (encryptionEnabled) {
                    privateKeyPem = encryptPrivateKey(privateKeyPem);
                }

                // Use current date as initial kid
                String initialKid = generateKeyId();
                Instant now = Instant.now();
                Instant expiresAt = now.plusSeconds(KEY_VALIDITY_HOURS * 3600);

                JwksKey initialKey = JwksKey.builder()
                        .kid(initialKid)
                        .publicKeyPem(publicKeyPem)
                        .privateKeyPem(privateKeyPem)
                        .status(JwksKey.KeyStatus.ACTIVE)
                        .createdAt(now)
                        .expiresAt(expiresAt)
                        .build();

                repository.save(initialKey);
                log.info("Created initial active key with kid: {}", initialKid);
            } catch (Exception e) {
                log.error("Failed to generate initial key pair", e);
                throw new RuntimeException("Failed to initialize JWKS keys", e);
            }
        }
    }

    /**
     * Cleanup expired keys from the repository.
     */
    private void cleanupExpiredKeys() {
        List<JwksKey> expiredKeys = repository.findAllByStatusIn(
                Arrays.asList(JwksKey.KeyStatus.PREVIOUS, JwksKey.KeyStatus.EXPIRED));

        Instant now = Instant.now();
        int deletedCount = 0;

        for (JwksKey key : expiredKeys) {
            if (key.getExpiresAt().isBefore(now)) {
                repository.delete(key);
                deletedCount++;
                log.info("Deleted expired key with kid: {}", key.getKid());
            }
        }

        if (deletedCount > 0) {
            log.info("Cleaned up {} expired keys", deletedCount);
        }
    }

    /**
     * Get the current active key from MongoDB.
     */
    public Optional<JwksKey> getActiveKey() {
        return repository.findFirstByStatusOrderByCreatedAtDesc(JwksKey.KeyStatus.ACTIVE);
    }

    /**
     * Build a fresh signing KeyPair from MongoDB's active key (real rotation-aware).
     * <p>
     * ExchangeService and CallbackSignService MUST call this on every signature,
     * never caching a KeyPair at construction time. The returned KeyPair carries
     * the CURRENT active private key from MongoDB — if a rotation happened since
     * the caller was built, this picks up the new key.
     *
     * @return freshly built KeyPair (public + private) from MongoDB active key
     * @throws IllegalStateException if rotation is disabled or no active key exists
     */
    public KeyPair buildActiveSigningKeyPair() {
        Optional<JwksKey> active = getActiveKey();
        if (active.isEmpty()) {
            throw new IllegalStateException(
                "No active JWKS key in MongoDB — cannot sign JWTs or callbacks");
        }
        JwksKey key = active.get();
        try {
            java.security.KeyFactory kf = java.security.KeyFactory.getInstance("RSA");
            java.security.PublicKey pub = parsePublicKeyFromPem(key.getPublicKeyPem());

            String pem = key.getPrivateKeyPem();
            if (encryptionEnabled && pem != null) {
                pem = decryptPrivateKey(pem);
            }
            java.security.PrivateKey priv = parsePrivateKeyFromPem(pem);
            return new KeyPair(pub, priv);
        } catch (Exception e) {
            throw new IllegalStateException(
                "Failed to rebuild signing KeyPair from JWKS key kid=" + key.getKid(), e);
        }
    }

    /**
     * PublicKey-only rebuild from PEM — used by JWKS endpoint + ResolveController fallback.
     */
    static java.security.PublicKey parsePublicKeyFromPem(String pem) throws Exception {
        String stripped = stripPem(pem);
        byte[] bytes = Base64.getDecoder().decode(stripped);
        java.security.KeyFactory kf = java.security.KeyFactory.getInstance("RSA");
        return kf.generatePublic(new X509EncodedKeySpec(bytes));
    }

    static java.security.PrivateKey parsePrivateKeyFromPem(String pem) throws Exception {
        String stripped = stripPem(pem);
        byte[] bytes = Base64.getDecoder().decode(stripped);
        java.security.KeyFactory kf = java.security.KeyFactory.getInstance("RSA");
        return kf.generatePrivate(new PKCS8EncodedKeySpec(bytes));
    }

    private static String stripPem(String pem) {
        StringBuilder sb = new StringBuilder();
        for (String line : pem.split("\n")) {
            if (!line.startsWith("-----") && !line.trim().isEmpty()) sb.append(line.trim());
        }
        return sb.toString();
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
            return new String(cipher.doFinal(Base64.getDecoder().decode(encryptedPrivateKeyPem)), "UTF-8");
        } catch (Exception e) {
            log.error("Failed to decrypt private key", e);
            return encryptedPrivateKeyPem;
        }
    }

    /**
     * Get all non-expired keys (active + previous) for JWKS endpoint.
     */
    public List<JwksKey> getNonExpiredKeys() {
        List<JwksKey> keys = repository.findAllByStatusIn(
                Arrays.asList(JwksKey.KeyStatus.ACTIVE, JwksKey.KeyStatus.PREVIOUS));

        // Filter out any keys that have expired based on expiresAt field
        Instant now = Instant.now();
        keys.removeIf(key -> key.getExpiresAt().isBefore(now));

        return keys;
    }
}
