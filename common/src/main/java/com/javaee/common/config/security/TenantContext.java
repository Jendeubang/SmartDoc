package com.javaee.common.config.security;

/**
 * Request-scoped tenant propagated only after server-side membership validation.
 */
public final class TenantContext {
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private TenantContext() {}

    public static void set(String organizationId) { CURRENT.set(organizationId); }
    public static String get() { return CURRENT.get(); }
    public static void clear() { CURRENT.remove(); }
}
