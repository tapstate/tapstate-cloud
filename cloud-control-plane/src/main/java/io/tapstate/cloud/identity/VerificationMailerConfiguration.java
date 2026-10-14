package io.tapstate.cloud.identity;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

@Configuration(proxyBeanMethods = false)
public class VerificationMailerConfiguration {

    @Bean
    @Profile("!it")
    @ConditionalOnProperty(prefix = "spring.mail", name = "host")
    VerificationCodeMailer smtpVerificationCodeMailer(
            JavaMailSender mailSender,
            @Value("${tapstate.cloud.auth.mail-from}") String from) {
        return (email, code) -> {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(email);
            message.setSubject("Your Tapstate verification code");
            message.setText("Your Tapstate verification code is " + code + ". It expires in 10 minutes.");
            mailSender.send(message);
        };
    }

    @Bean
    @ConditionalOnMissingBean(VerificationCodeMailer.class)
    VerificationCodeMailer unavailableVerificationCodeMailer() {
        return (email, code) -> {
            throw new IllegalStateException("Email delivery is not configured");
        };
    }
}
