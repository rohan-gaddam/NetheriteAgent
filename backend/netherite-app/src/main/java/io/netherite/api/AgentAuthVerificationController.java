package io.netherite.api;

import io.netherite.common.context.TenantContext;
import io.netherite.security.context.TenantContextHolder;
import io.netherite.security.filter.AgentAuthenticationFilter;
import io.netherite.security.principal.AuthenticatedAgentPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Controller to exercise and verify Phase 2 Agent Authentication and Context Establishment.
 */
@RestController
@RequestMapping("/v1/actions")
public class AgentAuthVerificationController {

    @GetMapping("/auth-check")
    public ResponseEntity<Map<String, Object>> checkAuth(HttpServletRequest request) {
        TenantContext tenantContext = TenantContextHolder.getContext();
        Object principal = request.getAttribute(AgentAuthenticationFilter.ATTR_AUTHENTICATED_PRINCIPAL);

        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("authenticated", tenantContext != null);
        body.put("principalType", tenantContext != null ? tenantContext.getPrincipalType() : null);
        if (tenantContext != null) {
            body.put("organizationId", tenantContext.getOrganizationId());
            body.put("agentId", tenantContext.getAgentId());
        }
        if (principal instanceof AuthenticatedAgentPrincipal agentPrincipal) {
            body.put("keyIdentifier", agentPrincipal.keyIdentifier());
            body.put("trustTier", agentPrincipal.trustTier());
            body.put("mechanism", agentPrincipal.authenticationMechanism());
        }
        return ResponseEntity.ok(body);
    }

    @PostMapping("/echo-context")
    public ResponseEntity<Map<String, Object>> echoContext(
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {

        TenantContext tenantContext = TenantContextHolder.getContext();
        Object principal = request.getAttribute(AgentAuthenticationFilter.ATTR_AUTHENTICATED_PRINCIPAL);

        // Server strictly uses trusted context, rejecting any client attempt to override identity
        java.util.Map<String, Object> responseBody = new java.util.LinkedHashMap<>();
        responseBody.put("trustedOrganizationId", tenantContext != null ? tenantContext.getOrganizationId() : null);
        responseBody.put("trustedAgentId", tenantContext != null ? tenantContext.getAgentId() : null);
        if (principal instanceof AuthenticatedAgentPrincipal agentPrincipal) {
            responseBody.put("keyIdentifier", agentPrincipal.keyIdentifier());
        }
        responseBody.put("clientPayloadSupplied", body != null ? body : Map.of());
        return ResponseEntity.ok(responseBody);
    }
}
