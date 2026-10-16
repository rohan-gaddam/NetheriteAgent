package io.netherite.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netherite.domain.entity.OrganizationDomainEntity;
import io.netherite.domain.entity.OrganizationEntity;
import io.netherite.domain.entity.OrganizationIdpConfigEntity;
import io.netherite.domain.repository.OrganizationDomainRepository;
import io.netherite.domain.repository.OrganizationIdpConfigRepository;
import io.netherite.domain.repository.OrganizationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests for organization discovery via work email domain.
 * Domain discovery is routing only — never authorization.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrganizationDiscoveryTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private OrganizationDomainRepository organizationDomainRepository;
    @Autowired private OrganizationIdpConfigRepository idpConfigRepository;

    private OrganizationEntity acmeOrg;
    private OrganizationDomainEntity acmeDomain;

    @BeforeEach
    void setUp() {
        // Clean up any prior test data
        cleanupDomain("acme.test");
        cleanupDomain("noidp.test");
        cleanupDomain("discovery-unit.test");
        organizationRepository.findBySlug("acme-discovery").ifPresent(o -> {
            idpConfigRepository.findByOrganizationId(o.getId()).ifPresent(idpConfigRepository::delete);
            organizationRepository.delete(o);
        });
        organizationRepository.findBySlug("noidp-discovery").ifPresent(o -> organizationRepository.delete(o));

        // Create Acme org with domain AND IdP config
        acmeOrg = organizationRepository.save(OrganizationEntity.builder()
                .name("Acme Corporation")
                .slug("acme-discovery")
                .status("ACTIVE")
                .build());

        acmeDomain = organizationDomainRepository.save(OrganizationDomainEntity.builder()
                .organizationId(acmeOrg.getId())
                .domain("acme.test")
                .verified(true)
                .build());

        idpConfigRepository.save(OrganizationIdpConfigEntity.builder()
                .organizationId(acmeOrg.getId())
                .issuerUrl("https://acme.test/issuer")
                .clientId("acme-client")
                .authorizationEndpoint("https://acme.test/authorize")
                .tokenEndpoint("https://acme.test/token")
                .jwksUri("https://acme.test/jwks")
                .adminClaimName("roles")
                .adminClaimValue("netherite-admin")
                .status("ACTIVE")
                .build());
    }

    private void cleanupDomain(String domain) {
        organizationDomainRepository.findByDomain(domain).ifPresent(d -> {
            organizationDomainRepository.delete(d);
        });
    }

    // ===== Test 1: Valid work email organization discovery =====

    @Test
    @DisplayName("1. Valid work email with known domain and IdP returns available=true")
    void validEmailDiscoverySucceeds() throws Exception {
        mockMvc.perform(post("/v1/auth/oidc/discover")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", "alice@acme.test"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true));
    }

    @Test
    @DisplayName("1b. Discovery response contains no secrets or internal IDs")
    void discoveryResponseContainsNoSecrets() throws Exception {
        String response = mockMvc.perform(post("/v1/auth/oidc/discover")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", "alice@acme.test"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertFalse(response.contains("clientSecret"), "Client secret must not be exposed");
        assertFalse(response.contains("acme-client"), "Client ID must not be exposed");
        assertFalse(response.contains(acmeOrg.getId().toString()), "Internal org ID must not be exposed");
        assertFalse(response.contains("issuerUrl"), "Issuer URL must not be exposed");
        assertFalse(response.contains("authorizationEndpoint"), "Authorization endpoint must not be exposed");
    }

    // ===== Test 2: Unknown email domain =====

    @Test
    @DisplayName("2. Unknown email domain returns available=false with unknown_organization")
    void unknownDomainReturnsUnknownOrganization() throws Exception {
        mockMvc.perform(post("/v1/auth/oidc/discover")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", "user@unknowncompany.com"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.errorCode").value("unknown_organization"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("couldn't find an organization")));
    }

    // ===== Test 3: Organization with no IdP =====

    @Test
    @DisplayName("3. Organization with no IdP returns available=false with sso_not_configured")
    void orgWithNoIdpReturnsSsoNotConfigured() throws Exception {
        OrganizationEntity noIdpOrg = organizationRepository.save(OrganizationEntity.builder()
                .name("No IdP Corp")
                .slug("noidp-discovery")
                .status("ACTIVE")
                .build());
        OrganizationDomainEntity domain = organizationDomainRepository.save(OrganizationDomainEntity.builder()
                .organizationId(noIdpOrg.getId())
                .domain("noidp.test")
                .verified(true)
                .build());

        mockMvc.perform(post("/v1/auth/oidc/discover")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", "user@noidp.test"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.errorCode").value("sso_not_configured"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("Single sign-on isn't configured")));

        // Cleanup
        organizationDomainRepository.delete(domain);
        organizationRepository.delete(noIdpOrg);
    }

    // ===== Test 4: Organization with IdP (covered by test 1) =====

    // ===== Test 5: OIDC authorization redirect =====

    @Test
    @DisplayName("5. OIDC start with valid email redirects to the IdP authorization endpoint")
    void oidcStartRedirectsToIdp() throws Exception {
        mockMvc.perform(get("/v1/auth/oidc/start").param("email", "alice@acme.test"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location",
                        org.hamcrest.Matchers.startsWith("https://acme.test/authorize")));
    }

    @Test
    @DisplayName("5b. OIDC authorization URL contains required OIDC parameters")
    void oidcAuthorizationUrlContainsRequiredParams() throws Exception {
        String location = mockMvc.perform(get("/v1/auth/oidc/start")
                        .param("email", "alice@acme.test"))
                .andExpect(status().isFound())
                .andReturn().getResponse().getHeader("Location");

        assertNotNull(location);
        assertTrue(location.contains("response_type=code"), "Must have response_type=code");
        assertTrue(location.contains("client_id=acme-client"), "Must have client_id");
        assertTrue(location.contains("redirect_uri="), "Must have redirect_uri");
        assertTrue(location.contains("scope="), "Must have scope");
        assertTrue(location.contains("state="), "Must have state");
        assertTrue(location.contains("nonce="), "Must have nonce");
        assertTrue(location.contains("code_challenge="), "Must have PKCE code_challenge");
        assertTrue(location.contains("code_challenge_method=S256"), "Must use S256 PKCE method");
    }

    // ===== Test 6: State validation =====

    @Test
    @DisplayName("6. OIDC callback with invalid state redirects to login with invalid_state")
    void callbackWithInvalidStateRejected() throws Exception {
        mockMvc.perform(get("/v1/auth/oidc/callback")
                        .param("code", "fake-code")
                        .param("state", "nonexistent-state"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location",
                        org.hamcrest.Matchers.containsString("login?error=invalid_state")));
    }

    // ===== Test 7-9, 11-13: Covered in OidcValidationTest =====

    // ===== Test 17: /v1/auth/me =====

    @Test
    @DisplayName("17. /v1/auth/me without token returns 401")
    void authMeWithoutTokenReturns401() throws Exception {
        mockMvc.perform(get("/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("ERR_AUTHENTICATION_FAILED"));
    }

    // ===== Domain normalization =====

    @Test
    @DisplayName("Email with spaces and mixed case is normalized for discovery")
    void emailNormalizationWorks() throws Exception {
        mockMvc.perform(post("/v1/auth/oidc/discover")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", " Alice@ACME.TEST "))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true));
    }

    @Test
    @DisplayName("Invalid email format returns 400 with invalid_email")
    void invalidEmailReturns400() throws Exception {
        mockMvc.perform(post("/v1/auth/oidc/discover")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", "not-an-email"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("invalid_email"));
    }

    @Test
    @DisplayName("Missing email returns 400")
    void missingEmailReturns400() throws Exception {
        mockMvc.perform(post("/v1/auth/oidc/discover")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("invalid_email"));
    }

    // ===== Domain is routing only, not authorization =====

    @Test
    @DisplayName("Domain discovery does NOT grant access — start with domain creates flow but no session")
    void domainDiscoveryDoesNotCreateSession() throws Exception {
        // The start endpoint creates an OIDC flow and redirects to IdP.
        // No session is created until the full OIDC callback completes.
        String location = mockMvc.perform(get("/v1/auth/oidc/start")
                        .param("email", "alice@acme.test"))
                .andExpect(status().isFound())
                .andReturn().getResponse().getHeader("Location");

        // We redirected to IdP — no session token in the redirect
        assertNotNull(location);
        assertFalse(location.contains("sessionToken"), "No session should be created at start");
        assertFalse(location.contains("X-Netherite"), "No auth header should be set at start");
    }
}
