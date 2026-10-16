package io.netherite.security.context;

import io.netherite.common.context.TenantContext;

/**
 * ThreadLocal container for the active TenantContext during request lifecycle.
 */
public final class TenantContextHolder {

    private static final ThreadLocal<TenantContext> CONTEXT = new ThreadLocal<>();

    private TenantContextHolder() {}

    public static void setContext(TenantContext tenantContext) {
        CONTEXT.set(tenantContext);
    }

    public static TenantContext getContext() {
        return CONTEXT.get();
    }

    public static String getRequiredOrganizationId() {
        TenantContext ctx = CONTEXT.get();
        if (ctx == null || ctx.getOrganizationId() == null) {
            throw new IllegalStateException("No active TenantContext found for the current thread");
        }
        return ctx.getOrganizationId();
    }

    public static TenantContext getRequiredContext() {
        TenantContext ctx = CONTEXT.get();
        if (ctx == null) {
            throw new IllegalStateException("No active TenantContext found for the current thread");
        }
        return ctx;
    }

    public static void clear() {
        CONTEXT.remove();
    }
}
