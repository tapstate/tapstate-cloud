package io.tapstate.cloud.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class OAuthClientRegistrationConfigTest {

    @Test
    void registersGoogleAndGitHubWithProviderRequirements() {
        var repository = new OAuthClientRegistrationConfig().clientRegistrationRepository(
            "google-id", "google-secret", "github-id", "github-secret", "https://cloud.example");
        var google = repository.findByRegistrationId("google");
        var github = repository.findByRegistrationId("github");

        assertNotNull(google);
        assertEquals(Set.of("openid", "email", "profile"), google.getScopes());
        assertEquals("https://cloud.example/login/oauth2/code/{registrationId}", google.getRedirectUri());
        assertTrue(google.getClientSettings().isRequireProofKey());

        assertNotNull(github);
        assertEquals(Set.of("read:user", "user:email"), github.getScopes());
        assertEquals(ClientAuthenticationMethod.CLIENT_SECRET_POST, github.getClientAuthenticationMethod());
        assertTrue(github.getClientSettings().isRequireProofKey());
    }

    @Test
    void leavesProvidersUnavailableWhenCredentialsAreMissing() {
        var repository = new OAuthClientRegistrationConfig().clientRegistrationRepository(
            "", "", "", "", "https://cloud.example");

        assertNull(repository.findByRegistrationId("google"));
        assertNull(repository.findByRegistrationId("github"));
    }
}
