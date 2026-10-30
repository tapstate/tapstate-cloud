package io.tapstate.cloud.event;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * Opaque cursor for event and alert listings. It carries the sort key of the
 * last item ({@code occurredAt}, {@code _id}) so pagination stays stable while
 * new events arrive.
 */
public record EventCursor(Instant occurredAt, String id) {

    public static String encode(Instant occurredAt, String id) {
        String raw = occurredAt.toEpochMilli() + ":" + id;
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static EventCursor decode(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int separator = raw.indexOf(':');
            if (separator <= 0 || separator == raw.length() - 1) {
                throw new IllegalArgumentException("cursor has no sort key");
            }
            return new EventCursor(
                Instant.ofEpochMilli(Long.parseLong(raw.substring(0, separator))),
                raw.substring(separator + 1));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid cursor", e);
        }
    }
}
