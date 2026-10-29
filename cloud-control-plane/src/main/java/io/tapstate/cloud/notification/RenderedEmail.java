package io.tapstate.cloud.notification;

/** A fully rendered email with a plain-text alternative and optional inline logo. */
public record RenderedEmail(
    String subject,
    String textBody,
    String htmlBody,
    boolean inlineLogo
) {
}
