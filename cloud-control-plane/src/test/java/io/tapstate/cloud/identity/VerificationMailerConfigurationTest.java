package io.tapstate.cloud.identity;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.mail.javamail.JavaMailSender;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VerificationMailerConfigurationTest {

    @Mock
    private JavaMailSender mailSender;

    @Test
    void sendsVerificationCodeWhenHtmlContainsLiteralPercentSigns() {
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(message);
        VerificationCodeMailer mailer = new VerificationMailerConfiguration()
            .smtpVerificationCodeMailer(mailSender, "no-reply@example.com");

        assertDoesNotThrow(() -> mailer.send("guest@example.com", "123456"));
        verify(mailSender).send(message);
    }
}
