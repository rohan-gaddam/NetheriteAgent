package io.netherite.domain.service;

import io.netherite.common.exception.NetheriteException;
import io.netherite.common.exception.SystemErrorCode;
import io.netherite.domain.entity.ActionParameterSchemaEntity;
import io.netherite.domain.entity.ResourceEntity;
import io.netherite.domain.repository.ActionParameterSchemaRepository;
import io.netherite.domain.repository.ResourceRepository;
import io.netherite.ingestion.promotion.ActionParameterRules;
import io.netherite.ingestion.schema.ActionParameterSchemaException;
import io.netherite.ingestion.schema.ActionParameterSchemaParser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Administration of operator-authored parameter schemas for registered resources.
 *
 * <p>Ownership is enforced by the caller (the controller is behind the existing
 * organization authorization surface); this service enforces <b>tenant isolation</b>:
 * every lookup is scoped by organization, so a schema for one organization can never be
 * created, activated or listed through another.</p>
 *
 * <p>A newly created schema is a {@code DRAFT} and has no effect until activated; at most
 * one version per (organization, resource, action) is {@code ACTIVE} at a time. The
 * definition is validated on write <em>and</em> re-validated on activation.</p>
 */
@Service
@RequiredArgsConstructor
public class ActionParameterSchemaAdminService {

    private final ActionParameterSchemaRepository schemaRepository;
    private final ResourceRepository resourceRepository;
    private final ActionParameterSchemaParser parser;

    @Transactional
    public ActionParameterSchemaEntity createDraft(UUID organizationId,
                                                   UUID resourceId,
                                                   String rawActionType,
                                                   Map<String, Object> definition,
                                                   UUID createdBy) {
        ResourceEntity resource = resourceRepository.findByIdAndOrganizationId(resourceId, organizationId)
                .orElseThrow(() -> new NetheriteException(SystemErrorCode.ERR_RESOURCE_NOT_FOUND,
                        "Resource not found for this organization"));

        String actionType = ActionParameterRules.normalizeActionIdentifier(rawActionType);
        if (actionType == null) {
            throw new NetheriteException(SystemErrorCode.ERR_INVALID_PARAMETERS,
                    "Action type is not a valid action identifier");
        }

        validateDefinition(definition);

        int nextVersion = schemaRepository
                .findAllByOrganizationIdAndResourceIdAndActionTypeOrderByVersionNumberDesc(
                        organizationId, resource.getId(), actionType)
                .stream()
                .findFirst()
                .map(ActionParameterSchemaEntity::getVersionNumber)
                .orElse(0) + 1;

        ActionParameterSchemaEntity draft = ActionParameterSchemaEntity.builder()
                .organizationId(organizationId)
                .resourceId(resource.getId())
                .actionType(actionType)
                .versionNumber(nextVersion)
                .schemaDefinition(definition)
                .status(ActionParameterSchemaEntity.STATUS_DRAFT)
                .createdBy(createdBy)
                .build();

        return schemaRepository.save(draft);
    }

    @Transactional
    public ActionParameterSchemaEntity activate(UUID organizationId, UUID resourceId, UUID versionId) {
        ActionParameterSchemaEntity version = schemaRepository.findByIdAndOrganizationId(versionId, organizationId)
                .filter(entity -> entity.getResourceId().equals(resourceId))
                .orElseThrow(() -> new NetheriteException(SystemErrorCode.ERR_RESOURCE_NOT_FOUND,
                        "Schema version not found for this organization"));

        // Never activate a definition that cannot be trusted, even if it was valid at write time.
        validateDefinition(version.getSchemaDefinition());

        if (ActionParameterSchemaEntity.STATUS_ACTIVE.equals(version.getStatus())) {
            return version;
        }

        schemaRepository.findByOrganizationIdAndResourceIdAndActionTypeAndStatus(
                        organizationId, resourceId, version.getActionType(),
                        ActionParameterSchemaEntity.STATUS_ACTIVE)
                .ifPresent(current -> {
                    current.setStatus(ActionParameterSchemaEntity.STATUS_SUPERSEDED);
                    schemaRepository.save(current);
                });

        version.setStatus(ActionParameterSchemaEntity.STATUS_ACTIVE);
        version.setActivatedAt(Instant.now());
        return schemaRepository.save(version);
    }

    @Transactional(readOnly = true)
    public List<ActionParameterSchemaEntity> list(UUID organizationId, UUID resourceId, String rawActionType) {
        if (rawActionType != null && !rawActionType.isBlank()) {
            String actionType = ActionParameterRules.normalizeActionIdentifier(rawActionType);
            if (actionType == null) {
                throw new NetheriteException(SystemErrorCode.ERR_INVALID_PARAMETERS,
                        "Action type is not a valid action identifier");
            }
            return schemaRepository.findAllByOrganizationIdAndResourceIdAndActionTypeOrderByVersionNumberDesc(
                    organizationId, resourceId, actionType);
        }
        return schemaRepository.findAllByOrganizationIdAndResourceIdOrderByActionTypeAscVersionNumberDesc(
                organizationId, resourceId);
    }

    @Transactional(readOnly = true)
    public Optional<ActionParameterSchemaEntity> findActive(UUID organizationId, UUID resourceId, String actionType) {
        return schemaRepository.findByOrganizationIdAndResourceIdAndActionTypeAndStatus(
                organizationId, resourceId, actionType, ActionParameterSchemaEntity.STATUS_ACTIVE);
    }

    private void validateDefinition(Map<String, Object> definition) {
        try {
            parser.parse(definition);
        } catch (ActionParameterSchemaException e) {
            throw new NetheriteException(SystemErrorCode.ERR_INVALID_PARAMETERS, e.getMessage());
        }
    }
}
