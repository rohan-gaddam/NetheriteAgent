package io.netherite.security.rate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netherite.observability.NetheriteMetrics;
import io.netherite.observability.SecurityEvents;
import io.netherite.observability.ThrottledRate;
import io.netherite.security.provider.ApiKeyAgentAuthenticationProvider;
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
 * Pre-authentication rate-limit tier ({@code @Order(0)}).
 *
 * <p>Runs ahead of <em>every</em> security filter, so floods on login, OIDC and the agent
 * surfaces are stopped before any BCrypt verification or database work begins. Resolves only
 * dimensions available outside authentication: the source IP ({@link ClientIpResolver}), the
 * login account (parsed from the request body, hashed before it ever reaches a Redis key) and the
 * API-key identifier (extracted from the credential header without verifying it — the identifier
 * is protocol-public, never a secret).</p>
 *
 * <p>Per-tenant dimensions are enforced by {@link IdentityRateLimitFilter} once the trusted
 * {@link io.netherite.security.context.TenantContextHolder} context exists.</p>
 */
@Slf4j
@Component
@Order(0)
@RequiredArgsConstructor
public class PreAuthRateLimitFilter extends OncePerRequestFilter {

    /** Upper bound for the login body that is buffered and parsed for the account dimension. */
    private static final int MAX_WRAPPED_BODY_BYTES = 8 * 1024;

    private final RateLimitService rateLimitService;
    private final RateLimitProperties properties;
    private final ClientIpResolver clientIpResolver;
    private final RateLimitResponseWriter responseWriter;
    private final ObjectMapper objectMapper;
    private final NetheriteMetrics metrics;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !properties.isEnabled() || !request.getRequestURI().startsWith("/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String method = request.getMethod();
        String path = request.getRequestURI();

        HttpServletRequest effectiveRequest = request;
        String loginAccount = null;
        if (rateLimitService.routeNeedsLoginAccount(method, path) && isSafelyWrappable(request)) {
            byte[] body = request.getInputStream().readAllBytes();
            effectiveRequest = new ReplayableBodyRequestWrapper(request, body);
            loginAccount = extractLoginAccount(body);
        }

        RateLimitVerdict verdict = rateLimitService.evaluatePreAuth(
                method,
                path,
                clientIpResolver.resolve(request),
                loginAccount,
                extractAgentKeyIdentifier(request));

        if (verdict.decision() == RateLimitVerdict.Decision.DENY) {
            observeRejection("pre_auth", method, path);
            responseWriter.sendTooManyRequests(response, verdict.retryAfterSeconds());
            return;
        }
        if (verdict.decision() == RateLimitVerdict.Decision.DEPENDENCY_UNAVAILABLE) {
            responseWriter.sendDependencyUnavailable(response);
            return;
        }

        filterChain.doFilter(effectiveRequest, response);
    }

    private boolean isSafelyWrappable(HttpServletRequest request) {
        long contentLength = request.getContentLengthLong();
        // Only buffer bounded bodies whose full content fits the cache; oversized bodies are
        // left untouched so downstream @RequestBody parsing is never consumed by this filter.
        return contentLength > 0 && contentLength <= MAX_WRAPPED_BODY_BYTES;
    }

    /**
     * Extracts the normalized account identifier from a fully buffered (bounded) body. Empty or
     * unparseable payloads fall back to "unknown" so the per-IP bucket still bounds the flood.
     */
    private String extractLoginAccount(byte[] body) {
        try {
            JsonNode node = objectMapper.readTree(body);
            String email = node.path("email").asText(null);
            if (email == null || email.isBlank()) {
                return "unknown";
            }
            return email.trim().toLowerCase();
        } catch (Exception e) {
            // Unparseable or empty body: keep the per-IP bucket bounding the flood.
            return "unknown";
        }
    }

    private String extractAgentKeyIdentifier(HttpServletRequest request) {
        String keyHeader = request.getHeader(ApiKeyAgentAuthenticationProvider.HEADER_AGENT_KEY);
        if (keyHeader == null || keyHeader.isBlank()) {
            keyHeader = request.getHeader(ApiKeyAgentAuthenticationProvider.HEADER_AUTHORIZATION);
        }
        if (keyHeader == null || keyHeader.isBlank()) {
            return null;
        }
        String rawKey = keyHeader.trim();
        if (rawKey.startsWith(ApiKeyAgentAuthenticationProvider.BEARER_PREFIX)) {
            rawKey = rawKey.substring(ApiKeyAgentAuthenticationProvider.BEARER_PREFIX.length()).trim();
        }
        int separator = rawKey.indexOf('.');
        if (separator <= 0) {
            return null;
        }
        return rawKey.substring(0, separator).trim();
    }

    /**
     * WS8: every bucket rejection bumps a bounded counter; a throttled warning (once per
     * route per 30s) keeps an active flood visible without log-storming the sink.
     */
    private void observeRejection(String tier, String method, String path) {
        RateLimitProperties.RouteSpec route = rateLimitService.matchRoute(method, path);
        String routeName = route != null ? route.getName() : "unknown";
        metrics.rateLimitRejected(tier, routeName);
        if (ThrottledRate.GLOBAL_30S.tryEmitOncePer("ratelimit:reject:" + tier + ":" + routeName)) {
            SecurityEvents.warn(log, "security.rate_limit",
                    "Rate limit rejection active: tier={}, route={}", tier, routeName);
        }
    }
}