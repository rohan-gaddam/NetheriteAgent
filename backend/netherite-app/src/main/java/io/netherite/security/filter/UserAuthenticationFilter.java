package io.netherite.security.filter;

import io.netherite.auth.session.SessionCookieService;
import io.netherite.common.context.TenantContext;
import io.netherite.domain.entity.UserEntity;
import io.netherite.domain.entity.UserSessionEntity;
import io.netherite.domain.repository.UserRepository;
import io.netherite.domain.repository.UserSessionRepository;
import io.netherite.observability.NetheriteMetrics;
import io.netherite.observability.SecurityEvents;
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

@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class UserAuthenticationFilter extends OncePerRequestFilter {

    private final UserSessionRepository userSessionRepository;
    private final UserRepository userRepository;
    private final SessionCookieService sessionCookieService;
    private final NetheriteMetrics metrics;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/v1/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        // Human sessions are authenticated exclusively via the HttpOnly session cookie.
        String sessionToken = sessionCookieService.readSessionToken(request);

        if (sessionToken == null || sessionToken.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }

        UserSessionEntity session = sessionCookieService.validateAndLoad(sessionToken);

        if (session == null) {
            sendAuthenticationFailure(response, "Invalid or expired user session");
            return;
        }

        UserEntity user = userRepository.findById(session.getUserId()).orElse(null);

        if (user == null) {
            sendAuthenticationFailure(response, "User not found");
            return;
        }

        if (!"ACTIVE".equals(user.getStatus())) {
            sendAuthenticationFailure(response, "User account is not active");
            return;
        }

        // Session-bound organization identity: a session can never act outside
        // the organization it was issued for (cross-tenant isolation).
        //
        // WS6: no roles are projected. Authorization is decided downstream by
        // comparing this userId against the organization's single designated
        // authorized person.
        TenantContext tenantContext = TenantContext.builder()
                .organizationId(user.getOrganizationId().toString())
                .userId(user.getId().toString())
                .principalType("USER")
                .build();

        TenantContextHolder.setContext(tenantContext);

        try {
            filterChain.doFilter(request, response);
        } finally {
            TenantContextHolder.clear();
        }
    }

    private void sendAuthenticationFailure(HttpServletResponse response, String message) throws IOException {
        // WS8: observe the rejection (bounded counter) and emit a structured security event.
        // The message is always a fixed internal reason string, never client content.
        metrics.authFailure("user_session");
        SecurityEvents.warn(log, "security.auth_failure",
                "User session authentication rejected: reason={}", message);

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        java.util.Map<String, Object> errorPayload = java.util.Map.of(
                "error", "ERR_AUTHENTICATION_FAILED",
                "message", message
        );

        response.getWriter().write(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(errorPayload));
    }
}
