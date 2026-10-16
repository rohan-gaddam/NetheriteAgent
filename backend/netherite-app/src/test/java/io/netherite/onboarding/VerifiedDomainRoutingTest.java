package io.netherite.onboarding;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netherite.auth.service.OrganizationDiscoveryService;
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
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Only VERIFIED email-domain mappings may participate in SSO routing.
 * An unverified mapping — even with an IdP configured underneath — must fail
 * closed and never route a user to an organization's Identity Provider.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VerifiedDomainRoutingTest {

    private static final String DOMAIN = "verified-routing.test";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private OrganizationDomainRepository organizationDomainRepository;
    @Autowired private OrganizationIdpConfigRepository idpConfigRepository;
    @Autowired private OrganizationDiscoveryService discoveryService;

    private OrganizationEntity org;
    private UUID domainId;

    @BeforeEach
    void setUp() {
        organizationDomainRepository.findByDomain(DOMAIN).ifPresent(organizationDomainRepository::delete);
        organizationRepository.findBySlug("verified-routing").ifPresent(organizationRepository::delete);

        org = organizationRepository.save(OrganizationEntity.builder()
                .name("Verified Routing Corp")
                .slug("verified-routing")
                .status("ACTIVE")
                .build());
    }

    private OrganizationDomainEntity addDomain(boolean verified) {
        OrganizationDomainEntity domain = organizationDomainRepository.save(OrganizationDomainEntity.builder()
                .organizationId(org.getId())
                .domain(DOMAIN)
                .verified(verified)
                .build());
        domainId = domain.getId();
        return domain;
    }

    private OrganizationIdpConfigEntity addIdp() {
        return idpConfigRepository.save(OrganizationIdpConfigEntity.builder()
                .organizationId(org.getId())
                .issuerUrl("https://" + DOMAIN + "/issuer")
                .clientId("routing-client")
                .authorizationEndpoint("https://" + DOMAIN + "/authorize")
                .tokenEndpoint("https://" + DOMAIN + "/token")
                .jwksUri("https://" + DOMAIN + "/jwks")
                .adminClaimName("roles")
                .adminClaimValue("netherite-admin")
                .status("ACTIVE")
                .build());
    }

    private void resetVerified(boolean verified) {
        OrganizationDomainEntity domain = organizationDomainRepository.findById(domainId).orElseThrow();
        domain.setVerified(verified);
        organizationDomainRepository.save(domain);
    }

    private void expectDiscoveryOutcome(String email, boolean available, String errorCode) throws Exception {
        var req = mockMvc.perform(post("/v1/auth/oidc/discover")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(available));
        if (errorCode != null) {
            req.andExpect(jsonPath("$.errorCode").value(errorCode));
        }
    }

    @Test
    @DisplayName("C-1. An unverified domain mapping never routes via discovery")
    void unverifiedDomainDoesNotRoute() throws Exception {
        addDomain(false);
        addIdp();

        // IdP is configured, but the domain is NOT verified -> must fail closed.
        expectDiscoveryOutcome("alice@" + DOMAIN, false, "unknown_organization");
    }

    @Test
    @DisplayName("C-2. Explicitly verifying the domain enables SSO routing")
    void verifyingDomainEnablesRouting() throws Exception {
        addDomain(false);
        addIdp();

        resetVerified(true);

        expectDiscoveryOutcome("alice@" + DOMAIN, true, null);
    }

    @Test
    @DisplayName("C-3. Reverting verification immediately disables routing (fail closed)")
    void revertingVerificationDisablesRouting() throws Exception {
        addDomain(true);
        addIdp();

        resetVerified(false);

        expectDiscoveryOutcome("alice@" + DOMAIN, false, "unknown_organization");
    }

    @Test
    @DisplayName("C-4. Discovery service resolves only verified domains at the service level")
    void serviceLevelVerifiedOnly() {
        addDomain(false);
        addIdp();

        Optional<UUID> before = discoveryService.discoverOrganization(DOMAIN)
                .map(OrganizationEntity::getId);
        assertTrue(before.isEmpty(), "Unverified domain must not resolve");

        resetVerified(true);
        Optional<UUID> after = discoveryService.discoverOrganization(DOMAIN)
                .map(OrganizationEntity::getId);
        assertTrue(after.isPresent(), "Verified domain must resolve");
        assertEquals(org.getId(), after.get());
    }

    @Test
    @DisplayName("C-5. A verified domain without an IdP reports sso_not_configured")
    void verifiedDomainWithoutIdpReportsNotConfigured() throws Exception {
        addDomain(true);
        expectDiscoveryOutcome("alice@" + DOMAIN, false, "sso_not_configured");
    }
}