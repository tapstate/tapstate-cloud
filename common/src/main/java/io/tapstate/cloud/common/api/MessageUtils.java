package io.tapstate.cloud.common.api;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.Locale;
import java.util.PropertyResourceBundle;
import java.util.ResourceBundle;

/**
 * Simplified message localization — wraps {@link ResourceBundle} + {@link MessageFormat}.
 * <p>
 * Mirrors hare {@code LanguageUtil} but WITHOUT plugin loading / custom MessageSource.
 * Tapstate Cloud needs only: {@code messages.properties} (en default) +
 * {@code messages_zh_CN.properties} (zh) bundled in {@code common/src/main/resources}.
 * <p>
 * Uses {@link UTF8Control} because Spring Boot 4.x (Java 17+) defaults to ISO-8859-1
 * for ResourceBundle, but we write properties in UTF-8 with raw Chinese characters.
 */
@Slf4j
public final class MessageUtils {

    private static final String BASE_NAME = "messages";

    private MessageUtils() {}

    /**
     * Resolve message from {@link ErrorCode} enum.
     * Falls back to {@link ErrorCode#getDescription()} when the ResourceBundle has no entry.
     *
     * @param locale target locale (null → {@link Locale#ENGLISH})
     * @param errorCode error code enum value
     * @param params MessageFormat placeholders ({0}, {1}, ...)
     */
    public static String getMessage(Locale locale, ErrorCode errorCode, Object... params) {
        return getMessage(locale, errorCode.getCode(), errorCode.getDescription(), params);
    }

    /**
     * Resolve message from raw key string.
     * Falls back to {@code defaultMessage} when ResourceBundle has no entry.
     */
    public static String getMessage(Locale locale, String key, String defaultMessage, Object... params) {
        if (locale == null) {
            locale = Locale.ENGLISH;
        }

        String raw = tryLoad(locale, key);
        if (raw == null && !locale.equals(Locale.ENGLISH)) {
            // Fallback to English default
            raw = tryLoad(Locale.ENGLISH, key);
        }

        if (raw == null || raw.isBlank()) {
            raw = defaultMessage != null ? defaultMessage : key;
        }

        if (params != null && params.length > 0) {
            try {
                return new MessageFormat(raw, locale).format(params);
            } catch (IllegalArgumentException e) {
                log.warn("Message format failed for key '{}': {}", key, e.getMessage());
                return raw;
            }
        }
        return raw;
    }

    private static String tryLoad(Locale locale, String key) {
        try {
            ResourceBundle bundle = ResourceBundle.getBundle(BASE_NAME, locale, new UTF8Control());
            return bundle.getString(key);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * UTF-8 aware ResourceBundle.Control — reads .properties files as UTF-8
     * instead of the JDK default ISO-8859-1, and disables the per-JVM-default-locale
     * fallback chain so that Locale.ENGLISH always resolves to {@code messages.properties}
     * rather than picking up the host's default-locale bundle.
     */
    private static class UTF8Control extends ResourceBundle.Control {

        @Override
        public Locale getFallbackLocale(String baseName, Locale locale) {
            // Return ROOT so ResourceBundle falls through to the default bundle
            // (messages.properties) instead of trying the JVM default locale.
            return Locale.ROOT;
        }

        @Override
        public ResourceBundle newBundle(String baseName, Locale locale, String format, ClassLoader loader, boolean reload)
                throws IOException {
            String bundleName = toBundleName(baseName, locale);
            String resourceName = toResourceName(bundleName, "properties");
            try (InputStream is = loader.getResourceAsStream(resourceName)) {
                if (is == null) return null;
                try (Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                    return new PropertyResourceBundle(reader);
                }
            }
        }
    }
}
