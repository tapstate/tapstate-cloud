package io.tapstate.cloud.notification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Email channel. Sending is disabled unless an operator explicitly enables it
 * and provides SMTP transport settings through the deployment environment.
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
    public void send(NotificationMessage message) {
        if (!enabled) {
            throw new NotificationDeliveryException("email channel is disabled (tapstate.cloud.notifications.email.enabled=false)");
        }
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) {
            throw new NotificationDeliveryException("SMTP transport is not configured (spring.mail.host is unset)");
        }
        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setFrom(from);
        mail.setTo(message.recipient());
        mail.setSubject(message.subject());
        mail.setText(message.body());
        try {
            sender.send(mail);
            log.info("Notification email sent to {}", message.recipient());
        } catch (RuntimeException e) {
            throw new NotificationDeliveryException("SMTP send failed: " + e.getMessage(), e);
        }
    }
}
