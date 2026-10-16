package io.netherite.api;

import io.netherite.common.exception.NetheriteException;
import io.netherite.common.exception.SystemErrorCode;
import io.netherite.domain.entity.AgentApiKeyEntity;
import io.netherite.domain.entity.AgentEntity;
import io.netherite.domain.repository.AgentApiKeyRepository;
import io.netherite.domain.repository.AgentRepository;
import io.netherite.domain.service.AgentDomainService;
import io.netherite.security.context.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import io.netherite.api.model.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/v1/agents")
@RequiredArgsConstructor
public class AgentManagementController {

    private final AgentDomainService agentDomainService;
    private final AgentApiKeyRepository apiKeyRepository;
    private final AgentRepository agentRepository;

    @PostMapping
    public ResponseEntity<?> registerAgent(@RequestBody AgentRegistrationRequest request) {
        String orgId = TenantContextHolder.getRequiredOrganizationId();
        UUID orgUuid = UUID.fromString(orgId);

        try {
            AgentDomainService.AgentRegistrationResult result =
                    agentDomainService.registerAgent(orgUuid, request.getName(), request.getDescription(), request.getTrustTier());

            return ResponseEntity.ok(AgentResponse.builder()
                    .id(result.agent().getId().toString())
                    .organizationId(result.agent().getOrganizationId().toString())
                    .name(result.agent().getName())
                    .description(result.agent().getDescription())
                    .trustTier(result.agent().getTrustTier())
                    .status(result.agent().getStatus())
                    .secretToken(result.secretToken())
                    .keyIdentifier(result.keyIdentifier())
                    .fullApiKey(result.fullApiKey())
                    .createdAt(result.agent().getCreatedAt())
                    .build());
        } catch (NetheriteException e) {
            return ResponseEntity.status(e.getErrorCode().getHttpStatus()).body(Map.of(
                    "error", e.getErrorCode().name(),
                    "message", e.getErrorCode().getDefaultMessage()));
        }
    }

    @GetMapping
    public ResponseEntity<List<AgentResponse>> listAgents() {
        String orgId = TenantContextHolder.getRequiredOrganizationId();
        UUID orgUuid = UUID.fromString(orgId);

        List<AgentResponse> agents = agentDomainService.listAgents(orgUuid).stream()
                .map(this::toAgentResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(agents);
    }

    @GetMapping("/{agentId}")
    public ResponseEntity<?> getAgent(@PathVariable("agentId") String agentId) {
        String orgId = TenantContextHolder.getRequiredOrganizationId();
        UUID orgUuid = UUID.fromString(orgId);

        try {
            UUID agentUuid = UUID.fromString(agentId);
            AgentEntity agent = agentDomainService.getAgent(orgUuid, agentUuid);
            return ResponseEntity.ok(toAgentResponse(agent));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of(
                    "error", "AGENT_NOT_FOUND",
                    "message", "Agent not found"));
        } catch (NetheriteException e) {
            return ResponseEntity.status(e.getErrorCode().getHttpStatus()).body(Map.of(
                    "error", e.getErrorCode().name(),
                    "message", e.getErrorCode().getDefaultMessage()));
        }
    }

