package io.netherite.pgverify;

import io.netherite.auth.service.OrganizationDiscoveryService;
import io.netherite.domain.entity.OrganizationDomainEntity;
import io.netherite.domain.entity.OrganizationEntity;
import io.netherite.domain.entity.OrganizationIdpConfigEntity;
import io.netherite.domain.repository.OrganizationDomainRepository;
import io.netherite.domain.repository.OrganizationIdpConfigRepository;
import io.netherite.domain.repository.OrganizationRepository;
import io.netherite.domain.service.OrganizationOnboardingService;
import io.netherite.domain.service.OrganizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

import static io.netherite.auth.service.OrganizationDiscoveryService.DiscoveryResult.Status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ENTERPRISE RUNTIME verification against a REAL PostgreSQL 16 instance under the
 * pgverify profile (Flyway V1..V14 + ddl-auto: validate). Proves that the running
 * default-style runtime — NOT the test profile with its DevelopmentDataSeeder —
 * starts clean with no auto-created org/domain/IdP data, that verified-domain
 * discovery routes to the correct organization's IdP, and that the whole
 * onboarding round trip persists in PostgreSQL.
 */
@SpringBootTest
@ActiveProfiles("pgverify")
class EnterpriseRuntimeIT {

    @Autowired private OrganizationService organizationService;
    @Autowired private OrganizationOnboardingService onboardingService;
    @Autowired private OrganizationDiscoveryService discoveryService;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private OrganizationDomainRepository organizationDomainRepository;
    @Autowired private OrganizationIdpConfigRepository idpConfigRepository;
    @Autowired private DataSource dataSource;

    private OrganizationEntity orgA;
    private OrganizationEntity orgB;

    @BeforeEach
    void setUp() {
        orgA = organizationService.createOrganization(
                "Enterprise A", "ent-a-" + UUID.randomUUID().toString().substring(0, 8));
        orgB = organizationService.createOrganization(
                "Enterprise B", "ent-b-" + UUID.randomUUID().toString().substring(0, 8));
    }

