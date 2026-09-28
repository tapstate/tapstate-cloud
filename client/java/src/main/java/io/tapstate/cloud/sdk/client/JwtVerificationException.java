/*
 * Tapstate Cloud — Global Control Plane SDK
 * Project-owned facade classes — NOT auto-generated.
 * Protected by .openapi-generator-ignore.
 */

package io.tapstate.cloud.sdk.client;

/**
 * Typed exception for resolve JWT offline verification failures (ADR-0122 D5 / ADR-0125 D6).
 * <p>
 * Distinct subtypes cover the verification pipeline so callers can branch on cause:
 * <ul>
 *   <li>{@link SignatureMismatch} — JWT signature does not match any cached JWK</li>
 *   <li>{@link Expired} — {@code exp} claim is in the past</li>
 *   <li>{@link BadIssuer} — {@code iss} claim does not match expected issuer</li>
 *   <li>{@link BadAudience} — {@code aud} claim does not match expected cluster</li>
 *   <li>{@link MissingKeyId} — JWT header has no {@code kid} (can't select a JWK)</li>
 *   <li>{@link JwkNotFound} — JWT header {@code kid} has no matching cached JWK</li>
 *   <li>{@link BadClaim} — custom claim ({@code org_id}, {@code cluster_id}) mismatch</li>
 *   <li>{@link ParseError} — JWT string malformed (base64/JSON)</li>
 * </ul>
 */
public class JwtVerificationException extends Exception {

    private static final long serialVersionUID = 1L;

    /** Classification tag for callers who want to branch on cause. */
    private final String kind;

    public JwtVerificationException(String kind, String message) {
        super(message);
        this.kind = kind;
    }

    public JwtVerificationException(String kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public String getKind() {
        return kind;
    }

    // ========== Specific subtypes ==========

    /** JWT header has no {@code kid} — can't select a JWK from the cache. */
    public static class MissingKeyId extends JwtVerificationException {
        private static final long serialVersionUID = 1L;
        public MissingKeyId() { super("missing-kid", "JWT header has no 'kid' claim — cannot select a verification key"); }
    }

    /** JWT header {@code kid} does not match any JWK currently in the cache. */
    public static class JwkNotFound extends JwtVerificationException {
        private static final long serialVersionUID = 1L;
        public JwkNotFound(String kid) { super("jwk-not-found", "No cached JWK with kid='" + kid + "' — may need JWKS refresh"); }
    }

    /** RSA signature verification failed — tampered token or wrong key. */
    public static class SignatureMismatch extends JwtVerificationException {
        private static final long serialVersionUID = 1L;
        public SignatureMismatch(String kid) { super("signature-mismatch", "JWT RS256 signature verification failed for kid='" + kid + "'"); }
    }

    /** {@code exp} claim is in the past. */
    public static class Expired extends JwtVerificationException {
        private static final long serialVersionUID = 1L;
        public Expired(long expEpoch, long nowEpoch) {
            super("expired", String.format("JWT expired at %d, current time %d", expEpoch, nowEpoch));
        }
    }

    /** {@code iss} claim does not match the configured issuer. */
    public static class BadIssuer extends JwtVerificationException {
        private static final long serialVersionUID = 1L;
        public BadIssuer(String actualIss, String expectedIss) {
            super("bad-issuer", String.format("JWT issuer '%s' does not match expected '%s'", actualIss, expectedIss));
        }
    }

    /**
     * {@code aud} claim does not match the expected Cluster audience.
     * <p>
     * NOTE: This subtype is NOT thrown by the SDK's {@code verifyJwt()} — the SDK
     * only verifies signature, exp, iss, and extracts all claims. The caller
     * (Cluster) can throw this when doing its own scope validation after calling
     * {@code verifyJwt()} and finding that the JWT's audience does not match the
     * local Cluster's expected audience pattern ({@code <clusterId>.api.tapstate.io}).
     */
    public static class BadAudience extends JwtVerificationException {
        private static final long serialVersionUID = 1L;
        public BadAudience(String actualAud, String expectedAud) {
            super("bad-audience", String.format("JWT audience '%s' does not match expected '%s'", actualAud, expectedAud));
        }
    }

    /**
     * A custom claim ({@code org_id} or {@code cluster_id}) does not match the expected value.
     * <p>
     * NOTE: This subtype is NOT thrown by the SDK's {@code verifyJwt()} — the SDK
     * only verifies signature, exp, iss, and extracts all claims. The caller
     * (Cluster) can throw this when doing its own scope validation after calling
     * {@code verifyJwt()} and finding that {@code org_id} / {@code cluster_id} in
     * the JWT does not match the local Cluster's records.
     */
    public static class BadClaim extends JwtVerificationException {
        private static final long serialVersionUID = 1L;
        public BadClaim(String claim, String actual, String expected) {
            super("bad-claim", String.format("JWT claim '%s' is '%s' but expected '%s'", claim, actual, expected));
        }
    }

    /** JWT string cannot be parsed (malformed base64 or JSON). */
    public static class ParseError extends JwtVerificationException {
        private static final long serialVersionUID = 1L;
        public ParseError(Throwable cause) { super("parse-error", "JWT parse failed: " + cause.getMessage(), cause); }
    }
}
