package io.tapstate.cloud.identity;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.mail.javamail.JavaMailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.mail.MessagingException;
import java.nio.charset.StandardCharsets;

@Configuration(proxyBeanMethods = false)
public class VerificationMailerConfiguration {

    private static final Logger log = LoggerFactory.getLogger(VerificationMailerConfiguration.class);

    @Bean
    @Profile("!it")
    @ConditionalOnProperty(prefix = "spring.mail", name = "host")
    VerificationCodeMailer smtpVerificationCodeMailer(
            JavaMailSender mailSender,
            @Value("${tapstate.cloud.auth.mail-from}") String from) {
        return (email, code) -> {
            try {
                var message = mailSender.createMimeMessage();
                var helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
                helper.setFrom(from);
                helper.setTo(email);
                helper.setSubject("Your Tapstate verification code");
                helper.setText(plainText(code), html(code));
                helper.addInline("tapstate-logo",
                    new ClassPathResource("email/tapstate-lockup-lightbg@2x.png"), "image/png");
                try {
                    mailSender.send(message);
                } catch (RuntimeException exception) {
                    Throwable cause = exception;
                    while (cause.getCause() != null && cause.getCause() != cause) {
                        cause = cause.getCause();
                    }
                    log.warn("Verification email transport failed (cause type: {})",
                        cause.getClass().getSimpleName());
                    throw exception;
                }
            } catch (MessagingException exception) {
                log.warn("Unable to prepare verification email ({})", exception.getClass().getSimpleName());
                throw new IllegalStateException("Unable to prepare verification email", exception);
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

    @Bean
    @ConditionalOnMissingBean(VerificationCodeMailer.class)
    VerificationCodeMailer unavailableVerificationCodeMailer() {
        return (email, code) -> {
            log.warn("Verification email fallback mailer invoked; SMTP mailer bean is unavailable");
            throw new IllegalStateException("Email delivery is not configured");
        };
    }
}
