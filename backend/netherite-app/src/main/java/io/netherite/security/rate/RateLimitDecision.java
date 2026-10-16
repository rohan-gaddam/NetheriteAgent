package io.netherite.security.rate;

/**
 * Result of a {@link RateLimitStore} evaluation for a request.
 *
 * @param allowed        whether the request may proceed this window
 * @param retryAfterSeconds seconds until the request should be retried (denials only)
 */
public record RateLimitDecision(boolean allowed, int retryAfterSeconds) {

    private static final RateLimitDecision ALLOWED = new RateLimitDecision(true, 0);

    public static RateLimitDecision allow() {
        return ALLOWED;
    }

    public static RateLimitDecision deny(int retryAfterSeconds) {
        return new RateLimitDecision(false, Math.max(1, retryAfterSeconds));
    }
}