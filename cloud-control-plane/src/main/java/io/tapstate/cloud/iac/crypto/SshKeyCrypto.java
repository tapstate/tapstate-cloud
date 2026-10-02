package io.tapstate.cloud.iac.crypto;

import io.tapstate.cloud.iac.IacException;
import io.tapstate.cloud.common.api.AppErrorCode;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encrypts and decrypts per-Cluster SSH private keys at rest.
 * <p>
 * Algorithm is AES-256-GCM: the 12-byte random nonce is generated per encryption
 * and the 128-bit authentication tag is bundled with the ciphertext. The 32-byte
 * master key is supplied through configuration and never persisted by this class.
 * <p>
 * Wire format stored on the Cluster document:
 * {@code v1:<base64 nonce>:<base64 ciphertext+tag>}. The version prefix allows
 * future algorithm migration without ambiguity.
 */
@Component
public class SshKeyCrypto {

    private static final String VERSION = "v1";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_LENGTH_BYTES = 32;
    private static final int NONCE_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Encrypt a PEM private key.
     *
     * @param plaintext PEM private key
     * @param masterKeyBase64 base64-encoded 32-byte master key
     */
    public String encrypt(String plaintext, String masterKeyBase64) {
        SecretKeySpec key = decodeMasterKey(masterKeyBase64);
        try {
            byte[] nonce = new byte[NONCE_LENGTH_BYTES];
            secureRandom.nextBytes(nonce);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            Base64.Encoder encoder = Base64.getEncoder();
            return VERSION + ":" + encoder.encodeToString(nonce) + ":" + encoder.encodeToString(ciphertext);
        } catch (Exception e) {
            throw new IacException(AppErrorCode.SYSTEM_ERROR, e, "private-key encryption failed");
        }
    }

    /**
     * Decrypt a value previously produced by {@link #encrypt}.
     *
     * @param encoded          stored {@code v1:…} value
     * @param masterKeyBase64 base64-encoded 32-byte master key
     */
    public String decrypt(String encoded, String masterKeyBase64) {
        SecretKeySpec key = decodeMasterKey(masterKeyBase64);
        String[] parts = encoded.split(":");
        if (parts.length != 3 || !VERSION.equals(parts[0])) {
            throw new IacException(AppErrorCode.SYSTEM_ERROR, "Unsupported private-key encoding version");
        }
        try {
            byte[] nonce = Base64.getDecoder().decode(parts[1]);
            byte[] ciphertext = Base64.getDecoder().decode(parts[2]);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
            return new String(cipher.doFinal(ciphertext), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IacException(AppErrorCode.SYSTEM_ERROR, e, "private-key decryption failed");
        }
    }

    /** Decode and validate the configured master key. */
    private static SecretKeySpec decodeMasterKey(String masterKeyBase64) {
        if (masterKeyBase64 == null || masterKeyBase64.isBlank()) {
            throw new IacException(AppErrorCode.IAC_CRYPTO_KEY_MISSING,
                "SSH private-key master key is not configured");
        }
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(masterKeyBase64);
        } catch (IllegalArgumentException e) {
            throw new IacException(AppErrorCode.IAC_CRYPTO_KEY_MISSING,
                "SSH private-key master key must be valid base64");
        }
        if (keyBytes.length != KEY_LENGTH_BYTES) {
            throw new IacException(AppErrorCode.IAC_CRYPTO_KEY_MISSING,
                "SSH private-key master key must decode to 32 bytes");
        }
        return new SecretKeySpec(keyBytes, "AES");
    }
}
