/*
 * Tapstate Cloud — Global Control Plane SDK
 * Project-owned unit tests for verifyJwt() — offline JWKS JWT verification.
 */

package io.tapstate.cloud.sdk.client;

import io.jsonwebtoken.Jwts;
import io.tapstate.cloud.sdk.ApiClient;
import io.tapstate.cloud.sdk.api.ExchangeApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CloudControlPlaneSdkVerifyTest {

    private static final String ISSUER = "https://cloud.tapstate.io";
    private static final String BASE_URL = "https://cloud.tapstate.io";
    private static final String CLUSTER_ID = "c1a2b3c4-d5e6-7f89-0abc-def123456789";
    private static final String ORG_ID = "org-abc-123";
    private static final String USER_EMAIL = "alice@tapstate.io";
    private static final String EXPECTED_KID = "test-kid-2026";

    @Mock
    ExchangeApi mockExchangeApi;

    private KeyPair keyPair;
    private CloudControlPlaneSdk sdk;
    private JwksKeyCache jwksCache;

    @BeforeEach
    void setUp() throws Exception {
        when(mockExchangeApi.getApiClient()).thenReturn(new ApiClient());
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        keyPair = kpg.generateKeyPair();

        jwksCache = new JwksKeyCache(BASE_URL + "/v1/api/jwks.json",
            java.time.Duration.ofMinutes(10));
        jwksCache.testInjectKey(EXPECTED_KID, (RSAPublicKey) keyPair.getPublic());

        sdk = CloudControlPlaneSdk.builder()
            .baseUrl(BASE_URL)
            .issuer(ISSUER)
            .sdkStaticToken("any-secret")
            .buildForTest(CLUSTER_ID, mockExchangeApi, jwksCache);
    }

    /** Build a standard test JWT */
    private String buildJwt(Instant issuedAt, Instant expiration,
                            String issuer, String audience,
                            String orgId, String clusterId, String kid) {
        Instant now = issuedAt != null ? issuedAt : Instant.now();
        Instant exp = expiration != null ? expiration : now.plusSeconds(900);
        String iss = issuer != null ? issuer : ISSUER;
        String aud = audience != null ? audience : CLUSTER_ID + ".api.tapstate.io";
        String oid = orgId != null ? orgId : ORG_ID;
        String cid = clusterId != null ? clusterId : CLUSTER_ID;
        String k = kid != null ? kid : EXPECTED_KID;

        return Jwts.builder()
            .subject(USER_EMAIL)
            .issuer(iss)
            .audience().add(aud).and()
            .issuedAt(Date.from(now))
            .expiration(Date.from(exp))
            .id("jti-" + now.toEpochMilli())
            .claim("org_id", oid)
            .claim("cluster_id", cid)
            .claim("scope", List.of("workload:read", "workload:write"))
            .header().keyId(k).and()
            .signWith((RSAPrivateKey) keyPair.getPrivate())
            .compact();
    }

    // ========== Happy path ==========

    @Test
    @DisplayName("verifyJwt — valid JWT → success, all claims extracted")
    void verifyJwt_validJwt_allClaimsExtracted() throws Exception {
        String jwt = buildJwt(null, null, null, null, null, null, null);

        VerifiedJwt result = sdk.verifyJwt(jwt);

        assertEquals(USER_EMAIL, result.userEmail());
        assertEquals(ORG_ID, result.orgId());
        assertEquals(CLUSTER_ID, result.clusterId());
        assertEquals(ISSUER, result.issuer());
        assertNotNull(result.expiresAt());
        assertNotNull(result.jti());
        assertNotNull(result.scope());
    }

    // ========== Signature / key issues ==========

    @Test
    @DisplayName("verifyJwt — kid missing, forceRefresh succeeds with new key injected → success")
    void verifyJwt_kidMissingRefreshSuccess() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair newKeyPair = kpg.generateKeyPair();

        String newKid = "rotated-key-2026-09-29";
        String jwt = Jwts.builder()
            .subject(USER_EMAIL)
            .issuer(ISSUER)
            .audience().add(CLUSTER_ID + ".api.tapstate.io").and()
            .issuedAt(Date.from(Instant.now()))
            .expiration(Date.from(Instant.now().plusSeconds(900)))
            .claim("org_id", ORG_ID)
            .claim("cluster_id", CLUSTER_ID)
            .header().keyId(newKid).and()
            .signWith((RSAPrivateKey) newKeyPair.getPrivate())
            .compact();

        JwksKeyCache spyCache = spy(jwksCache);
        doAnswer(inv -> {
            jwksCache.testInjectKey(newKid, (RSAPublicKey) newKeyPair.getPublic());
            return null;
        }).when(spyCache).forceRefresh();

        CloudControlPlaneSdk spySdk = CloudControlPlaneSdk.builder()
            .baseUrl(BASE_URL)
            .issuer(ISSUER)
            .sdkStaticToken("any-secret")
            .buildForTest(CLUSTER_ID, mockExchangeApi, spyCache);

        VerifiedJwt result = spySdk.verifyJwt(jwt);

        assertEquals(USER_EMAIL, result.userEmail());
        verify(spyCache).forceRefresh();
    }

    @Test
    @DisplayName("verifyJwt — kid not found even after refresh → JwkNotFound")
    void verifyJwt_kidTrulyMissing() throws Exception {
        String bogusKid = "never-existed-key";
        String jwt = buildJwt(null, null, null, null, null, null, bogusKid);

        JwksKeyCache spyCache = spy(jwksCache);
        doNothing().when(spyCache).forceRefresh();

        CloudControlPlaneSdk spySdk = CloudControlPlaneSdk.builder()
            .baseUrl(BASE_URL)
            .issuer(ISSUER)
            .sdkStaticToken("any-secret")
            .buildForTest(CLUSTER_ID, mockExchangeApi, spyCache);

        JwtVerificationException.JwkNotFound ex = assertThrows(
            JwtVerificationException.JwkNotFound.class,
            () -> spySdk.verifyJwt(jwt));

        assertTrue(ex.getMessage().contains(bogusKid));
    }

    @Test
    @DisplayName("verifyJwt — signature mismatch (JWT signed with wrong private key)")
    void verifyJwt_signatureMismatch() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair wrongKey = kpg.generateKeyPair();

        String jwt = Jwts.builder()
            .subject(USER_EMAIL)
            .issuer(ISSUER)
            .audience().add(CLUSTER_ID + ".api.tapstate.io").and()
            .issuedAt(Date.from(Instant.now()))
            .expiration(Date.from(Instant.now().plusSeconds(900)))
            .claim("org_id", ORG_ID)
            .claim("cluster_id", CLUSTER_ID)
            .header().keyId(EXPECTED_KID).and()
            .signWith((RSAPrivateKey) wrongKey.getPrivate())
            .compact();

        assertThrows(JwtVerificationException.SignatureMismatch.class,
            () -> sdk.verifyJwt(jwt));
    }

    // ========== Standard claim issues (SDK still validates these) ==========

    @Test
    @DisplayName("verifyJwt — expired JWT → Expired")
    void verifyJwt_expired() throws Exception {
        Instant oneHourAgo = Instant.now().minusSeconds(3600);
        String jwt = buildJwt(oneHourAgo, oneHourAgo.plusSeconds(60), null, null, null, null, null);

        assertThrows(JwtVerificationException.Expired.class,
            () -> sdk.verifyJwt(jwt));
    }

    @Test
    @DisplayName("verifyJwt — wrong issuer → BadIssuer")
    void verifyJwt_wrongIssuer() throws Exception {
        String jwt = buildJwt(null, null, "evil.attacker.io", null, null, null, null);

        assertThrows(JwtVerificationException.BadIssuer.class,
            () -> sdk.verifyJwt(jwt));
    }

    // ========== Parse / malformed ==========

    @Test
    @DisplayName("verifyJwt — malformed JWT → ParseError")
    void verifyJwt_malformed_noSegments() {
        assertThrows(JwtVerificationException.ParseError.class,
            () -> sdk.verifyJwt("not-a-jwt"));
    }

    @Test
    @DisplayName("verifyJwt — JWT header missing kid → MissingKeyId")
    void verifyJwt_noKidInHeader() throws Exception {
        String jwt = Jwts.builder()
            .subject(USER_EMAIL)
            .issuer(ISSUER)
            .audience().add(CLUSTER_ID + ".api.tapstate.io").and()
            .issuedAt(Date.from(Instant.now()))
            .expiration(Date.from(Instant.now().plusSeconds(900)))
            .claim("org_id", ORG_ID)
            .claim("cluster_id", CLUSTER_ID)
            // NO kid header
            .signWith((RSAPrivateKey) keyPair.getPrivate())
            .compact();

        assertThrows(JwtVerificationException.MissingKeyId.class,
            () -> sdk.verifyJwt(jwt));
    }
}
