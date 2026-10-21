package io.tapstate.cloud.tawk;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TawkWebhookSignatureTest {

    private static final byte[] BODY = "The quick brown fox jumps over the lazy dog".getBytes();
    private static final String SIGNATURE = "de7c9b85b8b78aa6bc8a7a36f70a90701c9db4d9";

    @Test
    void acceptsTheDocumentedHmacSha1HexDigest() {
        assertTrue(TawkWebhookSignature.matches(BODY, SIGNATURE, "key"));
    }

    @Test
    void rejectsAlteredBodyMalformedSignatureAndMissingSecret() {
        assertFalse(TawkWebhookSignature.matches(
            "The quick brown fox jumps over the lazy dog.".getBytes(), SIGNATURE, "key"));
        assertFalse(TawkWebhookSignature.matches(BODY, "not-a-signature", "key"));
        assertFalse(TawkWebhookSignature.matches(BODY, SIGNATURE, " "));
    }
}
