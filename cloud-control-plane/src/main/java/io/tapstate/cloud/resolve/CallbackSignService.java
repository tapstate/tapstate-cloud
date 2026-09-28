package io.tapstate.cloud.resolve;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.KeyPair;
import java.security.Signature;
import java.util.Base64;

/**
 * Signs Cloud→Cluster revocation callbacks with the Cloud's RSA private key.
 * <p>
 * Signature format (Issue #5 item 4): sign = Base64UrlEncode(RS256_private_key_sign(method + ts + nonce + data)).
 * The Cluster verifies with its cached JWKS public key via SDK verifyRequest.
 * <p>
 * Uses the SAME {@link KeyPair} as JWT signing — Cluster already caches this public key
 * via JWKS endpoint, so no additional key distribution is needed.
 */
@Service
@Slf4j
public class CallbackSignService {

    private final KeyPair jwtSigningKeyPair;

    public CallbackSignService(KeyPair jwtSigningKeyPair) {
        this.jwtSigningKeyPair = jwtSigningKeyPair;
    }

    /**
     * Sign a Cloud→Cluster revocation callback request.
     * <p>
     * Canonical string (matches SDK verifyRequest exactly):
     * <pre>method + "|" + ts + "|" + nonce + "|" + data</pre>
     *
     * @param method HTTP method (e.g. "POST")
     * @param ts     Unix epoch milliseconds
     * @param nonce  Random string
     * @param data   Request body JSON (empty string for GET/no-body)
     * @return Base64Url-encoded RS256 signature
     */
    public String sign(String method, String ts, String nonce, String data) {
        String canonical = method + "|" + ts + "|" + nonce + "|" + data;
        try {
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initSign(jwtSigningKeyPair.getPrivate());
            sig.update(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] raw = sig.sign();
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        } catch (Exception e) {
            log.error("Failed to sign callback request", e);
            throw new RuntimeException("Callback signing failed", e);
        }
    }
}
