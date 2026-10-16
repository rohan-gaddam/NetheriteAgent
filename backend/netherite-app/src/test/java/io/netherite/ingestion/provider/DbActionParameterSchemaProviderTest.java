package io.netherite.ingestion.provider;

import io.netherite.domain.entity.ActionParameterSchemaEntity;
import io.netherite.domain.entity.ResourceEntity;
import io.netherite.domain.repository.ActionParameterSchemaRepository;
import io.netherite.domain.repository.ResourceRepository;
import io.netherite.ingestion.model.ActionParameterSchema;
import io.netherite.ingestion.schema.ActionParameterSchemaException;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DbActionParameterSchemaProviderTest {

    private static final UUID ORG = UUID.randomUUID();

    private ResourceRepository resourceRepository;
    private ActionParameterSchemaRepository schemaRepository;
    private DbActionParameterSchemaProvider provider;

    @BeforeEach
    void setUp() {
        resourceRepository = mock(ResourceRepository.class);
        schemaRepository = mock(ActionParameterSchemaRepository.class);
        provider = new DbActionParameterSchemaProvider(
                resourceRepository, schemaRepository, new ActionParameterSchemaParser());
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

    private static ResourceEntity resource(UUID id, String status) {
        return ResourceEntity.builder().id(id).organizationId(ORG).name("PAYMENT_API").status(status).build();
    }

    @Test
    @DisplayName("Serves the active schema of an active resource")
    void servesActiveSchema() {
        UUID resourceId = UUID.randomUUID();
        when(resourceRepository.findAllByOrganizationId(ORG)).thenReturn(List.of(resource(resourceId, "ACTIVE")));
        when(schemaRepository.findByOrganizationIdAndResourceIdAndActionTypeAndStatus(
                ORG, resourceId, "PAYMENT:REFUND", "ACTIVE"))
                .thenReturn(Optional.of(ActionParameterSchemaEntity.builder()
                        .schemaDefinition(validDefinition()).build()));

        Optional<ActionParameterSchema> schema =
                provider.schemaFor(ORG.toString(), "PAYMENT_API", "PAYMENT:REFUND");

        assertTrue(schema.isPresent());
        assertEquals(ActionParameterSchema.ParameterType.OBJECT, schema.get().type());
    }

    @Test
    void emptyWhenOrganizationMalformed() {
        assertTrue(provider.schemaFor("not-a-uuid", "PAYMENT_API", "PAYMENT:REFUND").isEmpty());
        verify(resourceRepository, never()).findAllByOrganizationId(any());
    }

    @Test
    @DisplayName("Empty when the resource name is ambiguous")
    void emptyWhenResourceAmbiguous() {
        when(resourceRepository.findAllByOrganizationId(ORG)).thenReturn(List.of(
                resource(UUID.randomUUID(), "ACTIVE"), resource(UUID.randomUUID(), "ACTIVE")));

        assertTrue(provider.schemaFor(ORG.toString(), "PAYMENT_API", "PAYMENT:REFUND").isEmpty());
    }

    @Test
    @DisplayName("Empty when the resource is not active")
    void emptyWhenResourceInactive() {
        when(resourceRepository.findAllByOrganizationId(ORG))
                .thenReturn(List.of(resource(UUID.randomUUID(), "INACTIVE")));

        assertTrue(provider.schemaFor(ORG.toString(), "PAYMENT_API", "PAYMENT:REFUND").isEmpty());
    }

    @Test
    @DisplayName("Empty when no active schema exists")
    void emptyWhenNoActiveSchema() {
        UUID resourceId = UUID.randomUUID();
        when(resourceRepository.findAllByOrganizationId(ORG)).thenReturn(List.of(resource(resourceId, "ACTIVE")));
        when(schemaRepository.findByOrganizationIdAndResourceIdAndActionTypeAndStatus(
                eq(ORG), eq(resourceId), eq("PAYMENT:REFUND"), eq("ACTIVE")))
                .thenReturn(Optional.empty());

        assertTrue(provider.schemaFor(ORG.toString(), "PAYMENT_API", "PAYMENT:REFUND").isEmpty());
    }

    @Test
    @DisplayName("Throws when the stored definition cannot be trusted (caller fails closed)")
    void throwsWhenStoredSchemaInvalid() {
        UUID resourceId = UUID.randomUUID();
        when(resourceRepository.findAllByOrganizationId(ORG)).thenReturn(List.of(resource(resourceId, "ACTIVE")));
        Map<String, Object> corrupted = new LinkedHashMap<>();
        corrupted.put("type", "OBJECT");
        corrupted.put("properties", new LinkedHashMap<>(Map.of(
                "organizationId", new LinkedHashMap<>(Map.of("type", "STRING")))));
        when(schemaRepository.findByOrganizationIdAndResourceIdAndActionTypeAndStatus(
                org.mockito.ArgumentMatchers.any(), any(), anyString(), anyString()))
                .thenReturn(Optional.of(ActionParameterSchemaEntity.builder()
                        .schemaDefinition(corrupted).build()));

        assertThrows(ActionParameterSchemaException.class,
                () -> provider.schemaFor(ORG.toString(), "PAYMENT_API", "PAYMENT:REFUND"));
    }
}
