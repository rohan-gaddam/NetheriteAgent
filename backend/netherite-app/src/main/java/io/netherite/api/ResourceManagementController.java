package io.netherite.api;

import io.netherite.common.exception.NetheriteException;
import io.netherite.common.model.ConnectorType;
import io.netherite.common.model.SensitivityLevel;
import io.netherite.domain.entity.ResourceEntity;
import io.netherite.domain.repository.ResourceRepository;
import io.netherite.domain.service.ResourceDomainService;
import io.netherite.security.context.TenantContextHolder;
import io.netherite.security.key.ResourceCredentialService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import io.netherite.admin.dto.ResourceRegistrationRequest;
import io.netherite.admin.dto.ResourceResponse;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/v1/resources")
@RequiredArgsConstructor
public class ResourceManagementController {

    private final ResourceDomainService resourceDomainService;
    private final ResourceRepository resourceRepository;
    private final ResourceCredentialService resourceCredentialService;

    @PostMapping
    public ResponseEntity<?> createResource(@RequestBody ResourceRegistrationRequest request) {
        String orgId = TenantContextHolder.getRequiredOrganizationId();
        UUID orgUuid = UUID.fromString(orgId);

        try {
            String encryptedCredentials =
                    resourceCredentialService.encryptForStorage(request.getCredentials());

            ResourceEntity resource = resourceDomainService.createResource(
                    orgUuid,
                    request.getName(),
                    request.getConnectorType(),
                    request.getBaseUrl(),
                    encryptedCredentials,
                    request.getSensitivityLevel(),
                    request.getDescription());

            return ResponseEntity.ok(toResourceResponse(resource));
        } catch (NetheriteException e) {
            return ResponseEntity.status(e.getErrorCode().getHttpStatus()).body(Map.of(
                    "error", e.getErrorCode().name(),
                    "message", e.getErrorCode().getDefaultMessage()));
        }
    }

    @GetMapping
    public ResponseEntity<List<ResourceResponse>> listResources() {
        String orgId = TenantContextHolder.getRequiredOrganizationId();
        UUID orgUuid = UUID.fromString(orgId);

        List<ResourceResponse> resources = resourceDomainService.listResources(orgUuid).stream()
                .map(this::toResourceResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(resources);
    }

    @GetMapping("/{resourceId}")
    public ResponseEntity<?> getResource(@PathVariable("resourceId") String resourceId) {
        String orgId = TenantContextHolder.getRequiredOrganizationId();
        UUID orgUuid = UUID.fromString(orgId);

        try {
            UUID resourceUuid = UUID.fromString(resourceId);
            ResourceEntity resource = resourceDomainService.getResource(orgUuid, resourceUuid);
            return ResponseEntity.ok(toResourceResponse(resource));
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

    @PutMapping("/{resourceId}")
    public ResponseEntity<?> updateResource(
            @PathVariable("resourceId") String resourceId,
            @RequestBody ResourceRegistrationRequest request) {
        String orgId = TenantContextHolder.getRequiredOrganizationId();
        UUID orgUuid = UUID.fromString(orgId);

        try {
            UUID resourceUuid = UUID.fromString(resourceId);
            ResourceEntity resource = resourceDomainService.getResource(orgUuid, resourceUuid);

            if (request.getName() != null) resource.setName(request.getName());
            if (request.getConnectorType() != null) resource.setConnectorType(request.getConnectorType());
            if (request.getBaseUrl() != null) resource.setBaseUrl(request.getBaseUrl());
            if (request.getSensitivityLevel() != null) resource.setSensitivityLevel(request.getSensitivityLevel());
            if (request.getDescription() != null) resource.setDescription(request.getDescription());
            if (request.getCredentials() != null) {
                resource.setEncryptedCredentials(
                        resourceCredentialService.encryptForStorage(request.getCredentials()));
            }
            resourceRepository.save(resource);

            return ResponseEntity.ok(toResourceResponse(resource));
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

    @PostMapping("/{resourceId}/deactivate")
    public ResponseEntity<?> deactivateResource(@PathVariable("resourceId") String resourceId) {
        String orgId = TenantContextHolder.getRequiredOrganizationId();
        UUID orgUuid = UUID.fromString(orgId);

        try {
            UUID resourceUuid = UUID.fromString(resourceId);
            ResourceEntity resource = resourceDomainService.getResource(orgUuid, resourceUuid);
            resource.setStatus("INACTIVE");
            resourceRepository.save(resource);
            return ResponseEntity.ok(toResourceResponse(resource));
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

    @PostMapping("/{resourceId}/activate")
    public ResponseEntity<?> activateResource(@PathVariable("resourceId") String resourceId) {
        String orgId = TenantContextHolder.getRequiredOrganizationId();
        UUID orgUuid = UUID.fromString(orgId);

        try {
            UUID resourceUuid = UUID.fromString(resourceId);
            ResourceEntity resource = resourceDomainService.getResource(orgUuid, resourceUuid);
            resource.setStatus("ACTIVE");
            resourceRepository.save(resource);
            return ResponseEntity.ok(toResourceResponse(resource));
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

    private ResourceResponse toResourceResponse(ResourceEntity resource) {
        return ResourceResponse.builder()
                .id(resource.getId().toString())
                .organizationId(resource.getOrganizationId().toString())
                .name(resource.getName())
                .connectorType(resource.getConnectorType())
                .baseUrl(resource.getBaseUrl())
                .description(resource.getDescription())
                .sensitivityLevel(resource.getSensitivityLevel())
                .status(resource.getStatus())
                .createdAt(resource.getCreatedAt())
                .build();
    }
}
