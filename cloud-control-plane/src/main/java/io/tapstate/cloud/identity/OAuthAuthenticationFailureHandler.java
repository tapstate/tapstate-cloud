package io.tapstate.cloud.identity;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

@Component
public class OAuthAuthenticationFailureHandler implements AuthenticationFailureHandler {

    @Value("${tapstate.cloud.auth.console-url:http://localhost:5173}")
    private String consoleUrl;

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException, ServletException {
        var session = request.getSession(false);
        OAuthSessionSupport.reject(session);
        String error = exception instanceof OAuth2AuthenticationException oauth
            ? oauth.getError().getErrorCode() : null;
        String outcome = "access_denied".equals(error) ? "cancelled" : "provider-error";
        response.sendRedirect(consoleUrl.replaceAll("/$", "") + "/auth?oauth=" + outcome);
    }
}
