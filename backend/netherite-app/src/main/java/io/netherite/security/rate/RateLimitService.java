package io.netherite.security.rate;

import io.netherite.common.context.TenantContext;
import io.netherite.observability.NetheriteMetrics;
import io.netherite.observability.SecurityEvents;
import io.netherite.observability.ThrottledRate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Route-matching rate-limit orchestrator shared by the two filter tiers.
 *
 * <p><b>Pre-auth tier</b> ({@code @Order(0)}, before any BCrypt/DB work) evaluates the IP,
 * account and API-key-identifier buckets so floods are stopped before even cheap credential work
 * begins. <b>Identity tier</b> ({@code @Order(4)}, inside the authenticated
 * {@link io.netherite.security.context.TenantContextHolder} scope) evaluates per-tenant, per-user
 * and per-agent buckets using only server-derived identities — no client-controllable header can
 * pick a bucket.</p>
 *
 * <p>Dependency behavior: a Redis error is surfaced as {@code DEPENDENCY_UNAVAILABLE}. Routes
 * flagged {@code fail-closed} translate that into a 503; everyone else follows
 * {@code default-on-redis-error} (fail-open). The decision is never silently degraded to a
 * per-node-only limiter.</p>
 */
@Slf4j
@Service
public class RateLimitService {

    private final RateLimitProperties properties;
    private final RateLimitDependencyGuard dependencyGuard;
    private final RateLimitStore rateLimitStore;
    private final NetheriteMetrics metrics;

    /** @deprecated kept only to preserve direct-construction tests; Spring uses the autowired ctor. */
    public RateLimitService(RateLimitProperties properties,
                            RateLimitDependencyGuard dependencyGuard,
                            RateLimitStore rateLimitStore) {
        this(properties, dependencyGuard, rateLimitStore, null);
    }

    @Autowired
    public RateLimitService(RateLimitProperties properties,
                            RateLimitDependencyGuard dependencyGuard,
                            RateLimitStore rateLimitStore,
                            NetheriteMetrics metrics) {
        this.properties = properties;
        this.dependencyGuard = dependencyGuard;
        this.rateLimitStore = rateLimitStore;
        this.metrics = metrics != null ? metrics : NetheriteMetrics.NOOP;
    }

    /**
     * Pre-authentication evaluation: buckets keyed on IP, login account and API-key identifier.
     */
    public RateLimitVerdict evaluatePreAuth(String method, String path,
                                            String clientIp, String loginAccount,
                                            String agentKeyIdentifier) {
        if (!properties.isEnabled()) {
            return RateLimitVerdict.allow();
        }
        RateLimitProperties.RouteSpec route = matchRoute(method, path);
        if (route == null) {
            return RateLimitVerdict.allow();
        }

        RateLimitKeyBuilder resolved = RateLimitKeyBuilder.builder()
                .ip(clientIp)
                .account(loginAccount)
                .agentKeyIdentifier(agentKeyIdentifier)
                .build();

        List<RateLimitStore.BucketKey> buckets = resolvableBuckets(route,
                List.of(RateLimitDimension.IP, RateLimitDimension.LOGIN_ACCOUNT,
                        RateLimitDimension.LOGIN_ACCOUNT_IP, RateLimitDimension.AGENT_KEY_IDENTIFIER),
                resolved);
        if (buckets.isEmpty()) {
            return RateLimitVerdict.allow();
        }
        return evaluate(route, buckets);
    }

    /**
     * Authenticated evaluation: buckets keyed on organization, user and agent from the tenant context.
     */
    public RateLimitVerdict evaluateIdentity(String method, String path, TenantContext context) {
        if (!properties.isEnabled()) {
            return RateLimitVerdict.allow();
        }
        RateLimitProperties.RouteSpec route = matchRoute(method, path);
        if (route == null) {
            return RateLimitVerdict.allow();
        }

        RateLimitKeyBuilder resolved = RateLimitKeyBuilder.builder()
                .organizationId(context.getOrganizationId())
                .userId(context.getUserId())
                .agentId(context.getAgentId())
                .build();

        List<RateLimitStore.BucketKey> buckets = resolvableBuckets(route,
                List.of(RateLimitDimension.ORGANIZATION, RateLimitDimension.USER, RateLimitDimension.AGENT),
                resolved);
        if (buckets.isEmpty()) {
            return RateLimitVerdict.allow();
        }
        return evaluate(route, buckets);
    }

