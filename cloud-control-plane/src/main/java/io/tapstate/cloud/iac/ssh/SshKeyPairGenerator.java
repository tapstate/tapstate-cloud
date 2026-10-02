package io.tapstate.cloud.iac.ssh;

import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

/**
 * Generates per-Cluster SSH key pairs.
 * <p>
 * The public key is rendered in the single-line OpenSSH format consumed by
 * {@code authorized_keys} ({@code ssh-rsa AAAA…}); the private key is rendered as
 * a PKCS#8 PEM block suitable for JCE parsing and for SSHJ authentication.
 */
@Component
public class SshKeyPairGenerator {

    private static final int RSA_KEY_SIZE = 4096;
    private static final String KEY_TYPE = "ssh-rsa";
    private static final int LINE_LENGTH = 64;

    /**
     * Generate a fresh RSA-4096 key pair and its standard encodings.
     */
    public GeneratedSshKeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(RSA_KEY_SIZE);
            KeyPair keyPair = generator.generateKeyPair();

            String publicKey = encodeOpenSshPublicKey((RSAPublicKey) keyPair.getPublic());
            String privateKeyPem = encodePkcs8Pem(keyPair);
            return new GeneratedSshKeyPair(publicKey, privateKeyPem);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate SSH key pair", e);
        }
    }

    /** Encode an RSA public key into the OpenSSH wire format and base64 it. */
    private static String encodeOpenSshPublicKey(RSAPublicKey key) throws java.io.IOException {
        ByteArrayOutputStream blob = new ByteArrayOutputStream();
        writeString(blob, KEY_TYPE);
        writeBigInteger(blob, key.getPublicExponent());
        writeBigInteger(blob, key.getModulus());
        return KEY_TYPE + " " + Base64.getEncoder().encodeToString(blob.toByteArray());
    }

    /** Render a PKCS#8 private key as a PEM block. */
    private static String encodePkcs8Pem(KeyPair keyPair) {
        String base64 = Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
        StringBuilder pem = new StringBuilder("-----BEGIN PRIVATE KEY-----\n");
        for (int i = 0; i < base64.length(); i += LINE_LENGTH) {
            pem.append(base64, i, Math.min(i + LINE_LENGTH, base64.length())).append('\n');
        }
        pem.append("-----END PRIVATE KEY-----\n");
        return pem.toString();
    }

    /** Write an SSH wire "string" (4-byte big-endian length + bytes). */
    private static void writeString(ByteArrayOutputStream out, String value) throws java.io.IOException {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        writeUInt32(out, bytes.length);
        out.write(bytes);
    }

    /**
     * Write a positive big integer as an SSH wire "mpint". BigInteger's encoding
     * already carries the sign-padding zero when the high bit is set; keep that
     * zero (a stripped one would make the peer parse a negative number), and only
     * remove redundant extra leading zeros.
     */
    private static void writeBigInteger(ByteArrayOutputStream out, java.math.BigInteger value)
            throws java.io.IOException {
        byte[] bytes = value.toByteArray();
        int start = 0;
        while (start < bytes.length - 1
            && bytes[start] == 0
            && (bytes[start + 1] & 0x80) == 0) {
            start++;
        }
        int length = bytes.length - start;
        writeUInt32(out, length);
        out.write(bytes, start, length);
    }

    private static void writeUInt32(ByteArrayOutputStream out, int value) throws java.io.IOException {
        out.write((value >>> 24) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    /**
     * Generated key material.
     *
     * @param openSshPublicKey single-line {@code ssh-rsa …} public key
     * @param privateKeyPem    PKCS#8 PEM private key
     */
    public record GeneratedSshKeyPair(String openSshPublicKey, String privateKeyPem) {
    }
}
