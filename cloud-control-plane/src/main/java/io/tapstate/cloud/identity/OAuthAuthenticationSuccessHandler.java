package io.tapstate.cloud.identity;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class OAuthAuthenticationSuccessHandler implements AuthenticationSuccessHandler {

    private static final String FLOW = "TAPSTATE_OAUTH_FLOW";
    private static final String INVITATION_TOKEN = "TAPSTATE_OAUTH_INVITATION_TOKEN";
    private static final String REAUTH_AT = "TAPSTATE_IDENTITY_REAUTH_AT";

    private final IdentityAuthenticationService identityAuthenticationService;
    private final OAuth2AuthorizedClientService authorizedClients;
    private final RestClient.Builder restClientBuilder;

    @Value("${tapstate.cloud.auth.console-url:http://localhost:5173}")
    private String consoleUrl;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException, ServletException {
        if (!(authentication instanceof OAuth2AuthenticationToken oauth)) {
            OAuthSessionSupport.reject(request.getSession(false));
            response.sendRedirect(authRedirect("provider-error"));
            return;
        }
        HttpSession session = request.getSession(false);
        if (session == null) {
            SecurityContextHolder.clearContext();
            response.sendRedirect(authRedirect("provider-error"));
            return;
        }
        String flow = (String) session.getAttribute(FLOW);
        if (flow == null) flow = "login";

        String provider = oauth.getAuthorizedClientRegistrationId();
        String subject;
        String email;
        String displayName;
        boolean verified;
        try {
            if (oauth.getPrincipal() instanceof OidcUser oidcUser) {
                subject = oidcUser.getSubject();
                email = oidcUser.getEmail();
                displayName = oidcUser.getFullName();
                verified = Boolean.TRUE.equals(oidcUser.getEmailVerified());
            } else {
                Map<String, Object> attributes = oauth.getPrincipal().getAttributes();
                subject = String.valueOf(attributes.get("id"));
                displayName = String.valueOf(attributes.getOrDefault("name", attributes.getOrDefault("login", "")));
                GitHubEmail githubEmail = verifiedGitHubEmail(oauth, provider);
                email = githubEmail.email();
                verified = githubEmail.verified();
            }
        } catch (RuntimeException exception) {
            OAuthSessionSupport.reject(session);
            response.sendRedirect(authRedirect("provider-error", flow));
            return;
        }
        if (subject == null || "null".equals(subject) || email == null || email.isBlank()) {
            OAuthSessionSupport.reject(session);
            response.sendRedirect(authRedirect("provider-email-unavailable", flow));
            return;
        }

        String invitation = (String) session.getAttribute(INVITATION_TOKEN);
        String organizationName = (String) session.getAttribute("TAPSTATE_OAUTH_ORGANIZATION_NAME");
        String currentEmail = (String) session.getAttribute(AuthController.SESSION_USER_EMAIL);
        Instant reauthenticatedAt = session.getAttribute(REAUTH_AT) instanceof Instant at ? at : null;
        User user;
        try {
            user = identityAuthenticationService.authenticate(provider, subject, email, verified, displayName,
                flow, currentEmail, reauthenticatedAt, invitation, organizationName);
        } catch (RuntimeException exception) {
            OAuthSessionSupport.reject(session);
            response.sendRedirect(authRedirect(errorKey(exception), flow));
            return;
        }

        request.changeSessionId();
        var appAuthentication = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
            user.getEmail(), null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(appAuthentication);
        SecurityContextHolder.setContext(context);
        session.setAttribute("SPRING_SECURITY_CONTEXT", context);
        session.setAttribute(AuthController.SESSION_USER_EMAIL, user.getEmail());
        session.setAttribute("TAPSTATE_AUTHENTICATED_AT", Instant.now());
        OAuthSessionSupport.clearFlow(session);
        response.sendRedirect(authRedirect("success"));
    }

    private GitHubEmail verifiedGitHubEmail(OAuth2AuthenticationToken authentication, String provider) {
        OAuth2AuthorizedClient client = authorizedClients.loadAuthorizedClient(provider, authentication.getName());
        if (client == null) return new GitHubEmail(null, false);
        List<Map<String, Object>> emails = restClientBuilder.build().get()
            .uri("https://api.github.com/user/emails")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + client.getAccessToken().getTokenValue())
            .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
            .header("X-GitHub-Api-Version", "2022-11-28")
            .retrieve().body(new ParameterizedTypeReference<>() {});
        if (emails == null) return new GitHubEmail(null, false);
        return emails.stream()
            .filter(item -> Boolean.TRUE.equals(item.get("primary")))
            .map(item -> new GitHubEmail(item.get("email") instanceof String value ? value : null,
                Boolean.TRUE.equals(item.get("verified"))))
            .filter(item -> item.email() != null && !item.email().isBlank())
            .findFirst().orElse(new GitHubEmail(null, false));
    }

    private String errorKey(RuntimeException exception) {
        if (exception instanceof io.tapstate.cloud.common.api.BusinessException business) {
            return business.getErrorCode().getCode();
        }
        return "provider-error";
    }

    private String authRedirect(String outcome) {
        return authRedirect(outcome, null);
    }

    private String authRedirect(String outcome, String flow) {
        String redirect = consoleUrl.replaceAll("/$", "") + "/auth?oauth="
            + URLEncoder.encode(outcome, StandardCharsets.UTF_8);
        return "signup".equals(flow) ? redirect + "&mode=register" : redirect;
    }

    private record GitHubEmail(String email, boolean verified) {}
}
