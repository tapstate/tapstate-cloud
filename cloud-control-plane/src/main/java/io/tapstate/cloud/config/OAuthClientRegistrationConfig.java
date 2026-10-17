package io.tapstate.cloud.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.client.registration.ClientRegistration.ClientSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Configuration(proxyBeanMethods = false)
public class OAuthClientRegistrationConfig {

    @Bean
    ClientRegistrationRepository clientRegistrationRepository(
            @Value("${tapstate.cloud.auth.google-client-id:}") String googleClientId,
            @Value("${tapstate.cloud.auth.google-client-secret:}") String googleClientSecret,
            @Value("${tapstate.cloud.auth.github-client-id:}") String githubClientId,
            @Value("${tapstate.cloud.auth.github-client-secret:}") String githubClientSecret,
            @Value("${tapstate.cloud.auth.oauth-callback-base-url:http://localhost:8080}") String callbackBaseUrl) {
        List<ClientRegistration> registrations = new ArrayList<>();
        if (configured(googleClientId, googleClientSecret)) {
            registrations.add(ClientRegistration.withRegistrationId("google")
                .clientId(googleClientId)
                .clientSecret(googleClientSecret)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(callbackBaseUrl + "/login/oauth2/code/{registrationId}")
                .scope(OidcScopes.OPENID, OidcScopes.EMAIL, OidcScopes.PROFILE)
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .tokenUri("https://oauth2.googleapis.com/token")
                .userInfoUri("https://openidconnect.googleapis.com/v1/userinfo")
                .jwkSetUri("https://www.googleapis.com/oauth2/v3/certs")
                .userNameAttributeName("sub")
                .clientName("Google")
                .providerConfigurationMetadata(Map.of("issuer", "https://accounts.google.com"))
                .clientSettings(ClientSettings.builder().requireProofKey(true).build())
                .build());
        }
        if (configured(githubClientId, githubClientSecret)) {
            registrations.add(ClientRegistration.withRegistrationId("github")
                .clientId(githubClientId)
                .clientSecret(githubClientSecret)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(callbackBaseUrl + "/login/oauth2/code/{registrationId}")
                .scope("read:user", "user:email")
                .authorizationUri("https://github.com/login/oauth/authorize")
                .tokenUri("https://github.com/login/oauth/access_token")
                .userInfoUri("https://api.github.com/user")
                .userNameAttributeName("id")
                .clientName("GitHub")
                .clientSettings(ClientSettings.builder().requireProofKey(true).build())
                .build());
        }
        Map<String, ClientRegistration> byId = registrations.stream()
            .collect(java.util.stream.Collectors.toUnmodifiableMap(ClientRegistration::getRegistrationId, item -> item));
        return registrationId -> byId.get(registrationId);
    }

    @Bean
    OAuth2AuthorizedClientService authorizedClientService(ClientRegistrationRepository registrations) {
        return new InMemoryOAuth2AuthorizedClientService(registrations);
    }

    private boolean configured(String clientId, String clientSecret) {
        return clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank();
    }
}
