package io.tapstate.cloud.config;

import org.mongodb.spring.session.MongoIndexedSessionRepository;
import org.mongodb.spring.session.config.annotation.web.http.EnableMongoHttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.server.Cookie;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.config.SessionRepositoryCustomizer;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * MongoDB-backed HTTP sessions.
 * <p>
 * The control plane runs multiple replicas behind a Service without sticky
 * sessions, so Tomcat's per-JVM in-memory sessions make a login visible to
 * only the replica that served it: every other replica answers 401 for the
 * same session cookie. Persisting sessions in the control-plane MongoDB makes
 * every replica authenticate the same session.
 * <p>
 * The HTTP cookie contract (name, HttpOnly, Secure, SameSite) keeps reading
 * the existing {@code server.servlet.session.cookie.*} properties so profile
 * and environment configuration is unchanged. Idle expiry keeps reading
 * {@code tapstate.cloud.session-expiry-days}, the same knob the deployment
 * already sets; the servlet container timeout no longer governs the session.
 */
@Configuration(proxyBeanMethods = false)
@EnableMongoHttpSession(collectionName = SessionConfig.SESSIONS_COLLECTION)
public class SessionConfig {

    /** MongoDB collection holding serialized sessions; a TTL index is created on it. */
    public static final String SESSIONS_COLLECTION = "sessions";

    /**
     * Applies the configured idle expiry to the session repository.
     * The annotation attribute only accepts a compile-time constant, so the
     * externalized {@code tapstate.cloud.session-expiry-days} value is applied
     * through a customizer once the repository bean is created.
     */
    @Bean
    public SessionRepositoryCustomizer<MongoIndexedSessionRepository> sessionExpiryCustomizer(
            @Value("${tapstate.cloud.session-expiry-days:7}") int sessionExpiryDays) {
        Duration maxInactiveInterval = Duration.ofDays(sessionExpiryDays);
        return repository -> repository.setDefaultMaxInactiveInterval(maxInactiveInterval);
    }

    /**
     * Serializes the session cookie from the existing {@code server.servlet.session.cookie.*}
     * properties. Spring Session does not read those servlet properties itself, and its
     * own default cookie name is {@code SESSION}; without this bean the browser would
     * receive a differently named cookie.
     */
    @Bean
    public CookieSerializer cookieSerializer(ServerProperties serverProperties) {
        Cookie properties = serverProperties.getServlet().getSession().getCookie();

        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        serializer.setCookieName(properties.getName());
        if (StringUtils.hasText(properties.getPath())) {
            serializer.setCookiePath(properties.getPath());
        }
        serializer.setUseHttpOnlyCookie(Boolean.TRUE.equals(properties.getHttpOnly()));
        serializer.setUseSecureCookie(Boolean.TRUE.equals(properties.getSecure()));
        Cookie.SameSite sameSite = properties.getSameSite();
        if (sameSite != null && sameSite != Cookie.SameSite.OMITTED) {
            serializer.setSameSite(sameSite.attributeValue());
        }
        return serializer;
    }
}
