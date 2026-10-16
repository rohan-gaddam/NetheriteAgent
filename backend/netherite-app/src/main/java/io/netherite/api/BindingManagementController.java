package io.netherite.api;

import io.netherite.common.exception.NetheriteException;
import io.netherite.common.exception.SystemErrorCode;
import io.netherite.domain.entity.AgentResourceBindingEntity;
import io.netherite.domain.repository.AgentResourceBindingRepository;
import io.netherite.domain.service.BindingDomainService;
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
@RequestMapping("/v1/bindings")
@RequiredArgsConstructor
public class BindingManagementController {

    private final BindingDomainService bindingDomainService;
    private final AgentResourceBindingRepository bindingRepository;

    @PostMapping
    public ResponseEntity<?> createBinding(@RequestBody BindingCreateRequest request) {
        String orgId = TenantContextHolder.getRequiredOrganizationId();
        UUID orgUuid = UUID.fromString(orgId);

        try {
            UUID agentUuid = UUID.fromString(request.getAgentId());
            UUID resourceUuid = UUID.fromString(request.getResourceId());

            AgentResourceBindingEntity binding = bindingDomainService.bindAgentToResource(
                    orgUuid, agentUuid, resourceUuid, request.getAllowedActions());

            return ResponseEntity.ok(toBindingResponse(binding));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(400).body(Map.of(
                    "error", "ERR_INVALID_PARAMETERS",
                    "message", "Invalid agent or resource ID"));
        } catch (NetheriteException e) {
            return ResponseEntity.status(e.getErrorCode().getHttpStatus()).body(Map.of(
                    "error", e.getErrorCode().name(),
                    "message", e.getErrorCode().getDefaultMessage()));
        }
    }

    @GetMapping
    public ResponseEntity<List<BindingResponse>> listBindings() {
        String orgId = TenantContextHolder.getRequiredOrganizationId();
        UUID orgUuid = UUID.fromString(orgId);

        List<BindingResponse> bindings = bindingRepository.findAllByOrganizationId(orgUuid).stream()
                .map(this::toBindingResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(bindings);
    }

    @GetMapping("/{bindingId}")
    public ResponseEntity<?> getBinding(@PathVariable("bindingId") String bindingId) {
        String orgId = TenantContextHolder.getRequiredOrganizationId();
        UUID orgUuid = UUID.fromString(orgId);

        try {
            UUID bindingUuid = UUID.fromString(bindingId);
            return bindingRepository.findById(bindingUuid)
                    .filter(b -> b.getOrganizationId().equals(orgUuid))
                    .map(b -> ResponseEntity.ok((Object) toBindingResponse(b)))
                    .orElseGet(() -> ResponseEntity.status(404).body(Map.of(
                            "error", "BINDING_NOT_FOUND",
                            "message", "Binding not found")));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of(
                    "error", "BINDING_NOT_FOUND",
                    "message", "Binding not found"));
        }
    }

    @PutMapping("/{bindingId}")
    public ResponseEntity<?> updateBinding(
            @PathVariable("bindingId") String bindingId,
            @RequestBody BindingUpdateRequest request) {
        String orgId = TenantContextHolder.getRequiredOrganizationId();
        UUID orgUuid = UUID.fromString(orgId);

        try {
            UUID bindingUuid = UUID.fromString(bindingId);
            return bindingRepository.findById(bindingUuid)
                    .filter(b -> b.getOrganizationId().equals(orgUuid))
                    .map(binding -> {
                        if (request.getAllowedActions() != null) binding.setAllowedActions(request.getAllowedActions());
                        if (request.getActive() != null) binding.setActive(request.getActive());
                        bindingRepository.save(binding);
                        return ResponseEntity.ok((Object) toBindingResponse(binding));
                    })
                    .orElseGet(() -> ResponseEntity.status(404).body(Map.of(
                            "error", "BINDING_NOT_FOUND",
                            "message", "Binding not found")));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of(
                    "error", "BINDING_NOT_FOUND",
                    "message", "Binding not found"));
        }
    }

    @DeleteMapping("/{bindingId}")
    public ResponseEntity<?> deleteBinding(@PathVariable("bindingId") String bindingId) {
        String orgId = TenantContextHolder.getRequiredOrganizationId();
        UUID orgUuid = UUID.fromString(orgId);

        try {
            UUID bindingUuid = UUID.fromString(bindingId);
            return bindingRepository.findById(bindingUuid)
                    .filter(b -> b.getOrganizationId().equals(orgUuid))
                    .map(binding -> {
                        binding.setActive(false);
                        bindingRepository.save(binding);
                        return ResponseEntity.ok((Object) Map.of("message", "Binding deactivated"));
                    })
                    .orElseGet(() -> ResponseEntity.status(404).body(Map.of(
                            "error", "BINDING_NOT_FOUND",
                            "message", "Binding not found")));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of(
                    "error", "BINDING_NOT_FOUND",
                    "message", "Binding not found"));
        }
    }

    private BindingResponse toBindingResponse(AgentResourceBindingEntity binding) {
        return BindingResponse.builder()
                .id(binding.getId().toString())
                .organizationId(binding.getOrganizationId().toString())
                .agentId(binding.getAgentId().toString())
                .resourceId(binding.getResourceId().toString())
                .allowedActions(binding.getAllowedActions())
                .active(binding.isActive())
                .createdAt(binding.getCreatedAt())
                .updatedAt(binding.getUpdatedAt())
                .build();
    }
}
