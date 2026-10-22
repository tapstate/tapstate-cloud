package io.tapstate.cloud.notification;

/**
 * Outbound notification channel. This release ships {@code EMAIL}; SMS and
 * webhook implementations can be added without changing events or policies.
 */
public interface NotificationChannel {

    String name();

    void send(NotificationMessage message);
}
