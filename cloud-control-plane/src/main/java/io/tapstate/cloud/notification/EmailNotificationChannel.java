package io.tapstate.cloud.notification;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Email channel. Sending is disabled unless an operator explicitly enables it
 * and provides SMTP transport settings through the deployment environment.
 * <p>
 * The same channel serves plain-text alert mail and the multipart auth
 * verification mail, so there is one SMTP send path for every email.
 */
@Component
@Slf4j
public class EmailNotificationChannel implements NotificationChannel {

    private final ObjectProvider<JavaMailSender> mailSender;
    private final boolean enabled;
    private final String from;

    public EmailNotificationChannel(
            ObjectProvider<JavaMailSender> mailSender,
            @Value("${tapstate.cloud.notifications.email.enabled:false}") boolean enabled,
            @Value("${tapstate.cloud.notifications.email.from:no-reply@tapstate.com}") String from) {
        this.mailSender = mailSender;
        this.enabled = enabled;
        this.from = from;
    }

    @Override
    public String name() {
        return "EMAIL";
    }

    @Override
    public boolean isConfigured() {
        return enabled && mailSender.getIfAvailable() != null;
    }

    @Override
    public void send(NotificationMessage message) {
        if (!enabled) {
            throw new NotificationDeliveryException("email channel is disabled (tapstate.cloud.notifications.email.enabled=false)");
        }
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) {
            throw new NotificationDeliveryException("SMTP transport is not configured (spring.mail.host is unset)");
        }
        try {
            if (message.htmlBody() == null) {
                SimpleMailMessage mail = new SimpleMailMessage();
                mail.setFrom(from);
                mail.setTo(message.recipient());
                mail.setSubject(message.subject());
                mail.setText(message.textBody());
                sender.send(mail);
            } else {
                MimeMessage mime = sender.createMimeMessage();
                MimeMessageHelper helper =
                    new MimeMessageHelper(mime, true, StandardCharsets.UTF_8.name());
                helper.setFrom(from);
                helper.setTo(message.recipient());
                helper.setSubject(message.subject());
                helper.setText(message.textBody(), message.htmlBody());
                if (message.inlineLogo()) {
                    helper.addInline("tapstate-logo",
                        new ClassPathResource("email/tapstate-lockup-lightbg@2x.png"), "image/png");
                }
                sender.send(mime);
            }
            log.info("Notification email sent to {}", message.recipient());
        } catch (MessagingException e) {
            throw new NotificationDeliveryException("Unable to prepare email: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new NotificationDeliveryException("SMTP send failed: " + e.getMessage(), e);
        }
    }
}
