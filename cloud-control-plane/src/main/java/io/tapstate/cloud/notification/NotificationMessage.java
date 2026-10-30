package io.tapstate.cloud.notification;

/**
 * A fully rendered notification ready for one channel.
 * {@code htmlBody} is optional; when present the channel sends a multipart
 * message with {@code textBody} as the plain-text alternative.
 */
public record NotificationMessage(
    String recipient,
    String subject,
    String textBody,
    String htmlBody,
    boolean inlineLogo
) {
}
