package io.netherite.security.rate;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for the distributed rate-limiting subsystem ({@code netherite.rate-limit}).
 *
 * <p>The subsystem is route-driven: a request is matched (first match wins) against an ordered
 * route table, and every {@link BucketSpec} declared by the matching route is evaluated. Buckets
 * whose dimension cannot be resolved for the current request tier are skipped — the pre-auth tier
 * resolves {@code IP}, {@code LOGIN_ACCOUNT}, {@code LOGIN_ACCOUNT_IP} and {@code AGENT_KEY_IDENTIFIER};
 * the identity tier resolves {@code ORGANIZATION}, {@code USER} and {@code AGENT} from the authenticated
 * {@link io.netherite.common.context.TenantContext}.</p>
 *
 * <p>Security-sensitive routes declare {@code fail-closed: true}: when the distributed rate-limit
 * dependency cannot make a trustworthy decision (Redis unreachable), the request is denied with
 * {@code ERR_SYSTEM_UNAVAILABLE} (503) instead of being admitted. Non-critical surface keeps the
 * operator-chosen global default ({@code fail-open}) so a Redis outage does not take the entire
 * application down. Every key is bounded by a TTL equal to its {@code window-seconds}, so the
 * key space can never grow without bound.</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "netherite.rate-limit")
public class RateLimitProperties {

    /** Master switch for the rate-limiting filters. */
    private boolean enabled = true;

    /** Policy applied when the distributed dependency cannot decide, unless a route overrides it. */
    private OnRedisError defaultOnRedisError = OnRedisError.FAIL_OPEN;

    private Dependency dependency = new Dependency();

    /** Ordered route table. First matching method+path wins. */
    private List<RouteSpec> routes = new ArrayList<>();

    public enum OnRedisError {
        FAIL_OPEN,
        FAIL_CLOSED
    }

    @Data
    public static class Dependency {
        /** Connect timeout for the dedicated rate-limit Redis client (ms). */
        private int connectTimeoutMillis = 500;
        /** Per-command timeout for the dedicated rate-limit Redis client (ms). */
        private int timeoutMillis = 500;
        /** Retry attempts before a rate-limit Redis command is abandoned. */
        private int retryAttempts = 0;
        /** Consecutive dependency failures before the local circuit breaker opens. */
        private int circuitBreakFailures = 3;
        /** How long the circuit breaker stays open before probing Redis again (ms). */
        private long circuitBreakOpenMillis = 5000;
    }

    @Data
    public static class RouteSpec {
        /** Stable identifier used in log lines. */
        private String name;
        /** HTTP methods this route applies to; empty means all methods. */
        private List<String> methods = new ArrayList<>();
        /** Spring path pattern, e.g. {@code /v1/auth/login} or {@code /v1/actions/**}. */
        private String path;
        /** Overrides {@link RateLimitProperties#defaultOnRedisError} for this route. */
        private Boolean failClosed;
        /** Buckets declared by this route. */
        private List<BucketSpec> buckets = new ArrayList<>();
        /** Parsed path pattern (built lazily, not bound). */
        private PathPattern compiledPath;

        PathPattern compiledPath() {
            if (compiledPath == null) {
                compiledPath = PathPatternParser.defaultInstance.parse(path);
            }
            return compiledPath;
        }

        public boolean matches(String method, String requestPath) {
            if (methods != null && !methods.isEmpty() && !methods.contains(method)) {
                return false;
            }
            return compiledPath().matches(org.springframework.http.server.PathContainer.parsePath(requestPath));
        }
    }

    @Data
    public static class BucketSpec {
        /** Dimension this bucket is keyed on. */
        private RateLimitDimension dimension;
        /** Maximum admitted requests within {@code windowSeconds}. */
        private int limit;
        /** Fixed-window duration in seconds (also the key TTL bound). */
        private long windowSeconds;
    }
}