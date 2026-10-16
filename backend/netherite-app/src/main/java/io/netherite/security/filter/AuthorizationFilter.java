package io.netherite.security.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netherite.common.context.TenantContext;
import io.netherite.domain.service.OrganizationAuthorizationService;
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
import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * Enterprise authorization boundary for the /v1 control plane and action surfaces.
 *
 * <p>Authentication (UserAuthenticationFilter {@code @Order(1)},
 * AgentAuthenticationFilter {@code @Order(2)}) establishes a trusted
 * {@link TenantContext}. This filter (order 3) then enforces authorization.
 *
 * <h2>WS6 — the authorization model</h2>
 *
 * <p>There is <b>no role-based access control</b>. This filter used to consult a
 * role set on {@link TenantContext} against a permission matrix of
 * ORG_ADMIN / PLATFORM_ADMIN / POLICY_ADMIN / AUDITOR / HUMAN_APPROVER. All of
 * that is removed. Authorization is now a single question:</p>
 *
 * <blockquote><b>Is the caller the organization's single designated authorized
 * person?</b></blockquote>
 *
 * <p>The answer comes from {@code organizations.authorized_person_user_id} and is
 * resolved by {@link OrganizationAuthorizationService}, which is the single
 * trusted decision point. The organization is always taken from the
 * {@link TenantContext} the authentication filters established from the
 * session or API key — never from a request body, path variable, query
 * parameter or header — so a caller cannot reach another tenant's
 * configuration by supplying a different identifier.</p>
 *
 * <p>This filter governs the interactive, organization-scoped surfaces:</p>
 * <ul>
 *   <li><b>Configuration</b> — agents, resources, bindings, policies, users,
 *       control.</li>
 *   <li><b>Governance</b> — audit, dashboard, and approval decisions.</li>
 * </ul>
 *
 * <p>That applies to the designated person alone, not to a role: the single
 * person may administer the organization AND review its audit trail AND decide
 * its approval queue. See {@code docs/workstream-6-...} for the documented
 * separation-of-duties consequence.</p>
 *
 * <h2>Machine principals</h2>
 *
 * <p>{@code AGENT} principals (API keys) remain confined to the action surface:
 * {@code /v1/actions/**} and {@code /v1/gateway/execute}. They never administer
 * and never decide approvals. This preserves the existing machine-vs-human
 * separation: a protected action is always requested by a machine principal and
 * approved by a human.</p>
 *
 * <p>Unknown paths, missing context and unrecognized principal types are denied
 * (fail closed).</p>
 */
@Slf4j
@Component
@Order(3)
@RequiredArgsConstructor
public class AuthorizationFilter extends OncePerRequestFilter {

    public static final String ATTR_DENIED = "netherite.authorization.denied";

    /**
     * Surfaces an organization's designated authorized person administers.
     *
     * <p>Path prefixes are the first segment after {@code /v1/} so a nested
     * handler cannot bypass the check with a path trick.</p>
     */
    private static final Set<String> ADMINISTERED_SURFACES = Set.of(
            "agents", "resources", "bindings", "policies", "users", "control",
            "audit", "dashboard", "approvals");

    /**
     * Surfaces an AI-agent (machine) principal may exercise. Deliberately narrow:
     * a machine can request a protected action, never administer or approve.
     */
    private static final Set<String> MACHINE_SURFACES = Set.of("actions", "gateway");

    private final OrganizationAuthorizationService organizationAuthorizationService;
    private final NetheriteMetrics metrics;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (!path.startsWith("/v1/")) {
            return true;
        }
        if (path.startsWith("/v1/auth/")) {
            return true;
        }
        return path.equals("/v1/gateway/integration");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        TenantContext context = TenantContextHolder.getContext();
        if (context == null) {
            // No principal established on a protected /v1 surface: fail closed.
            sendUnauthorized(response);
            return;
        }

        if (!isAuthorized(context, request.getRequestURI())) {
            // WS8: the bounded security event records the principal type and the surface
            // (first path segment) only — never the full path, which can carry agent, ticket
            // or resource identifiers.
            String surface = firstSegment(request.getRequestURI());
            metrics.authorizationDenied(context.getPrincipalType(), surface);
            SecurityEvents.warn(log, "security.authorization_denied",
                    "Authorization denied: principalType={}, surface={}, method={}",
                    context.getPrincipalType(), surface, request.getMethod());
            request.setAttribute(ATTR_DENIED, Boolean.TRUE);
            sendForbidden(response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    /**
     * The entire WS6 authorization decision. No role is consulted anywhere.
     */
    private boolean isAuthorized(TenantContext context, String path) {
        String surface = firstSegment(path);

        if ("AGENT".equals(context.getPrincipalType())) {
            // Machine principals: request a protected action, nothing else.
            if (!MACHINE_SURFACES.contains(surface)) {
                return false;
            }
            if ("actions".equals(surface)) {
                return true;
            }
            // The gateway exposes exactly one machine operation: execution.
            // /v1/gateway/integration is excluded in shouldNotFilter; this is
            // defense in depth so no other gateway path can be reached.
            return path.equals("/v1/gateway/execute");
        }

        if ("USER".equals(context.getPrincipalType())) {
            if (!ADMINISTERED_SURFACES.contains(surface)) {
                // Fail closed: an unknown or unlisted surface is never authorized.
                return false;
            }
            // The one and only organizational authorization rule.
            return organizationAuthorizationService.isAuthorizedPersonForTenant();
        }

        // SYSTEM or unrecognized principal types never satisfy interactive endpoints.
        return false;
    }

    private static String firstSegment(String path) {
        String p = path.startsWith("/v1/") ? path.substring("/v1/".length()) : path;
        int slash = p.indexOf('/');
        String segment = slash < 0 ? p : p.substring(0, slash);
        return segment.isBlank() ? "unknown" : segment;
    }

    private void sendUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(Map.of(
                "errorCode", "ERR_AUTHENTICATION_FAILED",
                "message", "Authentication required",
                "timestamp", Instant.now().toString())));
    }

    private void sendForbidden(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(Map.of(
                "error", "ERR_FORBIDDEN",
                "message", "You are not authorized to access this resource",
                "timestamp", Instant.now().toString())));
    }
}
