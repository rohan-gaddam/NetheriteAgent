package io.netherite.security.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netherite.common.context.TenantContext;
import io.netherite.observability.NetheriteMetrics;
import io.netherite.observability.SecurityEvents;
import io.netherite.security.context.TenantContextHolder;
import io.netherite.security.principal.AuthenticatedAgentPrincipal;
import io.netherite.security.provider.AgentAuthenticationProvider;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Component
@Order(2)
@RequiredArgsConstructor
public class AgentAuthenticationFilter extends OncePerRequestFilter {

    public static final String ATTR_AUTHENTICATED_PRINCIPAL = "netherite.authenticated.principal";
    private final List<AgentAuthenticationProvider> authenticationProviders;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final NetheriteMetrics metrics;

    /** Known agent-credential header names; used to distinguish "no credential" from
     *  "credential present but rejected" so only the latter is a security event. */
    private static final Set<String> CREDENTIAL_HEADERS = Set.of("X-Agent-Key", "Authorization", "X-API-Key");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        // Public discovery endpoint - no auth required
        if (path.equals("/v1/gateway/integration")) {
            return true;
        }
        // Auth endpoints handled by UserAuthenticationFilter
        if (path.startsWith("/v1/auth/")) {
            return true;
        }
        // Enforce agent authentication on all /v1/** endpoints
        return !path.startsWith("/v1/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        // If UserAuthenticationFilter already established a user context, skip agent auth
        io.netherite.common.context.TenantContext existing = TenantContextHolder.getContext();
        if (existing != null && "USER".equals(existing.getPrincipalType())) {
            try {
                filterChain.doFilter(request, response);
            } finally {
                TenantContextHolder.clear();
            }
            return;
        }

        Optional<AuthenticatedAgentPrincipal> principalOpt = Optional.empty();

        for (AgentAuthenticationProvider provider : authenticationProviders) {
            if (provider.supports(request)) {
                try {
                    principalOpt = provider.authenticate(request);
                } catch (Exception e) {
                    log.warn("Authentication provider '{}' threw exception: {}", provider.getMechanismName(), e.getMessage());
                    sendAuthenticationFailure(response, true);
                    return;
                }
                if (principalOpt.isPresent()) {
                    break;
                }
            }
        }

        if (principalOpt.isEmpty()) {
            // Authentication failed on protected endpoint. Credentials that were actually
            // presented and rejected are a security event; a bare, credential-less request
            // is ordinary scanner noise and only bumps the bounded counter.
            boolean credentialPresent = hasCredentialHeader(request);
            sendAuthenticationFailure(response, credentialPresent);
            return;
        }

        AuthenticatedAgentPrincipal principal = principalOpt.get();

        // Establish trusted thread-local tenant and agent context.
        // WS6: no roles are projected; agent principals are authorized purely by
        // principal type (machine-to-machine action surface only).
        TenantContext tenantContext = TenantContext.builder()
                .organizationId(principal.organizationId().toString())
                .agentId(principal.agentId().toString())
                .principalType("AGENT")
                .build();

        TenantContextHolder.setContext(tenantContext);
        request.setAttribute(ATTR_AUTHENTICATED_PRINCIPAL, principal);

        try {
            filterChain.doFilter(request, response);
        } finally {
            TenantContextHolder.clear();
        }
    }

    private void sendAuthenticationFailure(HttpServletResponse response, boolean credentialPresent) throws IOException {
        metrics.authFailure("agent_api_key");
        if (credentialPresent) {
            // WS8: only a presented-but-rejected credential is a security event worth a log
            // line; the counter above observes rejected credential-less requests too.
            SecurityEvents.warn(log, "security.auth_failure",
                    "Agent API-key authentication rejected: invalid or expired credentials");
        }

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        Map<String, Object> errorPayload = Map.of(
                "errorCode", "ERR_AUTHENTICATION_FAILED",
                "message", "Authentication failed: invalid or missing credentials",
                "timestamp", Instant.now().toString()
        );

        response.getWriter().write(objectMapper.writeValueAsString(errorPayload));
    }

    private boolean hasCredentialHeader(HttpServletRequest request) {
        for (String header : CREDENTIAL_HEADERS) {
            String value = request.getHeader(header);
            if (value != null && !value.isBlank()) {
                return true;
            }
        }
        return false;
    }
}
