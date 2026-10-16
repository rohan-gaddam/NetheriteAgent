package io.netherite.security.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Disables intermediary/browser caching on the session-bearing application and
 * management surfaces.
 *
 * <p>The transport and origin security headers (HSTS, CSP, nosniff, frame
 * policy, referrer policy, …) are emitted by the one and only untrusted-entry
 * point — the Caddy edge (infrastructure/caddy/Caddyfile). The app deliberately
 * does not re-stamp them, otherwise proxied responses would carry each header
 * twice. This filter therefore contributes only what the edge cannot: the
 * {@code Cache-Control: no-store} guard, applied before any other filter so it
 * is present even on authentication and rate-limit rejections.</p>
 */
@Component
@Order(0)
public class SecurityHeadersFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String path = request.getRequestURI();
        if (path.startsWith("/v1/") || path.startsWith("/actuator/")) {
            response.setHeader("Cache-Control", "no-store");
        }

        filterChain.doFilter(request, response);
    }
}