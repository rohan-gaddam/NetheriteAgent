package io.netherite.security.filter;

import io.netherite.auth.session.SessionCookieService;
import io.netherite.common.context.TenantContext;
import io.netherite.domain.entity.UserEntity;
import io.netherite.domain.entity.UserSessionEntity;
import io.netherite.domain.repository.UserRepository;
import io.netherite.domain.repository.UserSessionRepository;
import io.netherite.domain.service.OrganizationAuthorizationService;
import io.netherite.security.context.TenantContextHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Gates Spring Boot Actuator behind human-session authentication at the origin.
 *
 * <p>Spring Security is deliberately not on the classpath (custom servlet-filter
 * chain), so the actuator auto-config has no principal to consult. This filter
 * therefore implements the authorization itself:</p>
 *
 * <ul>
 *   <li>{@code /actuator/health} and every probe/group beneath it ({@code /liveness},
 *       {@code /readiness}, …) stay <em>public</em> — orchestrators, the compose
 *       healthchecks and the edge only consume UP/DOWN status.</li>
 *   <li>Every other exposed endpoint ({@code info}, {@code metrics},
 *       {@code prometheus}, …) requires a valid human session belonging to that
 *       organization's <em>single designated authorized person</em> (WS6; the
 *       previous ORG_ADMIN/PLATFORM_ADMIN role check is removed).</li>
 * </ul>
 *
 * <p>The public edge additionally returns 404 for the management-heavy actuator
 * paths before they ever reach an application socket (Caddyfile); this filter is
 * the origin-level guarantee for direct internal calls and for any misconfigured
 * LB that routes management traffic to the app directly.</p>
 */
@Slf4j
@Component
@Order(4)
@RequiredArgsConstructor
public class ActuatorAccessFilter extends OncePerRequestFilter {

    private static final String HEALTH_BASE = "/actuator/health";

    private final UserSessionRepository userSessionRepository;
    private final UserRepository userRepository;
    private final SessionCookieService sessionCookieService;
    private final OrganizationAuthorizationService organizationAuthorizationService;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/actuator/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        if (isPublicHealthPath(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }

        String sessionToken = sessionCookieService.readSessionToken(request);
        if (sessionToken == null || sessionToken.isBlank()) {
            sendAccessDenied(response, HttpStatus.UNAUTHORIZED.value(), "ERR_AUTHENTICATION_REQUIRED",
                    "Management endpoint requires an authenticated admin session");
            return;
        }

        UserSessionEntity session = sessionCookieService.validateAndLoad(sessionToken);
        if (session == null) {
            sendAccessDenied(response, HttpStatus.UNAUTHORIZED.value(), "ERR_AUTHENTICATION_REQUIRED",
                    "Invalid or expired admin session");
            return;
        }

        UserEntity user = userRepository.findById(session.getUserId()).orElse(null);
        if (user == null || !"ACTIVE".equals(user.getStatus())) {
            sendAccessDenied(response, HttpStatus.UNAUTHORIZED.value(), "ERR_AUTHENTICATION_REQUIRED",
                    "Admin account is not available");
            return;
        }

        // WS6: no roles. The organization is taken from the user record, and the
        // session must belong to that organization's designated authorized
        // person. An organization with no designated person fails closed here
        // too, so an unadministered org cannot read management metrics.
        TenantContext tenantContext = TenantContext.builder()
                .organizationId(user.getOrganizationId().toString())
                .userId(user.getId().toString())
                .principalType("USER")
                .build();
        TenantContextHolder.setContext(tenantContext);

        boolean designated;
        try {
            designated = organizationAuthorizationService.isAuthorizedPersonForTenant();
        } finally {
            TenantContextHolder.clear();
        }
        if (!designated) {
            sendAccessDenied(response, HttpStatus.FORBIDDEN.value(), "ERR_FORBIDDEN",
                    "Management endpoint requires this organization's designated authorized person");
            return;
        }

        TenantContextHolder.setContext(tenantContext);

        try {
            filterChain.doFilter(request, response);
        } finally {
            TenantContextHolder.clear();
        }
    }

    private boolean isPublicHealthPath(String path) {
        return path.equals(HEALTH_BASE) || path.startsWith(HEALTH_BASE + "/");
    }

    private void sendAccessDenied(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                java.util.Map.of("error", code, "message", message)));
    }
}