/*
 * Tapstate Cloud — Global Control Plane SDK
 * Project-owned unit tests — NOT auto-generated.
 */

package io.tapstate.cloud.sdk.client;

import io.tapstate.cloud.sdk.ApiException;
import io.tapstate.cloud.sdk.api.ExchangeApi;
import io.tapstate.cloud.sdk.model.ApiResponseExchangeResponse;
import io.tapstate.cloud.sdk.model.ExchangeRequest;
import io.tapstate.cloud.sdk.model.ExchangeResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CloudControlPlaneSdkTest {

    private static final String BASE_URL = "https://dev.cloud.tapstate.com";
    private static final String ISSUER = "https://dev.cloud.tapstate.com";
    private static final String CLUSTER_IDENTITY_SECRET = "test-secret-abc123";
    private static final String CLUSTER_ID = "c1a2b3c4-d5e6-7f89-0abc-def123456789";
    private static final String EXCHANGE_CODE = "abc123xyz789".repeat(4);
    private static final String JWT = "eyJhbGciOiJSUzI1NiJ9.signature.payload";
    private static final Instant JWT_EXPIRES = Instant.parse("2026-09-28T16:00:00Z");

    @Mock
    ExchangeApi mockExchangeApi;

    private CloudControlPlaneSdk sdk;

    @BeforeEach
    void setUp() {
        JwksKeyCache jwksCache = new JwksKeyCache(BASE_URL + "/v1/api/jwks.json",
            java.time.Duration.ofMinutes(10));
        sdk = CloudControlPlaneSdk.builder()
            .baseUrl(BASE_URL)
            .issuer(ISSUER)
            .sdkStaticToken(CLUSTER_IDENTITY_SECRET)
            .buildForTest(CLUSTER_ID, mockExchangeApi, jwksCache);
    }

    // ========== Function 1 tests: exchangeForJwt ==========

    @Test
    @DisplayName("exchangeForJwt — success: request carries SDK's stored clusterId, returns typed result")
    void exchangeForJwt_success_usesStoredClusterId() throws Exception {
        ExchangeResponse response = new ExchangeResponse()
            .jwt(JWT)
            .expiresAt(JWT_EXPIRES.toString())
            .jti("jti-001")
            .userEmail("alice@tapstate.io")
            .orgId("org-abc")
            .clusterId(CLUSTER_ID);

        // Generator automatically unwraps ApiResponse envelope — mock returns
        // ApiResponseExchangeResponse (the actual return type), SDK calls .getData().
        when(mockExchangeApi.exchange(any(ExchangeRequest.class), anyString(), any()))
            .thenReturn(new ApiResponseExchangeResponse().code("ok").data(response));

        JwtExchangeResult result = sdk.exchangeForJwt(EXCHANGE_CODE);

        assertEquals(JWT, result.jwt());
        assertEquals(JWT_EXPIRES, result.expiresAt());
        assertEquals("jti-001", result.jti());
        assertEquals("alice@tapstate.io", result.userEmail());
        assertEquals("org-abc", result.orgId());
        assertEquals(CLUSTER_ID, result.clusterId());
        assertNull(result.ec2PublicIp());
        assertNull(result.egressAddressStatus());

        // Verify the ExchangeRequest built by SDK contains the stored clusterId
        ArgumentCaptor<ExchangeRequest> reqCaptor = ArgumentCaptor.forClass(ExchangeRequest.class);
        verify(mockExchangeApi).exchange(reqCaptor.capture(), anyString(), any());
        assertEquals(CLUSTER_ID, reqCaptor.getValue().getClusterId());
        assertEquals(EXCHANGE_CODE, reqCaptor.getValue().getExchangeCode());

        // Cluster identity secret sent as second arg (X-Cluster-Identity-Secret header)
        ArgumentCaptor<String> secretCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockExchangeApi).exchange(any(ExchangeRequest.class), secretCaptor.capture(), any());
        assertEquals(CLUSTER_IDENTITY_SECRET, secretCaptor.getValue());
    }

    @Test
    void exchangePreservesOptionalEgressFieldsFromTheBackChannel() throws Exception {
        ExchangeResponse response = new io.tapstate.cloud.sdk.ApiClient().getObjectMapper().readValue("""
            {"jwt":"signed-jwt","expiresAt":"2026-10-15T12:00:00Z","jti":"egress-jti",
             "orgId":"org-one","clusterId":"cluster-one","organizationName":"Org",
             "clusterName":"Analytics","region":"us-east-1","ec2PublicIp":"203.0.113.42",
             "egressAddressStatus":"STABLE"}
            """, ExchangeResponse.class);
        when(mockExchangeApi.exchange(any(ExchangeRequest.class), anyString(), any()))
            .thenReturn(new ApiResponseExchangeResponse().code("ok").data(response));
        JwtExchangeResult result = sdk.exchangeForJwt(EXCHANGE_CODE);
        assertEquals("203.0.113.42", result.ec2PublicIp());
        assertEquals("STABLE", result.egressAddressStatus());
        ExchangeResponse legacy = new io.tapstate.cloud.sdk.ApiClient().getObjectMapper().readValue(
            "{\"jwt\":\"legacy\"}", ExchangeResponse.class);
        assertNull(legacy.getEc2PublicIp());
        assertNull(legacy.getEgressAddressStatus());
    }

    @Test
    @DisplayName("exchangeForJwt — backend returns exchange.cluster-identity-mismatch → typed exception")
    void exchangeForJwt_clusterIdentityMismatch_typedException() throws Exception {
        String body = "{\"code\":\"exchange.cluster-identity-mismatch\",\"msg\":\"Cluster service identity secret is invalid\",\"opId\":\"op-002\"}";
        ApiException apiEx = new ApiException(401, "Unauthorized", Map.of(), body);
        when(mockExchangeApi.exchange(any(), anyString(), any())).thenThrow(apiEx);

        CloudControlPlaneException cpe = assertThrows(CloudControlPlaneException.class,
            () -> sdk.exchangeForJwt(EXCHANGE_CODE));

        assertEquals("exchange.cluster-identity-mismatch", cpe.getErrorCode());
        assertEquals(401, cpe.getHttpStatus());
        assertEquals("Cluster service identity secret is invalid", cpe.getServerMessage());
    }

    @Test
    @DisplayName("exchangeForJwt — backend returns exchange.code-consumed → typed exception")
    void exchangeForJwt_codeConsumed_typedException() throws Exception {
        String body = "{\"code\":\"exchange.code-consumed\",\"msg\":\"Exchange code already consumed\",\"opId\":\"op-003\"}";
        ApiException apiEx = new ApiException(401, "Unauthorized", Map.of(), body);
        when(mockExchangeApi.exchange(any(), anyString(), any())).thenThrow(apiEx);

        CloudControlPlaneException cpe = assertThrows(CloudControlPlaneException.class,
            () -> sdk.exchangeForJwt(EXCHANGE_CODE));

        assertEquals("exchange.code-consumed", cpe.getErrorCode());
        assertEquals(401, cpe.getHttpStatus());
    }

    @Test
    @DisplayName("exchangeForJwt — unparseable expiresAt → throws (security fail-closed)")
    void exchangeForJwt_unparseableExpiresAt() throws Exception {
        ExchangeResponse badResponse = new ExchangeResponse()
            .jwt(JWT)
            .expiresAt("not-a-valid-iso-date")
            .jti("jti-bad")
            .userEmail("a@b.c")
            .orgId("org-1")
            .clusterId(CLUSTER_ID);
        when(mockExchangeApi.exchange(any(), anyString(), any()))
            .thenReturn(new ApiResponseExchangeResponse().code("ok").data(badResponse));

        CloudControlPlaneException cpe = assertThrows(CloudControlPlaneException.class,
            () -> sdk.exchangeForJwt(EXCHANGE_CODE));

        assertEquals("sdk.invalid-expires-at", cpe.getErrorCode());
        assertEquals(200, cpe.getHttpStatus());
    }

    @Test
    @DisplayName("exchangeForJwt — null clusterId throws before making HTTP call")
    void exchangeForJwt_nullClusterId() {
        CloudControlPlaneSdk nullClusterSdk = CloudControlPlaneSdk.builder()
            .baseUrl(BASE_URL)
            .issuer(ISSUER)
            .sdkStaticToken("any-secret")
            .clusterId(null)   // explicit null
            .build();

        CloudControlPlaneException cpe = assertThrows(CloudControlPlaneException.class,
            () -> nullClusterSdk.exchangeForJwt(EXCHANGE_CODE));

        assertEquals("sdk.not-configured", cpe.getErrorCode());
    }

    // ========== Builder validation ==========

    @Test
    @DisplayName("builder — rejects blank baseUrl")
    void builder_rejectsBlankBaseUrl() {
        assertThrows(IllegalArgumentException.class, () -> CloudControlPlaneSdk.builder()
            .baseUrl("")
            .build());
    }

    @Test
    @DisplayName("getBaseUrl / getClusterId — return configured values")
    void getters_returnConfigured() {
        assertEquals(BASE_URL, sdk.getBaseUrl());
        assertEquals(CLUSTER_ID, sdk.getClusterId());
    }
}
