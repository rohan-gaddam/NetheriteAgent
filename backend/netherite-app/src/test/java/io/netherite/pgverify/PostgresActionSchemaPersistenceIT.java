package io.netherite.pgverify;

import io.netherite.common.model.ConnectorType;
import io.netherite.common.model.SensitivityLevel;
import io.netherite.domain.entity.ActionParameterSchemaEntity;
import io.netherite.domain.entity.OrganizationEntity;
import io.netherite.domain.entity.ResourceEntity;
import io.netherite.domain.repository.ActionParameterSchemaRepository;
import io.netherite.domain.service.ActionParameterSchemaAdminService;
import io.netherite.domain.service.OrganizationService;
import io.netherite.domain.service.ResourceDomainService;
import io.netherite.ingestion.provider.DbActionParameterSchemaProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LLM normalization Step 2 verification against a REAL PostgreSQL 16 instance under the
 * pgverify profile (Flyway V1..V14 + ddl-auto: validate). Proves the parameter-schema
 * table satisfies the entity contract, that a jsonb definition round-trips, that the
 * promoter's provider serves only the ACTIVE version, and that the version uniqueness
 * guarantee is enforced by PostgreSQL.
 */
@SpringBootTest
@ActiveProfiles("pgverify")
class PostgresActionSchemaPersistenceIT {

    @Autowired private OrganizationService organizationService;
    @Autowired private ResourceDomainService resourceDomainService;
    @Autowired private ActionParameterSchemaAdminService schemaAdminService;
    @Autowired private ActionParameterSchemaRepository schemaRepository;
    @Autowired private DbActionParameterSchemaProvider schemaProvider;

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

    private OrganizationEntity newOrganization(String name) {
        return organizationService.createOrganization(
                name, name.toLowerCase().replaceAll("[^a-z0-9]+", "-") + "-" + UUID.randomUUID());
    }

    private ResourceEntity newResource(OrganizationEntity org) {
        return resourceDomainService.createResource(
                org.getId(), "PAYMENT_API", ConnectorType.HTTP_REST,
                "https://api.example.com", null, SensitivityLevel.MEDIUM, "Payment API");
    }

    @Test
    @DisplayName("G-1. A draft round-trips as jsonb and is served only after activation")
    void draftRoundTripsAndActivates() {
        OrganizationEntity org = newOrganization("Schema PG");
        ResourceEntity resource = newResource(org);

        ActionParameterSchemaEntity draft = schemaAdminService.createDraft(
                org.getId(), resource.getId(), "PAYMENT:REFUND", validDefinition(), null);
        assertNotNull(draft.getId());

        ActionParameterSchemaEntity loaded = schemaRepository
                .findByIdAndOrganizationId(draft.getId(), org.getId())
                .orElseThrow();
        assertEquals("OBJECT", loaded.getSchemaDefinition().get("type"));
        assertEquals(Boolean.FALSE, loaded.getSchemaDefinition().get("additionalProperties"));
        assertEquals(ActionParameterSchemaEntity.STATUS_DRAFT, loaded.getStatus());

        assertTrue(schemaProvider
                .schemaFor(org.getId().toString(), "PAYMENT_API", "PAYMENT:REFUND").isEmpty());

        schemaAdminService.activate(org.getId(), resource.getId(), draft.getId());

        assertEquals("OBJECT", schemaProvider
                .schemaFor(org.getId().toString(), "PAYMENT_API", "PAYMENT:REFUND")
                .orElseThrow().type().name());
    }

    @Test
    @DisplayName("G-2. A schema for one organization is invisible to another")
    void schemaIsTenantIsolated() {
        OrganizationEntity orgA = newOrganization("Schema Tenant A");
        OrganizationEntity orgB = newOrganization("Schema Tenant B");
        ResourceEntity resourceA = newResource(orgA);
        ResourceEntity resourceB = newResource(orgB);

        ActionParameterSchemaEntity draftA = schemaAdminService.createDraft(
                orgA.getId(), resourceA.getId(), "PAYMENT:REFUND", validDefinition(), null);
        schemaAdminService.activate(orgA.getId(), resourceA.getId(), draftA.getId());

        assertTrue(schemaProvider
                .schemaFor(orgA.getId().toString(), "PAYMENT_API", "PAYMENT:REFUND").isPresent());
        assertTrue(schemaProvider
                .schemaFor(orgB.getId().toString(), "PAYMENT_API", "PAYMENT:REFUND").isEmpty());
        assertTrue(schemaRepository.findByIdAndOrganizationId(draftA.getId(), orgB.getId()).isEmpty());
    }

    @Test
    @DisplayName("G-3. The (organization, resource, action, version) uniqueness is enforced by PostgreSQL")
    void versionUniquenessEnforced() {
        OrganizationEntity org = newOrganization("Schema Unique");
        ResourceEntity resource = newResource(org);

        ActionParameterSchemaEntity first = ActionParameterSchemaEntity.builder()
                .organizationId(org.getId())
                .resourceId(resource.getId())
                .actionType("PAYMENT:REFUND")
                .versionNumber(1)
                .schemaDefinition(validDefinition())
                .status(ActionParameterSchemaEntity.STATUS_DRAFT)
                .createdAt(Instant.now())
                .build();
        schemaRepository.saveAndFlush(first);

        ActionParameterSchemaEntity duplicate = ActionParameterSchemaEntity.builder()
                .organizationId(org.getId())
                .resourceId(resource.getId())
                .actionType("PAYMENT:REFUND")
                .versionNumber(1)
                .schemaDefinition(validDefinition())
                .status(ActionParameterSchemaEntity.STATUS_DRAFT)
                .createdAt(Instant.now())
                .build();

        assertThrows(DataIntegrityViolationException.class,
                () -> schemaRepository.saveAndFlush(duplicate));
    }
}
