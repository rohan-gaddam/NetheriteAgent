package io.netherite.domain;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netherite.common.context.TenantContext;
import io.netherite.common.model.CanonicalAction;
import io.netherite.domain.entity.OrganizationEntity;
import io.netherite.domain.entity.ResourceEntity;
import io.netherite.domain.repository.ResourceRepository;
import io.netherite.domain.service.OrganizationService;
import io.netherite.routing.model.ResourceRoute;
import io.netherite.routing.spi.ResourceRoutingService;
import io.netherite.security.context.TenantContextHolder;
import io.netherite.security.key.CredentialEncryptionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 5G: resource credentials must be encrypted before persistence, redacted from API
 * responses, and carried as ciphertext until the authorized execution path resolves them.
 */
@SpringBootTest(properties = "netherite.security.credential-encryption-key="
        + ResourceCredentialPersistenceTest.VALID_KEY_BASE64)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@Transactional
class ResourceCredentialPersistenceTest {

    static final String VALID_KEY_BASE64 = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

    private static final String RESOURCE_NAME = "ERPNEXT-TEST";
    private static final String PLAINTEXT_SECRET = "Token test-api-key:test-api-secret";

    @Autowired private MockMvc mockMvc;
    @Autowired private OrganizationService organizationService;
    @Autowired private ResourceRepository resourceRepository;
    @Autowired private CredentialEncryptionService credentialEncryptionService;
    @Autowired private ResourceRoutingService resourceRoutingService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private OrganizationEntity organization;

    @BeforeEach
    void setUp() {
        organization = organizationService.createOrganization(
                "Credential Test Corp", "cred-" + UUID.randomUUID().toString().substring(0, 8));
        TenantContextHolder.setContext(TenantContext.builder()
                .organizationId(organization.getId().toString())
                .principalType("USER")
                .build());
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("Create encrypts credentials before persistence and never returns them")
    void createEncryptsAndRedacts() throws Exception {
        mockMvc.perform(post("/v1/resources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequestJson("Create Corp Test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.credentials").doesNotExist())
                .andExpect(jsonPath("$.encryptedCredentials").doesNotExist())
                .andExpect(content().string(not(containsString("test-api-key"))))
                .andExpect(content().string(not(containsString("test-api-secret"))));

        ResourceEntity saved = resourceRepository
                .findByNameAndOrganizationId(RESOURCE_NAME, organization.getId())
                .orElseThrow();

        String stored = saved.getEncryptedCredentials();
        assertNotNull(stored);
        assertNotEquals(PLAINTEXT_SECRET, stored);
        assertFalse(stored.contains("test-api-key"));
        assertFalse(stored.contains("test-api-secret"));

        Map<String, String> decrypted = decrypt(stored);
        assertEquals(PLAINTEXT_SECRET, decrypted.get("Authorization"));

        mockMvc.perform(get("/v1/resources/" + saved.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.credentials").doesNotExist())
                .andExpect(jsonPath("$.encryptedCredentials").doesNotExist())
                .andExpect(content().string(not(containsString("test-api-secret"))));
    }

    @Test
    @DisplayName("Update replaces encrypted credentials; omitting credentials preserves them")
    void updateReEncryptsOnlyWhenSupplied() throws Exception {
        mockMvc.perform(post("/v1/resources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequestJson("Create Corp Test")))
                .andExpect(status().isOk());

        ResourceEntity created = resourceRepository
                .findByNameAndOrganizationId(RESOURCE_NAME, organization.getId())
                .orElseThrow();
        String originalCiphertext = created.getEncryptedCredentials();

        mockMvc.perform(put("/v1/resources/" + created.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", RESOURCE_NAME,
                                "credentials", Map.of("Authorization", "Token rotated-key:rotated-secret")))))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("rotated-secret"))));

        String rotatedCiphertext = resourceRepository.findById(created.getId())
                .orElseThrow().getEncryptedCredentials();
        assertNotEquals(originalCiphertext, rotatedCiphertext);
        assertEquals("Token rotated-key:rotated-secret",
                decrypt(rotatedCiphertext).get("Authorization"));

        mockMvc.perform(put("/v1/resources/" + created.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", RESOURCE_NAME,
                                "description", "metadata-only update"))))
                .andExpect(status().isOk());

        String afterMetadataUpdate = resourceRepository.findById(created.getId())
                .orElseThrow().getEncryptedCredentials();
        assertEquals(rotatedCiphertext, afterMetadataUpdate);
    }

    @Test
    @DisplayName("Route resolution carries ciphertext, not plaintext, until execution")
    void routeResolutionDefersDecryption() throws Exception {
        mockMvc.perform(post("/v1/resources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequestJson("Create Corp Test")))
                .andExpect(status().isOk());

        ResourceEntity saved = resourceRepository
                .findByNameAndOrganizationId(RESOURCE_NAME, organization.getId())
                .orElseThrow();

        CanonicalAction action = CanonicalAction.builder()
                .actionId(UUID.randomUUID().toString())
                .organizationId(organization.getId().toString())
                .agentId(UUID.randomUUID().toString())
                .actionType("CREATE QUOTATION")
                .targetResource(RESOURCE_NAME)
                .parameters(Map.of("party", "Disposable Test Customer"))
                .timestamp(Instant.now())
                .build();

        Optional<ResourceRoute> route = resourceRoutingService.resolveRoute(action);

        assertTrue(route.isPresent());
        assertEquals(saved.getEncryptedCredentials(), route.get().getCredentialReference());
        assertFalse(route.get().getCredentialReference().contains("test-api-secret"));
    }

    @Test
    @DisplayName("Invalid credential payload is rejected and nothing is persisted")
    void invalidCredentialPayloadRejected() throws Exception {
        mockMvc.perform(post("/v1/resources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", RESOURCE_NAME,
                                "connectorType", "ERPNEXT",
                                "baseUrl", "https://erpnext.example.com",
                                "credentials", Map.of("Authorization", "Token key\r\nInjected: true")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("ERR_INVALID_PARAMETERS"))
                .andExpect(content().string(not(containsString("Injected: true"))));

        assertTrue(resourceRepository
                .findByNameAndOrganizationId(RESOURCE_NAME, organization.getId())
                .isEmpty());
    }

    private String createRequestJson(String description) throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("name", RESOURCE_NAME);
        request.put("connectorType", "ERPNEXT");
        request.put("baseUrl", "https://erpnext.example.com");
        request.put("description", description);
        request.put("sensitivityLevel", "HIGH");
        request.put("credentials", Map.of("Authorization", PLAINTEXT_SECRET));
        return objectMapper.writeValueAsString(request);
    }

    private Map<String, String> decrypt(String ciphertext) throws Exception {
        return objectMapper.readValue(credentialEncryptionService.decrypt(ciphertext),
                new TypeReference<Map<String, String>>() { });
    }
}
