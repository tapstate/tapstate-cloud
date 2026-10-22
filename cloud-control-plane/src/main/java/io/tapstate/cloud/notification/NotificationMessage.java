package io.tapstate.cloud.notification;

/** A fully rendered notification ready for one channel. */
public record NotificationMessage(String recipient, String subject, String body) {
}
