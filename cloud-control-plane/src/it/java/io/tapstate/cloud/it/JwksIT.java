package io.tapstate.cloud.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.tapstate.cloud.sdk.ApiClient;
import io.tapstate.cloud.sdk.api.ResolveExchangeApi;
import io.tapstate.cloud.sdk.model.ResolveResponse;
import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for JWKS endpoint.
 */
public class JwksIT extends BaseIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void testJwksEndpointReturnsValidJson() throws Exception {
        // Get JWKS directly
        var response = get("/v1/api/jwks.json", null);

        assertEquals(200, response.statusCode(), "JWKS endpoint should return 200");

        JsonNode jwks = MAPPER.readTree(response.body());
        assertTrue(jwks.has("keys"), "JWKS should have 'keys' array");
        assertTrue(jwks.get("keys").isArray(), "'keys' should be an array");
    }

    @Test
    void testJwksContainsAtLeastOneKey() throws Exception {
        var response = get("/v1/api/jwks.json", null);

        assertEquals(200, response.statusCode());

        JsonNode jwks = MAPPER.readTree(response.body());
        ArrayNode keys = (ArrayNode) jwks.get("keys");

        assertTrue(keys.size() >= 1, "JWKS should contain at least one key");
    }

    @Test
    void testJwksKeyHasRequiredFields() throws Exception {
        var response = get("/v1/api/jwks.json", null);

        assertEquals(200, response.statusCode());

        JsonNode jwks = MAPPER.readTree(response.body());
        ArrayNode keys = (ArrayNode) jwks.get("keys");

        for (JsonNode key : keys) {
            assertTrue(key.has("kty"), "Key should have 'kty'");
            assertEquals("RSA", key.get("kty").asText(), "kty should be 'RSA'");

            assertTrue(key.has("kid"), "Key should have 'kid'");
            assertTrue(key.get("kid").asText().matches("key-\\d{4}-\\d{2}-\\d{2}"), "kid should match format 'key-YYYY-MM-DD'");

            assertTrue(key.has("use"), "Key should have 'use'");
            assertEquals("sig", key.get("use").asText(), "use should be 'sig'");

            assertTrue(key.has("alg"), "Key should have 'alg'");
            assertEquals("RS256", key.get("alg").asText(), "alg should be 'RS256'");

            assertTrue(key.has("n"), "Key should have 'n' (modulus)");
            assertTrue(key.has("e"), "Key should have 'e' (exponent)");
        }
    }

    @Test
    void testResolveEndpointRequiresSession() throws Exception {
        // Verify resolve endpoint returns 401 without session
        var response = get("/v1/api/clusters/c001/resolve?orgId=o001", null);
        assertEquals(401, response.statusCode(), "Resolve endpoint should require authentication");
    }

    @Test
    void testMultipleKeysCanExist() throws Exception {
        // This test verifies the JWKS endpoint can return multiple keys
        // (active + previous) during the rotation overlap period.

        var response = get("/v1/api/jwks.json", null);

        assertEquals(200, response.statusCode());

        JsonNode jwks = MAPPER.readTree(response.body());
        ArrayNode keys = (ArrayNode) jwks.get("keys");

        // Should have at least one key (active), and may have previous during overlap
        assertTrue(keys.size() >= 1, "JWKS should contain at least one key");
        assertTrue(keys.size() <= 2, "JWKS should not exceed 2 keys during rotation overlap");

        // Verify all keys have unique kids
        for (int i = 0; i < keys.size(); i++) {
            for (int j = i + 1; j < keys.size(); j++) {
                String kidI = keys.get(i).get("kid").asText();
                String kidJ = keys.get(j).get("kid").asText();
                assertFalse(kidI.equals(kidJ), "Each key should have a unique kid");
            }
        }
    }
}
