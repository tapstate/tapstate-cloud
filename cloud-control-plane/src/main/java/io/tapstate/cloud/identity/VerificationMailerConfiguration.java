package io.tapstate.cloud.identity;

import io.tapstate.cloud.notification.NotificationDeliveryService;
import io.tapstate.cloud.notification.NotificationRequest;
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
    VerificationCodeMailer notificationVerificationCodeMailer(NotificationDeliveryService notifications) {
        return (email, code) -> {
            boolean sent = notifications.sendNow(new NotificationRequest(
                null,
                new ObjectId(),
                "EMAIL",
                email,
                "Your Tapstate verification code",
                plainText(code),
                html(code),
                true,
                "auth-verification:" + sha256(email + ":" + code)));
            if (!sent) {
                throw new IllegalStateException("Email delivery failed");
            }
        };
    }

    private String plainText(String code) {
        return "Your Tapstate verification code is " + code + ".\n\n"
            + "It expires in 10 minutes. If you did not request this code, you can ignore this email.\n\n"
            + "Tapstate Cloud";
    }

    private String html(String code) {
        return """
            <!doctype html>
            <html lang="en">
              <body style="margin:0;padding:32px 16px;background:#f4f7f8;font-family:Arial,Helvetica,sans-serif;color:#101820;">
                <table role="presentation" width="100%" cellspacing="0" cellpadding="0" style="max-width:560px;margin:0 auto;background:#ffffff;border-radius:12px;">
                  <tr><td style="padding:28px 32px 24px;">
                    <img src="cid:tapstate-logo" width="140" alt="Tapstate" style="display:block;width:140px;height:auto;border:0;" />
                    <h1 style="margin:28px 0 8px;font-size:22px;line-height:1.35;font-weight:600;color:#101820;">Verify your email</h1>
                    <p style="margin:0;font-size:15px;line-height:1.6;color:#334155;">Enter this code to continue creating your Tapstate account.</p>
                    <div style="margin:24px 0;padding:16px 20px;border-radius:10px;background:#f1f5f9;text-align:center;font-family:Arial,Helvetica,sans-serif;font-size:32px;line-height:1.25;font-weight:700;letter-spacing:0.28em;color:#101820;">
                      {{verificationCode}}
                    </div>
                    <p style="margin:0;font-size:14px;line-height:1.6;color:#334155;">This code expires in 10 minutes. Do not share it with anyone.</p>
                    <p style="margin:20px 0 0;font-size:13px;line-height:1.6;color:#64748b;">If you did not request this code, you can ignore this email.</p>
                  </td></tr>
                </table>
              </body>
            </html>
            """.replace("{{verificationCode}}", code);
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
