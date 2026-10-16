package io.netherite.config;

import io.netherite.domain.entity.OrganizationDomainEntity;
import io.netherite.domain.entity.OrganizationEntity;
import io.netherite.domain.entity.OrganizationIdpConfigEntity;
import io.netherite.domain.repository.OrganizationDomainRepository;
import io.netherite.domain.repository.OrganizationIdpConfigRepository;
import io.netherite.domain.repository.OrganizationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Seeds a development organization and IdP configuration for local SSO testing.
 * Active only on the test profile — never runs in production.
 *
 * Creates:
 *   Organization: "NetheriteAgent Development Organization" (slug: netherite-dev)
 *   Domain:       netherite.local  ->  NetheriteAgent Development Organization
 *   IdP config:   local Keycloak (configurable via properties)
 */
@Slf4j
@Component
@Profile("test")
@RequiredArgsConstructor
public class DevelopmentDataSeeder implements CommandLineRunner {

    private final OrganizationRepository organizationRepository;
    private final OrganizationDomainRepository organizationDomainRepository;
    private final OrganizationIdpConfigRepository idpConfigRepository;

    @Value("${netherite.dev.idp.issuer-url:http://localhost:8180/realms/netherite}")
    private String devIssuerUrl;

    @Value("${netherite.dev.idp.client-id:netherite-agent}")
    private String devClientId;

    @Value("${netherite.dev.idp.client-secret:dev-client-secret}")
    private String devClientSecret;

    @Value("${netherite.dev.idp.authorization-endpoint:}")
    private String devAuthorizationEndpoint;

    @Value("${netherite.dev.idp.token-endpoint:}")
    private String devTokenEndpoint;

    @Value("${netherite.dev.idp.jwks-uri:}")
    private String devJwksUri;

    @Value("${netherite.dev.idp.admin-claim-name:realm_access.roles}")
    private String devAdminClaimName;

    @Value("${netherite.dev.idp.admin-claim-value:netherite-admin}")
    private String devAdminClaimValue;

    @Value("${netherite.dev.domain:netherite.local}")
    private String devDomain;

    @Value("${netherite.dev.redirect-uri:http://localhost:8080/v1/auth/oidc/callback}")
    private String devRedirectUri;

    @Override
    @Transactional
    public void run(String... args) {
        try {
            seedDevelopmentOrganization();
        } catch (Exception e) {
            log.warn("Development data seeding failed: {}", e.getMessage());
        }
    }

    private void seedDevelopmentOrganization() {
        // Create development organization
        OrganizationEntity devOrg = organizationRepository.findBySlug("netherite-dev")
                .orElseGet(() -> {
                    log.info("Seeding development organization");
                    return organizationRepository.save(OrganizationEntity.builder()
                            .name("NetheriteAgent Development Organization")
                            .slug("netherite-dev")
                            .status("ACTIVE")
                            .build());
                });

        // Create domain mapping
        if (!organizationDomainRepository.existsByDomain(devDomain)) {
            log.info("Seeding development domain mapping: {} -> {}", devDomain, devOrg.getName());
            organizationDomainRepository.save(OrganizationDomainEntity.builder()
                    .organizationId(devOrg.getId())
                    .domain(devDomain)
                    .verified(true)
                    .build());
        }

        // Create IdP configuration
        Optional<OrganizationIdpConfigEntity> existingConfig =
                idpConfigRepository.findByOrganizationId(devOrg.getId());
        if (existingConfig.isEmpty()) {
            log.info("Seeding development IdP config: issuer={}", devIssuerUrl);
            idpConfigRepository.save(OrganizationIdpConfigEntity.builder()
                    .organizationId(devOrg.getId())
                    .issuerUrl(devIssuerUrl)
                    .clientId(devClientId)
                    .clientSecret(devClientSecret)
                    .authorizationEndpoint(
                            devAuthorizationEndpoint != null && !devAuthorizationEndpoint.isBlank()
                                    ? devAuthorizationEndpoint : null)
                    .tokenEndpoint(
                            devTokenEndpoint != null && !devTokenEndpoint.isBlank()
                                    ? devTokenEndpoint : null)
                    .jwksUri(
                            devJwksUri != null && !devJwksUri.isBlank()
                                    ? devJwksUri : null)
                    .scopes("openid email profile")
                    .claimEmail("email")
                    .claimName("name")
                    .adminClaimName(devAdminClaimName)
                    .adminClaimValue(devAdminClaimValue)
                    .redirectUri(devRedirectUri)
                    .status("ACTIVE")
                    .build());
        }

        log.info("Development organization ready: slug=netherite-dev, domain={}, issuer={}",
                devDomain, devIssuerUrl);
    }
}