    private String domain(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 8) + ".test";
    }

    @Test
    @DisplayName("F-1. Flyway applied exactly V1..V14 on the runtime database")
    void flywayAppliedAllMigrations() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        List<String> versions = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = TRUE ORDER BY installed_rank",
                String.class);
        assertEquals(List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14"), versions);
        assertEquals(14, versions.size());
    }

    @Test
    @DisplayName("F-2. The default-style runtime seeds nothing (DevelopmentDataSeeder is test-profile only)")
    void noSeederCreatesDomainOrIdpData() {
        // The DevelopmentDataSeeder is restricted to the test profile. In a normal
        // runtime boot none of its artifacts may exist: no netherite-dev org, no
        // netherite.local, no localhost:8180 IdP.
        assertTrue(organizationRepository.findBySlug("netherite-dev").isEmpty(),
                "DevelopmentDataSeeder must not run under the normal runtime profile");
        assertTrue(organizationDomainRepository.findByDomain("netherite.local").isEmpty(),
                "DevelopmentDataSeeder domain mapping must not exist in the normal runtime");
        idpConfigRepository.findAll().forEach(config ->
                assertTrue(!"http://localhost:8180/realms/netherite".equals(config.getIssuerUrl()),
                        "DevelopmentDataSeeder IdP must not exist in the normal runtime"));
    }

    @Test
    @DisplayName("F-3. Onboarding persists in PostgreSQL: domain verify + IdP secret round trip")
    void onboardingRoundTripPersists() {
        String d = domain("ent-a");

        // Unverified domain on org A.
        OrganizationDomainEntity mapping = onboardingService.addDomain(orgA.getId(), d, false);
        assertNotNull(mapping.getId());
        assertTrue(!mapping.isVerified());

        // Not yet routed.
        assertEquals(Status.UNKNOWN_ORGANIZATION, discoveryService.discoverFromEmail("alice@" + d).status());

        // Verify + IdP with secret.
        onboardingService.setDomainVerified(orgA.getId(), mapping.getId(), true);
        OrganizationIdpConfigEntity idp = onboardingService.upsertIdpConfig(orgA.getId(),
                new OrganizationOnboardingService.IdpConfigInput(
                        "https://idp-enterprise-a.example.com", "client-a", "s3cr3t-a",
                        null, "https://idp-enterprise-a.example.com/authorize",
                        "https://idp-enterprise-a.example.com/token", null, null,
                        "openid email profile", "email", "name", null, null,
                        "roles", "netherite-admin", null, "ACTIVE"));
        assertNotNull(idp.getId());

        OrganizationIdpConfigEntity stored = idpConfigRepository.findByOrganizationId(orgA.getId()).orElseThrow();
        assertEquals("s3cr3t-a", stored.getClientSecret());
        assertEquals("roles", stored.getAdminClaimName());

        // Routes only after verification.
        var result = discoveryService.discoverFromEmail("alice@" + d);
        assertEquals(Status.SUCCESS, result.status());
        assertEquals(orgA.getId(), result.organization().getId());
        assertEquals("https://idp-enterprise-a.example.com/authorize", result.idpConfig().getAuthorizationEndpoint());
    }

    @Test
    @DisplayName("F-4. Blank secret on update preserves the stored secret across a save")
    void blankSecretUpdatePreservesStoredSecret() {
        onboardingService.upsertIdpConfig(orgA.getId(),
                new OrganizationOnboardingService.IdpConfigInput(
                        "https://idp-a.example.com", "client-a", "s3cr3t-original",
                        null, "https://idp-a.example.com/authorize", "https://idp-a.example.com/token",
                        null, null, null, null, null, null, null,
                        "roles", "netherite-admin", null, "ACTIVE"));

        onboardingService.upsertIdpConfig(orgA.getId(),
                new OrganizationOnboardingService.IdpConfigInput(
                        "https://idp-a.example.com", "client-a", "",
                        null, "https://idp-a.example.com/authorize", "https://idp-a.example.com/token",
                        null, null, null, null, null, null, null,
                        "roles", "netherite-admin", null, "ACTIVE"));

        OrganizationIdpConfigEntity stored = idpConfigRepository.findByOrganizationId(orgA.getId()).orElseThrow();
        assertEquals("s3cr3t-original", stored.getClientSecret(),
                "Blank client secret on update must preserve the stored secret");
    }

    @Test
    @DisplayName("F-5. Verified-only two-organization routing resolves each org's own IdP")
    void twoOrganizationVerifiedRouting() {
        String dA = domain("two-a");
        String dB = domain("two-b");

        OrganizationDomainEntity mapA = onboardingService.addDomain(orgA.getId(), dA, false);
        OrganizationDomainEntity mapB = onboardingService.addDomain(orgB.getId(), dB, true);
        onboardingService.setDomainVerified(orgA.getId(), mapA.getId(), true);

        onboardingService.upsertIdpConfig(orgA.getId(), idp("https://idp-a.example.com"));
        onboardingService.upsertIdpConfig(orgB.getId(), idp("https://idp-b.example.com"));

        var routedA = discoveryService.discoverFromEmail("alice@" + dA);
        var routedB = discoveryService.discoverFromEmail("bob@" + dB);
        assertEquals(Status.SUCCESS, routedA.status());
        assertEquals(Status.SUCCESS, routedB.status());
        assertEquals(orgA.getId(), routedA.organization().getId());
        assertEquals(orgB.getId(), routedB.organization().getId());
        assertEquals("https://idp-a.example.com/authorize", routedA.idpConfig().getAuthorizationEndpoint());
        assertEquals("https://idp-b.example.com/authorize", routedB.idpConfig().getAuthorizationEndpoint());
        assertNotEquals(routedA.idpConfig().getId(), routedB.idpConfig().getId(),
                "Each organization must resolve its OWN IdP configuration");
    }

    @Test
    @DisplayName("F-6. Re-verifying an unverified domain toggles routing on real PostgreSQL")
    void reverifyTogglesRouting() {
        String d = domain("toggle");
        OrganizationDomainEntity map = onboardingService.addDomain(orgA.getId(), d, false);
        assertTrue(!discoveryService.discoverOrganization(d).isPresent());

        onboardingService.setDomainVerified(orgA.getId(), map.getId(), true);
        assertTrue(discoveryService.discoverOrganization(d).isPresent());

        onboardingService.setDomainVerified(orgA.getId(), map.getId(), false);
        assertTrue(discoveryService.discoverOrganization(d).isEmpty(),
                "Disabling verification must immediately disable routing");
    }

    private OrganizationOnboardingService.IdpConfigInput idp(String issuer) {
        return new OrganizationOnboardingService.IdpConfigInput(
                issuer, "client-" + UUID.randomUUID().toString().substring(0, 6), "s3cr3t",
                null, issuer + "/authorize", issuer + "/token", null, null,
                "openid email profile", "email", "name", null, null,
                "roles", "netherite-admin", null, "ACTIVE");
    }
}