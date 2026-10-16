package io.netherite.securitysuite;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netherite.common.model.ConnectorType;
import io.netherite.common.model.SensitivityLevel;
import io.netherite.domain.entity.OrganizationEntity;
import io.netherite.domain.entity.ResourceEntity;
import io.netherite.domain.repository.OrganizationRepository;
import io.netherite.domain.repository.UserRepository;
import io.netherite.domain.repository.UserSessionRepository;
import io.netherite.domain.service.OrganizationService;
import io.netherite.domain.service.ResourceDomainService;
import io.netherite.ingestion.provider.DbActionParameterSchemaProvider;
import io.netherite.testsupport.DesignatedPersonFixture;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security + behavior suite for registered action parameter-schema administration.
 *
 * <p>Proves the Step-2 surface is governed by the existing single-designated-person
 * model (no new authorization surface was introduced), that tenant isolation holds
 * across organizations, and that a freshly created schema has no effect until it is
 * activated.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ActionSchemaAdminSecurityTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private OrganizationService organizationService;
    @Autowired private ResourceDomainService resourceDomainService;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private UserSessionRepository userSessionRepository;
    @Autowired private DbActionParameterSchemaProvider schemaProvider;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private OrganizationEntity organization;
    private ResourceEntity resource;
    private Cookie designatedCookie;
    private Cookie otherMemberCookie;

    @BeforeEach
    void setUp() {
        DesignatedPersonFixture fixture =
                new DesignatedPersonFixture(organizationRepository, userRepository, userSessionRepository);
        organization = organizationService.createOrganization(
                "Schema Corp", "sch-" + UUID.randomUUID().toString().substring(0, 8));

        var designated = fixture.newMember(organization, "designated");
        var other = fixture.newMember(organization, "other");
        fixture.designate(organization, designated);
        designatedCookie = fixture.sessionFor(designated, "designated");
        otherMemberCookie = fixture.sessionFor(other, "other");

        resource = resourceDomainService.createResource(
                organization.getId(), "PAYMENT_API", ConnectorType.HTTP_REST,
                "https://api.example.com", null, SensitivityLevel.MEDIUM, "Payment API");
    }

    private String body(String actionType, Map<String, Object> schema) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("actionType", actionType);
        payload.put("schema", schema);
        return objectMapper.writeValueAsString(payload);
    }

    private static Map<String, Object> validSchema() {
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

    private String schemasPath() {
        return "/v1/resources/" + resource.getId() + "/action-schemas";
    }

    @Test
    @DisplayName("AS-1. The designated person creates a draft that is inert until activated")
    void designatedCreatesDraftThenActivates() throws Exception {
        assertTrue(schemaProvider
                .schemaFor(organization.getId().toString(), "PAYMENT_API", "PAYMENT:REFUND").isEmpty());

        MvcResult created = mockMvc.perform(post(schemasPath())
                        .cookie(designatedCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("PAYMENT:REFUND", validSchema())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.versionNumber").value(1))
                .andReturn();

        String versionId = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("id").asText();

        // A draft is not served: unactivated configuration must never become a permissive default.
        assertTrue(schemaProvider
                .schemaFor(organization.getId().toString(), "PAYMENT_API", "PAYMENT:REFUND").isEmpty());

        mockMvc.perform(post(schemasPath() + "/" + versionId + "/activate").cookie(designatedCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        assertTrue(schemaProvider
                .schemaFor(organization.getId().toString(), "PAYMENT_API", "PAYMENT:REFUND").isPresent());

        mockMvc.perform(get(schemasPath()).cookie(designatedCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("ACTIVE"));
    }

    @Test
    @DisplayName("AS-2. A non-designated member is denied on every schema operation")
    void nonDesignatedMemberIsDenied() throws Exception {
        mockMvc.perform(post(schemasPath())
                        .cookie(otherMemberCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("PAYMENT:REFUND", validSchema())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ERR_FORBIDDEN"));

        mockMvc.perform(get(schemasPath()).cookie(otherMemberCookie))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ERR_FORBIDDEN"));
    }

    @Test
    @DisplayName("AS-3. Anonymous callers cannot reach the schema surface")
    void anonymousIsUnauthorized() throws Exception {
        mockMvc.perform(post(schemasPath())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("PAYMENT:REFUND", validSchema())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("AS-4. An invalid schema definition is rejected")
    void rejectsInvalidSchemaDefinition() throws Exception {
        Map<String, Object> invalid = new LinkedHashMap<>();
        invalid.put("type", "STRING");

        mockMvc.perform(post(schemasPath())
                        .cookie(designatedCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("PAYMENT:REFUND", invalid)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("AS-5. An invalid action type is rejected")
    void rejectsInvalidActionType() throws Exception {
        mockMvc.perform(post(schemasPath())
                        .cookie(designatedCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("bad action", validSchema())))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("AS-6. A schema cannot be created against another organization's resource")
    void crossOrganizationIsolation() throws Exception {
        DesignatedPersonFixture fixtureB =
                new DesignatedPersonFixture(organizationRepository, userRepository, userSessionRepository);
        OrganizationEntity orgB = organizationService.createOrganization(
                "Schema Corp B", "schb-" + UUID.randomUUID().toString().substring(0, 8));
        var designatedB = fixtureB.newMember(orgB, "designated-b");
        fixtureB.designate(orgB, designatedB);
        Cookie designatedBCookie = fixtureB.sessionFor(designatedB, "designated-b");

        // Org B's authorized person addresses Org A's resource id.
        mockMvc.perform(post(schemasPath())
                        .cookie(designatedBCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("PAYMENT:REFUND", validSchema())))
                .andExpect(status().isNotFound());
    }
}