    public boolean routeNeedsLoginAccount(String method, String path) {
        RateLimitProperties.RouteSpec route = matchRoute(method, path);
        if (route == null) {
            return false;
        }
        for (RateLimitProperties.BucketSpec bucket : route.getBuckets()) {
            if (bucket.getDimension() == RateLimitDimension.LOGIN_ACCOUNT
                    || bucket.getDimension() == RateLimitDimension.LOGIN_ACCOUNT_IP) {
                return true;
            }
        }
        return false;
    }

    RateLimitProperties.RouteSpec matchRoute(String method, String path) {
        String normalizedMethod = method == null ? "GET" : method.toUpperCase();
        String normalizedPath = path == null ? "" : path;
        for (RateLimitProperties.RouteSpec route : properties.getRoutes()) {
            if (route.matches(normalizedMethod, normalizedPath)) {
                return route;
            }
        }
        return null;
    }

    private List<RateLimitStore.BucketKey> resolvableBuckets(
            RateLimitProperties.RouteSpec route,
            List<RateLimitDimension> dimensions,
            RateLimitKeyBuilder resolved) {
        List<RateLimitStore.BucketKey> buckets = new ArrayList<>();
        for (RateLimitProperties.BucketSpec bucket : route.getBuckets()) {
            if (!dimensions.contains(bucket.getDimension())) {
                continue;
            }
            String key = resolved.bucketKey(route, bucket.getDimension());
            if (key == null) {
                continue;
            }
            buckets.add(new RateLimitStore.BucketKey(key, bucket.getLimit(), bucket.getWindowSeconds()));
        }
        return buckets;
    }

    private RateLimitVerdict evaluate(RateLimitProperties.RouteSpec route, List<RateLimitStore.BucketKey> buckets) {
        if (dependencyGuard.isOpen()) {
            boolean failClosed = isFailClosed(route);
            observeDependencyFailure(route);
            return failClosed ? RateLimitVerdict.dependencyUnavailable() : RateLimitVerdict.allow();
        }
        try {
            RateLimitDecision decision = rateLimitStore.checkAndIncrement(buckets);
            dependencyGuard.recordSuccess();
            return decision.allowed()
                    ? RateLimitVerdict.allow()
                    : RateLimitVerdict.deny(decision.retryAfterSeconds());
        } catch (RuntimeException e) {
            dependencyGuard.recordFailure();
            observeDependencyFailure(route);
            return isFailClosed(route) ? RateLimitVerdict.dependencyUnavailable() : RateLimitVerdict.allow();
        }
    }

    private boolean isFailClosed(RateLimitProperties.RouteSpec route) {
        if (route.getFailClosed() != null) {
            return route.getFailClosed();
        }
        return properties.getDefaultOnRedisError() == RateLimitProperties.OnRedisError.FAIL_CLOSED;
    }

    /**
     * WS8: Redis rate-limit store unavailability is a bounded counter plus a throttled
     * warning — at most one line per route per 30s, so a store outage cannot log-storm.
     */
    private void observeDependencyFailure(RateLimitProperties.RouteSpec route) {
        metrics.redisFailure("rate_limit");
        if (ThrottledRate.GLOBAL_30S.tryEmitOncePer("ratelimit:dependency:" + route.getName())) {
            SecurityEvents.warn(log, "security.rate_limit_dependency",
                    "Rate-limit store unavailable: route={}, fail-closed policy applies for this route",
                    route.getName());
        }
    }
}