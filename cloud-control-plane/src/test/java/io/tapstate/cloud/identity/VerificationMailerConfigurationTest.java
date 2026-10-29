package io.tapstate.cloud.identity;

import io.tapstate.cloud.notification.EmailTemplateRenderer;
import io.tapstate.cloud.notification.NotificationDeliveryService;
import io.tapstate.cloud.notification.NotificationRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VerificationMailerConfigurationTest {

    @Mock
    private NotificationDeliveryService notifications;

    @Test
    void sendsVerificationCodeThroughTheNotificationService() {
        when(notifications.sendNow(any())).thenReturn(true);
        VerificationCodeMailer mailer = new VerificationMailerConfiguration()
            .notificationVerificationCodeMailer(notifications, new EmailTemplateRenderer());

        assertDoesNotThrow(() -> mailer.send("guest@example.com", "123456"));

        ArgumentCaptor<NotificationRequest> captor = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notifications).sendNow(captor.capture());
        NotificationRequest request = captor.getValue();
        assertTrue("guest@example.com".equals(request.recipient()));
        assertTrue("EMAIL".equals(request.channel()));
        assertTrue(request.textBody().contains("123456"));
        assertTrue(request.htmlBody().contains("123456"));
        assertTrue(request.inlineLogo());
        assertTrue(request.notificationKey().startsWith("auth-verification:"));
    }

    @Test
    void failsWhenTheNotificationServiceRejectsTheMessage() {
        when(notifications.sendNow(any())).thenReturn(false);
        VerificationCodeMailer mailer = new VerificationMailerConfiguration()
            .notificationVerificationCodeMailer(notifications, new EmailTemplateRenderer());

        assertThrows(IllegalStateException.class, () -> mailer.send("guest@example.com", "123456"));
    }
}
