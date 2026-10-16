package io.netherite.security.rate;

import io.netherite.common.context.TenantContext;
import io.netherite.observability.NetheriteMetrics;
import io.netherite.observability.SecurityEvents;
import io.netherite.observability.ThrottledRate;
import io.netherite.security.context.TenantContextHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Identity rate-limit tier running at {@code @Order(4)}, inside the authenticated
 * {@link TenantContextHolder} scope established by the user/agent authentication filters.
 *
 * <p>Enforces per-organization, per-user and per-agent buckets for the management, execution and
 * approval surfaces using only server-derived identities — a client can never select another
 * tenant's bucket by spoofing a header. Because it runs after {@code AuthorizationFilter}, rate
 * limits apply only to requests the principal is actually entitled to issue.</p>
 */
@Slf4j
@Component
@Order(4)
@RequiredArgsConstructor
public class IdentityRateLimitFilter extends OncePerRequestFilter {

    private final RateLimitService rateLimitService;
    private final RateLimitProperties properties;
    private final RateLimitResponseWriter responseWriter;
    private final NetheriteMetrics metrics;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !properties.isEnabled() || !request.getRequestURI().startsWith("/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        TenantContext context = TenantContextHolder.getContext();
        if (context == null) {
            filterChain.doFilter(request, response);
            return;
        }

        RateLimitVerdict verdict = rateLimitService.evaluateIdentity(
                request.getMethod(), request.getRequestURI(), context);

        if (verdict.decision() == RateLimitVerdict.Decision.DENY) {
            RateLimitProperties.RouteSpec route = rateLimitService.matchRoute(
                    request.getMethod(), request.getRequestURI());
            String routeName = route != null ? route.getName() : "unknown";
            metrics.rateLimitRejected("identity", routeName);
            // WS8 throttled warning: an active per-tenant flood is visible without log-storming.
            if (ThrottledRate.GLOBAL_30S.tryEmitOncePer("ratelimit:reject:identity:" + routeName)) {
                SecurityEvents.warn(log, "security.rate_limit",
                        "Rate limit rejection active: tier=identity, route={}", routeName);
            }
            responseWriter.sendTooManyRequests(response, verdict.retryAfterSeconds());
            return;
        }
        if (verdict.decision() == RateLimitVerdict.Decision.DEPENDENCY_UNAVAILABLE) {
            responseWriter.sendDependencyUnavailable(response);
            return;
        }

        filterChain.doFilter(request, response);
    }
}