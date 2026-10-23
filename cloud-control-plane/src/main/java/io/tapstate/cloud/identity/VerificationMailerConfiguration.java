package io.tapstate.cloud.identity;

import io.tapstate.cloud.notification.EmailTemplateRenderer;
import io.tapstate.cloud.notification.NotificationDeliveryService;
import io.tapstate.cloud.notification.NotificationRequest;
import io.tapstate.cloud.notification.RenderedEmail;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Routes verification mail through the shared notification outbox. The auth
 * flow only renders the message; transport, retry and delivery records belong
 * to {@link NotificationDeliveryService}.
 */
@Configuration(proxyBeanMethods = false)
public class VerificationMailerConfiguration {

    private static final Logger log = LoggerFactory.getLogger(VerificationMailerConfiguration.class);

    @Bean
    @Profile("!it")
    VerificationCodeMailer notificationVerificationCodeMailer(
            NotificationDeliveryService notifications,
            EmailTemplateRenderer emailTemplates) {
        return (email, code) -> {
            RenderedEmail rendered = emailTemplates.renderVerificationCode(code);
            boolean sent = notifications.sendNow(new NotificationRequest(
                null,
                new ObjectId(),
                "EMAIL",
                email,
                rendered.subject(),
                rendered.textBody(),
                rendered.htmlBody(),
                rendered.inlineLogo(),
                "auth-verification:" + sha256(email + ":" + code)));
            if (!sent) {
                throw new IllegalStateException("Email delivery failed");
            }
        };
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    @Bean
    @ConditionalOnMissingBean(VerificationCodeMailer.class)
    VerificationCodeMailer unavailableVerificationCodeMailer() {
        return (email, code) -> {
            log.warn("Verification email fallback mailer invoked; notification channel is unavailable");
            throw new IllegalStateException("Email delivery is not configured");
        };
    }
}
