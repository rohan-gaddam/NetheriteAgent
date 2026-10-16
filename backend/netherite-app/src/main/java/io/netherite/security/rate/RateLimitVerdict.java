package io.netherite.security.rate;

/**
 * Filter-level outcome of one rate-limit evaluation.
 *
 * @param decision          ALLOW (continue), DENY (429) or DEPENDENCY_UNAVAILABLE (policy decides 503 vs allow)
 * @param retryAfterSeconds seconds to echo via {@code Retry-After} on a 429
 */
public record RateLimitVerdict(Decision decision, int retryAfterSeconds) {

    public enum Decision {
        ALLOW,
        DENY,
        DEPENDENCY_UNAVAILABLE
    }

    public static RateLimitVerdict allow() {
        return new RateLimitVerdict(Decision.ALLOW, 0);
    }

    public static RateLimitVerdict deny(int retryAfterSeconds) {
        return new RateLimitVerdict(Decision.DENY, Math.max(1, retryAfterSeconds));
    }

    public static RateLimitVerdict dependencyUnavailable() {
        return new RateLimitVerdict(Decision.DEPENDENCY_UNAVAILABLE, 0);
    }
}