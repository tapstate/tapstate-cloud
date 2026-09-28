package io.tapstate.cloud.common.api;

import java.util.UUID;

/**
 * Thread-local holder for the current request's {@code opId}.
 * Set by {@link OpIdFilter}, cleared after the request completes.
 */
public final class OpIdHolder {

    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

    private OpIdHolder() {}

    public static void set(String opId) {
        HOLDER.set(opId);
    }

    public static String get() {
        String v = HOLDER.get();
        return v != null ? v : UUID.randomUUID().toString();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
