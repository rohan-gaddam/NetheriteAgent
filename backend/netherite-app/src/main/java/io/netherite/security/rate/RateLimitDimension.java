package io.netherite.security.rate;

/**
 * The identity dimension a rate-limit bucket is keyed on.
 *
 * <p>Dimensions resolve to server-derived values only ({@link io.netherite.security.context.TenantContextHolder}
 * identities, the resolved client IP, a hashed account identifier, or the API-key <em>identifier</em> parsed
 * from the credential header — never a raw secret or credential). This prevents client-controllable header
 * spoofing from keying buckets (a client can invent any {@code X-Forwarded-For}, but can never choose the
 * tenant, user, or organization the server resolves from the authenticated session).</p>
 */
public enum RateLimitDimension {

    /** Source IP as resolved by the container (post trusted-proxy valve). */
    IP("ip"),

    /** SHA-256 of the normalized account identifier, scoped per source IP. */
    LOGIN_ACCOUNT_IP("login-account-ip"),

    /** SHA-256 of the normalized account identifier (all sources). */
    LOGIN_ACCOUNT("login-account"),

    /** SHA-256 of the API-key identifier presented in the credential header. */
    AGENT_KEY_IDENTIFIER("agent-key"),

    /** Organization id from the authenticated tenant context. */
    ORGANIZATION("org"),

    /** User id from the authenticated tenant context. */
    USER("user"),

    /** Agent id from the authenticated tenant context. */
    AGENT("agent");

    private final String label;

    RateLimitDimension(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}