    @PutMapping("/{agentId}")
    public ResponseEntity<?> updateAgent(
            @PathVariable("agentId") String agentId,
            @RequestBody AgentUpdateRequest request) {
        String orgId = TenantContextHolder.getRequiredOrganizationId();
        UUID orgUuid = UUID.fromString(orgId);

        try {
            UUID agentUuid = UUID.fromString(agentId);
            AgentEntity agent = agentDomainService.getAgent(orgUuid, agentUuid);

            if (request.getName() != null) agent.setName(request.getName());
            if (request.getDescription() != null) agent.setDescription(request.getDescription());
            if (request.getTrustTier() != null) agent.setTrustTier(request.getTrustTier());
            agentRepository.save(agent);

            return ResponseEntity.ok(toAgentResponse(agent));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of(
                    "error", "AGENT_NOT_FOUND",
                    "message", "Agent not found"));
        } catch (NetheriteException e) {
            return ResponseEntity.status(e.getErrorCode().getHttpStatus()).body(Map.of(
                    "error", e.getErrorCode().name(),
                    "message", e.getErrorCode().getDefaultMessage()));
        }
    }

    @PostMapping("/{agentId}/deactivate")
    public ResponseEntity<?> deactivateAgent(@PathVariable("agentId") String agentId) {
        String orgId = TenantContextHolder.getRequiredOrganizationId();
        UUID orgUuid = UUID.fromString(orgId);

        try {
            UUID agentUuid = UUID.fromString(agentId);
            AgentEntity agent = agentDomainService.getAgent(orgUuid, agentUuid);
            agent.setStatus("INACTIVE");
            agentRepository.save(agent);
            return ResponseEntity.ok(toAgentResponse(agent));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of(
                    "error", "AGENT_NOT_FOUND",
                    "message", "Agent not found"));
        } catch (NetheriteException e) {
            return ResponseEntity.status(e.getErrorCode().getHttpStatus()).body(Map.of(
                    "error", e.getErrorCode().name(),
                    "message", e.getErrorCode().getDefaultMessage()));
        }
    }

    @PostMapping("/{agentId}/activate")
    public ResponseEntity<?> activateAgent(@PathVariable("agentId") String agentId) {
        String orgId = TenantContextHolder.getRequiredOrganizationId();
        UUID orgUuid = UUID.fromString(orgId);

        try {
            UUID agentUuid = UUID.fromString(agentId);
            AgentEntity agent = agentDomainService.getAgent(orgUuid, agentUuid);
            agent.setStatus("ACTIVE");
            agentRepository.save(agent);
            return ResponseEntity.ok(toAgentResponse(agent));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of(
                    "error", "AGENT_NOT_FOUND",
                    "message", "Agent not found"));
        } catch (NetheriteException e) {
            return ResponseEntity.status(e.getErrorCode().getHttpStatus()).body(Map.of(
                    "error", e.getErrorCode().name(),
                    "message", e.getErrorCode().getDefaultMessage()));
        }
    }

    @GetMapping("/{agentId}/keys")
    public ResponseEntity<?> listApiKeys(@PathVariable("agentId") String agentId) {
        String orgId = TenantContextHolder.getRequiredOrganizationId();
        UUID orgUuid = UUID.fromString(orgId);

        try {
            UUID agentUuid = UUID.fromString(agentId);
            agentDomainService.getAgent(orgUuid, agentUuid); // tenant check

            List<Map<String, Object>> keys = apiKeyRepository.findAllByAgentIdAndOrganizationId(agentUuid, orgUuid)
                    .stream()
                    .map(this::toKeySummary)
                    .collect(Collectors.toList());

            return ResponseEntity.ok(keys);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of(
                    "error", "AGENT_NOT_FOUND",
                    "message", "Agent not found"));
        } catch (NetheriteException e) {
            return ResponseEntity.status(e.getErrorCode().getHttpStatus()).body(Map.of(
                    "error", e.getErrorCode().name(),
                    "message", e.getErrorCode().getDefaultMessage()));
        }
    }

    @PostMapping("/{agentId}/keys")
    public ResponseEntity<?> createApiKey(
            @PathVariable("agentId") String agentId,
            @RequestBody(required = false) Map<String, Object> body) {
        String orgId = TenantContextHolder.getRequiredOrganizationId();
        UUID orgUuid = UUID.fromString(orgId);

        try {
            UUID agentUuid = UUID.fromString(agentId);
            String description = body != null ? (String) body.get("description") : null;

            AgentDomainService.GeneratedApiKeyResult result =
                    agentDomainService.createApiKey(orgUuid, agentUuid, description, null);

            return ResponseEntity.ok(Map.of(
                    "keyId", result.keyId().toString(),
                    "keyIdentifier", result.keyIdentifier(),
                    "secretToken", result.secretToken(),
                    "fullApiKey", result.fullApiKey()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of(
                    "error", "AGENT_NOT_FOUND",
                    "message", "Agent not found"));
        } catch (NetheriteException e) {
            return ResponseEntity.status(e.getErrorCode().getHttpStatus()).body(Map.of(
                    "error", e.getErrorCode().name(),
                    "message", e.getErrorCode().getDefaultMessage()));
        }
    }

    @PostMapping("/{agentId}/keys/{keyId}/revoke")
    public ResponseEntity<?> revokeApiKey(
            @PathVariable("agentId") String agentId,
            @PathVariable("keyId") String keyId) {
        String orgId = TenantContextHolder.getRequiredOrganizationId();
        UUID orgUuid = UUID.fromString(orgId);

        try {
            UUID agentUuid = UUID.fromString(agentId);
            UUID keyUuid = UUID.fromString(keyId);
            agentDomainService.getAgent(orgUuid, agentUuid); // tenant check
            agentDomainService.revokeApiKey(orgUuid, keyUuid);
            return ResponseEntity.ok(Map.of("message", "API key revoked successfully"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of(
                    "error", "RESOURCE_NOT_FOUND",
                    "message", "Resource not found"));
        } catch (NetheriteException e) {
            return ResponseEntity.status(e.getErrorCode().getHttpStatus()).body(Map.of(
                    "error", e.getErrorCode().name(),
                    "message", e.getErrorCode().getDefaultMessage()));
        }
    }

    private AgentResponse toAgentResponse(AgentEntity agent) {
        return AgentResponse.builder()
                .id(agent.getId().toString())
                .organizationId(agent.getOrganizationId().toString())
                .name(agent.getName())
                .description(agent.getDescription())
                .trustTier(agent.getTrustTier())
                .status(agent.getStatus())
                .createdAt(agent.getCreatedAt())
                .build();
    }

    private Map<String, Object> toKeySummary(AgentApiKeyEntity key) {
        return Map.of(
                "keyId", key.getId().toString(),
                "keyIdentifier", key.getKeyIdentifier(),
                "description", key.getDescription() != null ? key.getDescription() : "",
                "valid", key.isValid(),
                "createdAt", key.getCreatedAt().toString());
    }
}
