package io.netherite.domain.service;

import io.netherite.common.exception.NetheriteException;
import io.netherite.common.exception.SystemErrorCode;
import io.netherite.domain.entity.ActionParameterSchemaEntity;
import io.netherite.domain.entity.ResourceEntity;
import io.netherite.domain.repository.ActionParameterSchemaRepository;
import io.netherite.domain.repository.ResourceRepository;
import io.netherite.ingestion.schema.ActionParameterSchemaParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ActionParameterSchemaAdminServiceTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID RESOURCE = UUID.randomUUID();
    private static final UUID OTHER_RESOURCE = UUID.randomUUID();
    private static final UUID CREATOR = UUID.randomUUID();

    private ActionParameterSchemaRepository schemaRepository;
    private ResourceRepository resourceRepository;
    private ActionParameterSchemaAdminService service;

    @BeforeEach
    void setUp() {
        schemaRepository = mock(ActionParameterSchemaRepository.class);
        resourceRepository = mock(ResourceRepository.class);
        service = new ActionParameterSchemaAdminService(
                schemaRepository, resourceRepository, new ActionParameterSchemaParser());
        when(schemaRepository.save(any(ActionParameterSchemaEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static Map<String, Object> validDefinition() {
        Map<String, Object> amount = new LinkedHashMap<>();
        amount.put("type", "NUMBER");
        amount.put("required", true);
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("amount", amount);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "OBJECT");
        root.put("additionalProperties", false);
        root.put("properties", properties);
        return root;
    }

    private static ResourceEntity resource(UUID id) {
        return ResourceEntity.builder().id(id).organizationId(ORG).name("PAYMENT_API").status("ACTIVE").build();
    }

    private void resourceExists() {
        when(resourceRepository.findByIdAndOrganizationId(RESOURCE, ORG))
                .thenReturn(Optional.of(resource(RESOURCE)));
    }

    @Test
    @DisplayName("Creates a DRAFT with normalized action type and version 1 when none exist")
    void createDraftPersistsNormalizedActionWithFirstVersion() {
        resourceExists();
        when(schemaRepository.findAllByOrganizationIdAndResourceIdAndActionTypeOrderByVersionNumberDesc(
                ORG, RESOURCE, "PAYMENT:REFUND")).thenReturn(List.of());

        ActionParameterSchemaEntity draft =
                service.createDraft(ORG, RESOURCE, "payment:refund", validDefinition(), CREATOR);

        assertEquals("PAYMENT:REFUND", draft.getActionType());
        assertEquals(1, draft.getVersionNumber());
        assertEquals(ActionParameterSchemaEntity.STATUS_DRAFT, draft.getStatus());
        assertEquals(CREATOR, draft.getCreatedBy());
        assertEquals(ORG, draft.getOrganizationId());
        assertEquals(RESOURCE, draft.getResourceId());
        assertNotNull(draft.getSchemaDefinition());
        assertNull(draft.getActivatedAt());
    }

    @Test
    @DisplayName("Increments the version number past the highest existing version")
    void createDraftIncrementsVersion() {
        resourceExists();
        when(schemaRepository.findAllByOrganizationIdAndResourceIdAndActionTypeOrderByVersionNumberDesc(
                ORG, RESOURCE, "PAYMENT:REFUND"))
                .thenReturn(List.of(ActionParameterSchemaEntity.builder().versionNumber(3).build()));

        ActionParameterSchemaEntity draft =
                service.createDraft(ORG, RESOURCE, "PAYMENT:REFUND", validDefinition(), CREATOR);

        assertEquals(4, draft.getVersionNumber());
    }

    @Test
    void createDraftRejectsUnknownResource() {
        when(resourceRepository.findByIdAndOrganizationId(RESOURCE, ORG)).thenReturn(Optional.empty());

        NetheriteException ex = assertThrows(NetheriteException.class,
                () -> service.createDraft(ORG, RESOURCE, "PAYMENT:REFUND", validDefinition(), CREATOR));
        assertEquals(SystemErrorCode.ERR_RESOURCE_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void createDraftRejectsInvalidActionType() {
        resourceExists();

        NetheriteException ex = assertThrows(NetheriteException.class,
                () -> service.createDraft(ORG, RESOURCE, "bad action", validDefinition(), CREATOR));
        assertEquals(SystemErrorCode.ERR_INVALID_PARAMETERS, ex.getErrorCode());
    }

    @Test
    void createDraftRejectsInvalidSchemaDefinition() {
        resourceExists();
        Map<String, Object> invalid = new LinkedHashMap<>();
        invalid.put("type", "STRING");

        NetheriteException ex = assertThrows(NetheriteException.class,
                () -> service.createDraft(ORG, RESOURCE, "PAYMENT:REFUND", invalid, CREATOR));
        assertEquals(SystemErrorCode.ERR_INVALID_PARAMETERS, ex.getErrorCode());
    }

    @Test
    @DisplayName("Activation supersedes the current active version")
    void activateSupersedesCurrentActive() {
        UUID versionId = UUID.randomUUID();
        ActionParameterSchemaEntity version = ActionParameterSchemaEntity.builder()
                .id(versionId).organizationId(ORG).resourceId(RESOURCE)
                .actionType("PAYMENT:REFUND").versionNumber(2)
                .schemaDefinition(validDefinition())
                .status(ActionParameterSchemaEntity.STATUS_DRAFT)
                .build();
        ActionParameterSchemaEntity currentActive = ActionParameterSchemaEntity.builder()
                .id(UUID.randomUUID()).organizationId(ORG).resourceId(RESOURCE)
                .actionType("PAYMENT:REFUND").versionNumber(1)
                .status(ActionParameterSchemaEntity.STATUS_ACTIVE)
                .build();

        when(schemaRepository.findByIdAndOrganizationId(versionId, ORG)).thenReturn(Optional.of(version));
        when(schemaRepository.findByOrganizationIdAndResourceIdAndActionTypeAndStatus(
                ORG, RESOURCE, "PAYMENT:REFUND", ActionParameterSchemaEntity.STATUS_ACTIVE))
                .thenReturn(Optional.of(currentActive));

        ActionParameterSchemaEntity activated = service.activate(ORG, RESOURCE, versionId);

        assertEquals(ActionParameterSchemaEntity.STATUS_ACTIVE, activated.getStatus());
        assertNotNull(activated.getActivatedAt());
        assertEquals(ActionParameterSchemaEntity.STATUS_SUPERSEDED, currentActive.getStatus());
    }

    @Test
    void activateIsIdempotentWhenAlreadyActive() {
        UUID versionId = UUID.randomUUID();
        ActionParameterSchemaEntity version = ActionParameterSchemaEntity.builder()
                .id(versionId).organizationId(ORG).resourceId(RESOURCE)
                .actionType("PAYMENT:REFUND").versionNumber(1)
                .schemaDefinition(validDefinition())
                .status(ActionParameterSchemaEntity.STATUS_ACTIVE)
                .build();
        when(schemaRepository.findByIdAndOrganizationId(versionId, ORG)).thenReturn(Optional.of(version));

        ActionParameterSchemaEntity activated = service.activate(ORG, RESOURCE, versionId);

        assertEquals(ActionParameterSchemaEntity.STATUS_ACTIVE, activated.getStatus());
        verify(schemaRepository, never()).save(any(ActionParameterSchemaEntity.class));
    }

    @Test
    void activateRejectsUnknownVersion() {
        UUID versionId = UUID.randomUUID();
        when(schemaRepository.findByIdAndOrganizationId(versionId, ORG)).thenReturn(Optional.empty());

        NetheriteException ex = assertThrows(NetheriteException.class,
                () -> service.activate(ORG, RESOURCE, versionId));
        assertEquals(SystemErrorCode.ERR_RESOURCE_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void activateRejectsVersionBelongingToAnotherResource() {
        UUID versionId = UUID.randomUUID();
        ActionParameterSchemaEntity version = ActionParameterSchemaEntity.builder()
                .id(versionId).organizationId(ORG).resourceId(OTHER_RESOURCE)
                .actionType("PAYMENT:REFUND").versionNumber(1)
                .schemaDefinition(validDefinition())
                .status(ActionParameterSchemaEntity.STATUS_DRAFT)
                .build();
        when(schemaRepository.findByIdAndOrganizationId(versionId, ORG)).thenReturn(Optional.of(version));

        NetheriteException ex = assertThrows(NetheriteException.class,
                () -> service.activate(ORG, RESOURCE, versionId));
        assertEquals(SystemErrorCode.ERR_RESOURCE_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    @DisplayName("Activation refuses a stored definition that no longer validates")
    void activateRejectsCorruptedStoredSchema() {
        UUID versionId = UUID.randomUUID();
        Map<String, Object> corrupted = new LinkedHashMap<>();
        corrupted.put("type", "ARRAY");
        ActionParameterSchemaEntity version = ActionParameterSchemaEntity.builder()
                .id(versionId).organizationId(ORG).resourceId(RESOURCE)
                .actionType("PAYMENT:REFUND").versionNumber(1)
                .schemaDefinition(corrupted)
                .status(ActionParameterSchemaEntity.STATUS_DRAFT)
                .build();
        when(schemaRepository.findByIdAndOrganizationId(versionId, ORG)).thenReturn(Optional.of(version));

        NetheriteException ex = assertThrows(NetheriteException.class,
                () -> service.activate(ORG, RESOURCE, versionId));
        assertEquals(SystemErrorCode.ERR_INVALID_PARAMETERS, ex.getErrorCode());
    }

    @Test
    void listScopesToOrganizationAndResource() {
        when(schemaRepository.findAllByOrganizationIdAndResourceIdOrderByActionTypeAscVersionNumberDesc(
                ORG, RESOURCE)).thenReturn(List.of());

        assertTrue(service.list(ORG, RESOURCE, null).isEmpty());
        verify(schemaRepository)
                .findAllByOrganizationIdAndResourceIdOrderByActionTypeAscVersionNumberDesc(ORG, RESOURCE);
    }
}
