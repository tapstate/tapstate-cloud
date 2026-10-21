package io.tapstate.cloud.tawk;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class TawkWebhookSignature {

    private static final String ALGORITHM = "HmacSHA1";

    private TawkWebhookSignature() {}

    static boolean matches(byte[] rawBody, String signature, String secret) {
        if (rawBody == null || signature == null || secret == null || secret.isBlank()) {
            return false;
        }
        try {
            byte[] supplied = HexFormat.of().parseHex(signature.trim());
            if (supplied.length != 20) {
                return false;
            }
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            byte[] expected = mac.doFinal(rawBody);
            return MessageDigest.isEqual(expected, supplied);
        } catch (IllegalArgumentException | java.security.GeneralSecurityException e) {
            return false;
        }
    }
}
