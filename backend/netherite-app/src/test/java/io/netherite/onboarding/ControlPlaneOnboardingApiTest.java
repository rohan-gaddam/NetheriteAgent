package io.netherite.onboarding;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netherite.domain.entity.OrganizationDomainEntity;
import io.netherite.domain.entity.OrganizationEntity;
import io.netherite.domain.entity.OrganizationIdpConfigEntity;
import io.netherite.domain.entity.UserEntity;
import io.netherite.domain.entity.UserSessionEntity;
import io.netherite.domain.repository.OrganizationDomainRepository;
import io.netherite.domain.repository.OrganizationIdpConfigRepository;
import io.netherite.domain.repository.OrganizationRepository;
import io.netherite.domain.repository.UserRepository;
import io.netherite.domain.repository.UserSessionRepository;
import io.netherite.domain.service.AgentDomainService;
import io.netherite.security.config.AuthProperties;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Control-plane onboarding API: explicit organization creation, verified-domain
 * management, and organization-specific IdP provisioning â€” all performed by an
 * authorized human session for the organization's designated person. Owner is
 * the only cross-tenant principal.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ControlPlaneOnboardingApiTest {

    private static final String OWNER_EMAIL = "owner@example.com";
    private static final String OWNER_PASSWORD = "Fakepassword@26";
    private static final String COOKIE_NAME = "neth_sid";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AuthProperties authProperties;
    @Autowired private UserRepository userRepository;
    @Autowired private UserSessionRepository userSessionRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private OrganizationDomainRepository organizationDomainRepository;
    @Autowired private OrganizationIdpConfigRepository idpConfigRepository;
    @Autowired private io.netherite.domain.repository.AgentRepository agentRepository;
    @Autowired private AgentDomainService agentService;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private record Login(String body, Cookie sessionCookie) {
        private String organizationIdFromBody() throws Exception {
            return (String) new ObjectMapper().readValue(body, Map.class).get("organizationId");
        }
    }

    /** Created entities to clean up after each test (order: sessions â†’ users â†’ domains â†’ idp â†’ orgs â†’ agents). */
    private final List<UUID> createdOrganizations = new ArrayList<>();
    private final List<UUID> createdUsers = new ArrayList<>();
    private final List<UUID> createdSessions = new ArrayList<>();
    private final List<UUID> createdAgents = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        UserEntity existingOwner = userRepository.findByEmail(OWNER_EMAIL).orElse(null);
        if (existingOwner != null) {
            userSessionRepository.findByOrganizationId(existingOwner.getOrganizationId())
                    .forEach(userSessionRepository::delete);
            userRepository.delete(existingOwner);
        }
        organizationRepository.findBySlug("netherite-test").ifPresent(organizationRepository::delete);
    }

    @AfterEach
    void tearDown() {
        createdSessions.forEach(id -> userSessionRepository.deleteById(id));
        createdUsers.forEach(id -> userRepository.deleteById(id));
        createdOrganizations.forEach(orgId -> {
            organizationDomainRepository.findByOrganizationId(orgId).forEach(organizationDomainRepository::delete);
            idpConfigRepository.findByOrganizationId(orgId).ifPresent(idpConfigRepository::delete);
            organizationRepository.deleteById(orgId);
        });
        createdAgents.forEach(id -> agentRepository.deleteById(id));
        createdSessions.clear();
        createdUsers.clear();
        createdOrganizations.clear();
        createdAgents.clear();
        userRepository.findByEmail(OWNER_EMAIL).ifPresent(owner -> {
            userSessionRepository.findByOrganizationId(owner.getOrganizationId())
                    .forEach(userSessionRepository::delete);
            userRepository.delete(owner);
        });
        organizationRepository.findBySlug("netherite-test").ifPresent(organizationRepository::delete);
    }

    private Login loginAsOwner() throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", OWNER_EMAIL,
                                "password", OWNER_PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        Cookie sessionCookie = result.getResponse().getCookie(COOKIE_NAME);
        assertNotNull(sessionCookie, "Owner login must issue the HttpOnly session cookie");
        return new Login(result.getResponse().getContentAsString(), sessionCookie);
    }

    private UUID createOrganizationViaApi(Cookie cookie, String slug) throws Exception {
        String body = mockMvc.perform(post("/v1/control/organizations")
                        .cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Acme Electronics",
                                "slug", slug))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.name").value("Acme Electronics"))
                .andReturn().getResponse().getContentAsString();
        UUID orgId = UUID.fromString((String) objectMapper.readValue(body, Map.class).get("id"));
        createdOrganizations.add(orgId);
        return orgId;
    }

    private Cookie sessionCookieForAdmin(UUID orgId, String email) {
        UserEntity admin = userRepository.save(UserEntity.builder()
                .organizationId(orgId)
                .email(email)
                .fullName("Org B Admin")
                .passwordHash(passwordEncoder.encode("irrelevant"))
                .status("ACTIVE")
                .build());
        createdUsers.add(admin.getId());

        // WS6: an organization created through the control plane starts with
        // nobody designated (fail closed). Its first person must be designated
        // explicitly before they can administer it, so this fixture does that
        // for the "org admin manages own org" scenarios.
        organizationRepository.findById(orgId).ifPresent(org -> {
            org.setAuthorizedPersonUserId(admin.getId());
            organizationRepository.save(org);
            organizationRepository.flush();
        });

        String rawOnboardToken = "tok-" + UUID.randomUUID();
        UserSessionEntity session = userSessionRepository.save(UserSessionEntity.builder()
                .userId(admin.getId())
                .organizationId(orgId)
                .sessionToken(io.netherite.auth.session.SessionTokenHasher.hash(rawOnboardToken))
                .expiresAt(Instant.now().plusSeconds(3600))
                .build());
        createdSessions.add(session.getId());
        return new Cookie(COOKIE_NAME, rawOnboardToken);
    }

    private AgentDomainService.AgentRegistrationResult registerAgentOn(UUID orgId) {
        AgentDomainService.AgentRegistrationResult agent = agentService.registerAgent(
                orgId, "ops-agent", "Ops helper", "TIER_1");
        createdAgents.add(agent.agent().getId());
        return agent;
    }

    private void addDomainViaApi(Cookie cookie, UUID orgId, String domain, boolean verified) throws Exception {
        mockMvc.perform(put("/v1/control/organizations/" + orgId + "/domains")
                        .cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("domain", domain))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.domain").value(domain))
                .andExpect(jsonPath("$.verified").value(verified));
    }

    private UUID addUnverifiedDomainViaApi(Cookie cookie, UUID orgId, String domain) throws Exception {
        addDomainViaApi(cookie, orgId, domain, false);
        return organizationDomainRepository.findByDomain(domain).orElseThrow().getId();
    }

    private Map<String, Object> idpPayload(String issuer, boolean withAdminClaim) {
        if (withAdminClaim) {
            return Map.of(
                    "issuerUrl", issuer,
                    "clientId", "client-" + UUID.randomUUID().toString().substring(0, 8),
                    "clientSecret", "s3cr3t-" + UUID.randomUUID(),
                    "authorizationEndpoint", issuer + "/authorize",
                    "tokenEndpoint", issuer + "/token",
                    "jwksUri", issuer + "/jwks",
                    "adminClaimName", "roles",
                    "adminClaimValue", "netherite-admin",
                    "scopes", "openid email profile");
        }
        return Map.of(
                "issuerUrl", issuer,
                "clientId", "client-no-admin",
                "clientSecret", "s3cr3t-no-admin");
    }

    private void expectDiscover(String email, boolean available, String errorCode) throws Exception {
        var matcher = mockMvc.perform(post("/v1/auth/oidc/discover")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(available));
        if (errorCode != null) {
            matcher.andExpect(jsonPath("$.errorCode").value(errorCode));
        }
    }

    // ===== Owner / lifecycle =====

    @Test
    @DisplayName("D-1. Owner creates an organization; no client secret leaks in the response")
    void ownerCreatesOrganization() throws Exception {
        Login owner = loginAsOwner();
        String slug = "acme-" + UUID.randomUUID().toString().substring(0, 8);
        mockMvc.perform(post("/v1/control/organizations")
                        .cookie(owner.sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "Acme Electronics", "slug", slug))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.domains").isArray())
                .andExpect(jsonPath("$.idp").doesNotExist());
    }

    @Test
    @DisplayName("D-2. Duplicate slug is rejected")
    void duplicateSlugRejected() throws Exception {
        Login owner = loginAsOwner();
        UUID orgId = createOrganizationViaApi(owner.sessionCookie, "dup-slug-" + UUID.randomUUID().toString().substring(0, 6));

        mockMvc.perform(post("/v1/control/organizations")
                        .cookie(owner.sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Duplicate",
                                "slug", organizationRepository.findById(orgId).orElseThrow().getSlug()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("ERR_INVALID_PARAMETERS"));
    }

    @Test
    @DisplayName("D-3. Invalid domain format is rejected")
    void invalidDomainRejected() throws Exception {
        Login owner = loginAsOwner();
        UUID orgId = createOrganizationViaApi(owner.sessionCookie, "bad-domain-" + UUID.randomUUID().toString().substring(0, 6));

        mockMvc.perform(put("/v1/control/organizations/" + orgId + "/domains")
                        .cookie(owner.sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("domain", "not_a_domain"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("ERR_INVALID_PARAMETERS"));
    }

    // ===== Domain verification lifecycle =====

    @Test
    @DisplayName("D-4. Unverified domain does not route until explicitly verified")
    void domainVerificationLifecycle() throws Exception {
        Login owner = loginAsOwner();
        UUID orgId = createOrganizationViaApi(owner.sessionCookie, "lifecycle-" + UUID.randomUUID().toString().substring(0, 6));
        String domain = "lifecycle-" + UUID.randomUUID().toString().substring(0, 6) + ".test";
        UUID domainId = addUnverifiedDomainViaApi(owner.sessionCookie, orgId, domain);

        // Not configured yet (no IdP), but crucially the domain is NOT verified.
        expectDiscover("user@" + domain, false, "unknown_organization");

        // Verify the domain.
        mockMvc.perform(post("/v1/control/organizations/" + orgId + "/domains/" + domainId + "/verify")
                        .cookie(owner.sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("verified", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified").value(true));

        expectDiscover("user@" + domain, false, "sso_not_configured");
    }

    // ===== IdP provisioning + fail-closed admin claim =====

    @Test
    @DisplayName("D-5. IdP provisioning requires the admin claim mapping (fail closed)")
    void idpRequiresAdminClaim() throws Exception {
        Login owner = loginAsOwner();
        UUID orgId = createOrganizationViaApi(owner.sessionCookie, "noadmin-" + UUID.randomUUID().toString().substring(0, 6));

        mockMvc.perform(put("/v1/control/organizations/" + orgId + "/idp")
                        .cookie(owner.sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(idpPayload("https://idp.example.com", false))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("ERR_INVALID_PARAMETERS"));

        assertTrue(idpConfigRepository.findByOrganizationId(orgId).isEmpty(),
                "No IdP config may be persisted without the admin claim mapping");
    }

    @Test
    @DisplayName("D-6. IdP with admin claim configures SSO; secret is write-only")
    void idpConfiguredAndSecretWriteOnly() throws Exception {
        Login owner = loginAsOwner();
        UUID orgId = createOrganizationViaApi(owner.sessionCookie, "idpcfg-" + UUID.randomUUID().toString().substring(0, 6));
        String domain = "idpcfg-" + UUID.randomUUID().toString().substring(0, 6) + ".test";
        addUnverifiedDomainViaApi(owner.sessionCookie, orgId, domain);

        Map<String, Object> payload = idpPayload("https://idp.example.com", true);
        String secret = (String) payload.get("clientSecret");

        MvcResult result = mockMvc.perform(put("/v1/control/organizations/" + orgId + "/idp")
                        .cookie(owner.sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientSecretConfigured").value(true))
                .andExpect(jsonPath("$.adminClaimName").value("roles"))
                .andExpect(jsonPath("$.clientSecret").doesNotExist())
                .andReturn();

        String response = result.getResponse().getContentAsString();
        assertFalse(response.contains(secret), "Client secret must never be echoed back");
        assertFalse(response.contains("\"clientSecret\":"), "No clientSecret field in API responses");

        // Secret persisted server-side.
        OrganizationIdpConfigEntity stored = idpConfigRepository.findByOrganizationId(orgId).orElseThrow();
        assertEquals(secret, stored.getClientSecret());

        // Verify the domain so SSO routes.
        UUID domainId = organizationDomainRepository.findByDomain(domain).orElseThrow().getId();
        mockMvc.perform(post("/v1/control/organizations/" + orgId + "/domains/" + domainId + "/verify")
                        .cookie(owner.sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("verified", true))))
                .andExpect(status().isOk());
        expectDiscover("alice@" + domain, true, null);
    }

    @Test
    @DisplayName("D-7. Blank client secret on update preserves the stored secret")
    void blankSecretPreservesStoredSecret() throws Exception {
        Login owner = loginAsOwner();
        UUID orgId = createOrganizationViaApi(owner.sessionCookie, "preserve-" + UUID.randomUUID().toString().substring(0, 6));

        Map<String, Object> payload = idpPayload("https://idp.example.com", true);
        String originalSecret = (String) payload.get("clientSecret");
        mockMvc.perform(put("/v1/control/organizations/" + orgId + "/idp")
                        .cookie(owner.sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk());

        Map<String, Object> update = new java.util.LinkedHashMap<>(payload);
        update.put("clientSecret", "");
        update.put("issuerUrl", "https://idp-example-updated.com");
        mockMvc.perform(put("/v1/control/organizations/" + orgId + "/idp")
                        .cookie(owner.sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientSecretConfigured").value(true));

        OrganizationIdpConfigEntity stored = idpConfigRepository.findByOrganizationId(orgId).orElseThrow();
        assertEquals(originalSecret, stored.getClientSecret(), "Blank secret must preserve the stored secret");
        assertEquals("https://idp-example-updated.com", stored.getIssuerUrl());
    }

    @Test
    @DisplayName("D-8. Owner can list all organizations")
    void ownerListsOrganizations() throws Exception {
        Login owner = loginAsOwner();
        UUID first = createOrganizationViaApi(owner.sessionCookie, "list-a-" + UUID.randomUUID().toString().substring(0, 6));
        UUID second = createOrganizationViaApi(owner.sessionCookie, "list-b-" + UUID.randomUUID().toString().substring(0, 6));

        mockMvc.perform(get("/v1/control/organizations").cookie(owner.sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString(first.toString())))
                .andExpect(MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString(second.toString())));
    }

    // ===== Authorization: agents, cross-tenant, unauthenticated =====

    @Test
    @DisplayName("D-9. Agent API keys cannot use the control plane")
    void agentKeyCannotUseControlPlane() throws Exception {
        Login owner = loginAsOwner();
        UUID orgId = createOrganizationViaApi(owner.sessionCookie, "agent-ctl-" + UUID.randomUUID().toString().substring(0, 6));
        AgentDomainService.AgentRegistrationResult agent = registerAgentOn(orgId);

        mockMvc.perform(put("/v1/control/organizations/" + orgId + "/domains")
                        .header("X-Netherite-Agent-Key", agent.fullApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("domain", "agentctl.test"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ERR_FORBIDDEN"));
    }

    @Test
    @DisplayName("D-10. A non-owner designated person cannot create organizations or list all")
    void nonOwnerAdminCannotCreateOrList() throws Exception {
        Login owner = loginAsOwner();
        UUID ownerOrgId = UUID.fromString(owner.organizationIdFromBody());
        Cookie adminCookie = sessionCookieForAdmin(ownerOrgId, "admin-a-" + UUID.randomUUID().toString().substring(0, 8) + "@b.test");

        mockMvc.perform(post("/v1/control/organizations")
                        .cookie(adminCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Not Allowed Org",
                                "slug", "not-allowed-" + UUID.randomUUID().toString().substring(0, 6)))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));

        mockMvc.perform(get("/v1/control/organizations").cookie(adminCookie))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("D-11. A designated person manages their OWN organization only")
    void orgAdminManagesOwnOrganization() throws Exception {
        Login owner = loginAsOwner();
        UUID ownerOrgId = UUID.fromString(owner.organizationIdFromBody());
        UUID orgB = createOrganizationViaApi(owner.sessionCookie, "orgb-" + UUID.randomUUID().toString().substring(0, 6));
        Cookie adminCookie = sessionCookieForAdmin(orgB, "admin-b-" + UUID.randomUUID().toString().substring(0, 8) + "@b.test");

        // Own organization: allowed.
        addDomainViaApi(adminCookie, orgB, "ownorg-b.test", false);

        // Someone else's organization (owner org): forbidden.
        mockMvc.perform(put("/v1/control/organizations/" + ownerOrgId + "/domains")
                        .cookie(adminCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("domain", "stranger.test"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));

        // Cross-tenant IdP deletion: forbidden.
        mockMvc.perform(delete("/v1/control/organizations/" + ownerOrgId + "/idp")
                        .cookie(adminCookie))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("D-12. The owner bootstrap exception may manage another organization")
    void ownerCanManageAnotherOrganization() throws Exception {
        Login owner = loginAsOwner();
        UUID orgB = createOrganizationViaApi(owner.sessionCookie, "owner-mgmt-" + UUID.randomUUID().toString().substring(0, 6));

        addDomainViaApi(owner.sessionCookie, orgB, "owner-ok.test", false);

        UUID domainId = organizationDomainRepository.findByDomain("owner-ok.test").orElseThrow().getId();
        mockMvc.perform(post("/v1/control/organizations/" + orgB + "/domains/" + domainId + "/verify")
                        .cookie(owner.sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("verified", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified").value(true));
    }

    @Test
    @DisplayName("D-13. Unauthenticated control-plane access is rejected with 401")
    void unauthenticatedControlPlaneRejected() throws Exception {
        mockMvc.perform(get("/v1/control/organizations"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/v1/control/organizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "X", "slug", "x-" + UUID.randomUUID().toString().substring(0, 6)))))
                .andExpect(status().isUnauthorized());
    }

    // ===== Removal =====

    @Test
    @DisplayName("D-14. Removing a verified domain disables routing; removing IdP reverts to sso_not_configured")
    void removalLifecycle() throws Exception {
        Login owner = loginAsOwner();
        UUID orgId = createOrganizationViaApi(owner.sessionCookie, "removal-" + UUID.randomUUID().toString().substring(0, 6));
        String domain = "removal-" + UUID.randomUUID().toString().substring(0, 6) + ".test";
        UUID domainId = addUnverifiedDomainViaApi(owner.sessionCookie, orgId, domain);
        mockMvc.perform(post("/v1/control/organizations/" + orgId + "/domains/" + domainId + "/verify")
                        .cookie(owner.sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("verified", true))))
                .andExpect(status().isOk());
        mockMvc.perform(put("/v1/control/organizations/" + orgId + "/idp")
                        .cookie(owner.sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(idpPayload("https://idp.example.com", true))))
                .andExpect(status().isOk());
        expectDiscover("alice@" + domain, true, null);

        mockMvc.perform(delete("/v1/control/organizations/" + orgId + "/domains/" + domainId)
                        .cookie(owner.sessionCookie))
                .andExpect(status().isNoContent());
        expectDiscover("alice@" + domain, false, "unknown_organization");

        UUID remainingDomainId = addUnverifiedDomainViaApi(owner.sessionCookie, orgId, "replacement-" + UUID.randomUUID().toString().substring(0, 6) + ".test");
        mockMvc.perform(post("/v1/control/organizations/" + orgId + "/domains/" + remainingDomainId + "/verify")
                        .cookie(owner.sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("verified", true))))
                .andExpect(status().isOk());
        expectDiscover("alice@" + organizationDomainRepository.findById(remainingDomainId).orElseThrow().getDomain(), true, null);

        mockMvc.perform(delete("/v1/control/organizations/" + orgId + "/idp")
                        .cookie(owner.sessionCookie))
                .andExpect(status().isNoContent());
        expectDiscover("alice@" + organizationDomainRepository.findById(remainingDomainId).orElseThrow().getDomain(), false, "sso_not_configured");
    }
}