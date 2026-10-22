package io.tapstate.cloud.event;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Bounds event payloads before they are stored.
 * <p>
 * The event center records identifiers and outcomes, never request bodies,
 * credentials or customer data. This sanitizer enforces that contract for every
 * source: only scalar values and shallow containers survive, strings are
 * truncated, and keys that name a secret are redacted.
 */
public final class EventPayloadSanitizer {

    private static final int MAX_DEPTH = 3;
    private static final int MAX_KEYS = 50;
    private static final int MAX_LIST_ITEMS = 20;
    private static final int MAX_STRING = 500;

    private EventPayloadSanitizer() {
    }

    public static Map<String, Object> sanitize(Map<String, Object> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (raw == null) {
            return out;
        }
        Counter counter = new Counter();
        copyMap(raw, out, 0, counter);
        return out;
    }

    private static void copyMap(Map<?, ?> source, Map<String, Object> target, int depth, Counter counter) {
        if (depth >= MAX_DEPTH) {
            return;
        }
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (counter.keys >= MAX_KEYS) {
                return;
            }
            String key = String.valueOf(entry.getKey());
            if (key.length() > 80) {
                key = key.substring(0, 80);
            }
            target.put(key, isSecretKey(key)
                ? "[redacted]"
                : sanitizeValue(entry.getValue(), depth + 1, counter));
            counter.keys++;
        }
    }

    private static Object sanitizeValue(Object value, int depth, Counter counter) {
        if (value == null || value instanceof Boolean || value instanceof Number) {
            return value;
        }
        if (value instanceof String text) {
            return text.length() > MAX_STRING ? text.substring(0, MAX_STRING) : text;
        }
        if (value instanceof Map<?, ?> map) {
            if (depth >= MAX_DEPTH) {
                return "[truncated]";
            }
            Map<String, Object> nested = new LinkedHashMap<>();
            copyMap(map, nested, depth, counter);
            return nested;
        }
        if (value instanceof List<?> list) {
            List<Object> nested = new ArrayList<>();
            int limit = Math.min(list.size(), MAX_LIST_ITEMS);
            for (int i = 0; i < limit; i++) {
                nested.add(sanitizeValue(list.get(i), depth + 1, counter));
            }
            if (list.size() > limit) {
                nested.add("[truncated]");
            }
            return nested;
        }
        return String.valueOf(value);
    }

    private static boolean isSecretKey(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        return lower.contains("password")
            || lower.contains("secret")
            || lower.contains("token")
            || lower.contains("credential")
            || lower.contains("connectionstring")
            || lower.equals("uri")
            || lower.endsWith("uri");
    }

    private static final class Counter {
        private int keys;
    }
}
