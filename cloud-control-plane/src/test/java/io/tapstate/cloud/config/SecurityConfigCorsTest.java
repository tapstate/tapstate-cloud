package io.tapstate.cloud.config;

import io.tapstate.cloud.identity.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.cors.CorsConfiguration;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class SecurityConfigCorsTest {

    private static final String ALLOWED_ORIGINS = String.join(",",
        "https://dev.cloud.tapstate.com",
        "https://*.dev.cloud.tapstate.com",
        "http://localhost:5173",
        "http://localhost:5174");

    @Test
    void allowsConfiguredCloudAndClusterOriginsAndRejectsUnconfiguredOrigins() {
        SecurityConfig securityConfig = new SecurityConfig(mock(UserRepository.class));
        Field allowedOriginsField = ReflectionUtils.findField(SecurityConfig.class, "corsAllowedOrigins");
        if (allowedOriginsField != null) {
            ReflectionTestUtils.setField(securityConfig, "corsAllowedOrigins", ALLOWED_ORIGINS);
        }

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/api/auth/logout");
        CorsConfiguration cors = securityConfig.corsConfigurationSource().getCorsConfiguration(request);

        assertNotNull(cors);
        assertTrue(cors.getAllowCredentials());
        assertEquals(
            "https://cluster-1.dev.cloud.tapstate.com",
            cors.checkOrigin("https://cluster-1.dev.cloud.tapstate.com"));
        assertEquals("http://localhost:5174", cors.checkOrigin("http://localhost:5174"));
        assertNull(cors.checkOrigin("https://attacker.example"));
    }
}
