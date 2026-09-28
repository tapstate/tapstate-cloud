package io.tapstate.cloud.common.api;

import java.util.Locale;

/**
 * Thread-local holder for the current request's {@link Locale}.
 * Set by {@link LocaleResolverFilter}, cleared after each request.
 */
public final class LocaleHolder {

    private static final ThreadLocal<Locale> HOLDER = new ThreadLocal<>();

    private LocaleHolder() {}

    public static void set(Locale locale) {
        HOLDER.set(locale);
    }

    public static Locale get() {
        Locale v = HOLDER.get();
        return v != null ? v : Locale.ENGLISH;
    }

    public static void clear() {
        HOLDER.remove();
    }
}
