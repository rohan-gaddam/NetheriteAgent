package io.netherite.api;

import io.netherite.api.model.ActionParameterSchemaCreateRequest;
import io.netherite.api.model.ActionParameterSchemaResponse;
import io.netherite.common.exception.NetheriteException;
import io.netherite.common.exception.SystemErrorCode;
import io.netherite.domain.entity.ActionParameterSchemaEntity;
import io.netherite.domain.service.ActionParameterSchemaAdminService;
import io.netherite.domain.service.OrganizationAuthorizationService;
import io.netherite.security.context.TenantContextHolder;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Administration of operator-authored parameter schemas for a registered resource.
 *
 * <p>The path is nested under {@code /v1/resources/...} deliberately: it reuses the
 * existing organization administration surface, so only the organization's designated
 * authorized person may reach it and no new authorization surface or filter rule is
 * introduced. The organization is always taken from the trusted session context, never
 * from the request.</p>
 */
@RestController
@RequestMapping("/v1/resources/{resourceId}/action-schemas")
@RequiredArgsConstructor
public class ActionParameterSchemaController {

    private final ActionParameterSchemaAdminService schemaAdminService;
    private final OrganizationAuthorizationService organizationAuthorizationService;

    @PostMapping
    public ResponseEntity<ActionParameterSchemaResponse> createDraft(
            @PathVariable("resourceId") String resourceId,
            @Valid @RequestBody ActionParameterSchemaCreateRequest request) {
        organizationAuthorizationService.requireAuthorizedPersonForTenant();
        UUID organizationId = organizationAuthorizationService.requireTenantOrganizationId();
        UUID resourceUuid = parseUuid(resourceId);

        ActionParameterSchemaEntity created = schemaAdminService.createDraft(
                organizationId, resourceUuid, request.getActionType(), request.getSchema(), currentUserId());

        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(created));
    }

    @GetMapping
    public ResponseEntity<List<ActionParameterSchemaResponse>> list(
            @PathVariable("resourceId") String resourceId,
            @RequestParam(name = "actionType", required = false) String actionType) {
        organizationAuthorizationService.requireAuthorizedPersonForTenant();
        UUID organizationId = organizationAuthorizationService.requireTenantOrganizationId();
        UUID resourceUuid = parseUuid(resourceId);

        List<ActionParameterSchemaResponse> versions = schemaAdminService
                .list(organizationId, resourceUuid, actionType).stream()
                .map(this::toResponse)
                .toList();

        return ResponseEntity.ok(versions);
    }

    @PostMapping("/{versionId}/activate")
    public ResponseEntity<ActionParameterSchemaResponse> activate(
            @PathVariable("resourceId") String resourceId,
            @PathVariable("versionId") String versionId) {
        organizationAuthorizationService.requireAuthorizedPersonForTenant();
        UUID organizationId = organizationAuthorizationService.requireTenantOrganizationId();
        UUID resourceUuid = parseUuid(resourceId);
        UUID versionUuid = parseUuid(versionId);

        ActionParameterSchemaEntity activated =
                schemaAdminService.activate(organizationId, resourceUuid, versionUuid);

        return ResponseEntity.ok(toResponse(activated));
    }

    private ActionParameterSchemaResponse toResponse(ActionParameterSchemaEntity entity) {
        return ActionParameterSchemaResponse.builder()
                .id(entity.getId().toString())
                .organizationId(entity.getOrganizationId().toString())
                .resourceId(entity.getResourceId().toString())
                .actionType(entity.getActionType())
                .versionNumber(entity.getVersionNumber())
                .schema(entity.getSchemaDefinition())
                .status(entity.getStatus())
                .createdBy(entity.getCreatedBy() != null ? entity.getCreatedBy().toString() : null)
                .createdAt(entity.getCreatedAt())
                .activatedAt(entity.getActivatedAt())
                .build();
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new NetheriteException(SystemErrorCode.ERR_RESOURCE_NOT_FOUND, "Resource not found");
        }
    }

    private static UUID currentUserId() {
        String userId = TenantContextHolder.getRequiredContext().getUserId();
        if (userId == null || userId.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(userId);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
