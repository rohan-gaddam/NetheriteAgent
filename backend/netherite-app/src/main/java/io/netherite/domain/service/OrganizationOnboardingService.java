package io.netherite.domain.service;

import io.netherite.common.exception.NetheriteException;
import io.netherite.common.exception.SystemErrorCode;
import io.netherite.domain.entity.OrganizationDomainEntity;
import io.netherite.domain.entity.OrganizationEntity;
import io.netherite.domain.entity.OrganizationIdpConfigEntity;
import io.netherite.domain.repository.OrganizationDomainRepository;
import io.netherite.domain.repository.OrganizationIdpConfigRepository;
import io.netherite.domain.repository.OrganizationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Control-plane organization onboarding.
 *
 * Organizations, their verified email domains, and their organization-specific
 * Identity Provider configuration are created explicitly by authorized
 * operators through this service — never auto-created from an email address
 * and never discovered from an arbitrary email domain.
 *
 * A domain only participates in SSO routing once it is explicitly verified.
 */
@Service
@RequiredArgsConstructor
public class OrganizationOnboardingService {

    private static final Pattern DOMAIN_PATTERN = Pattern.compile(
            "^(?=.{1,253}$)[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$");

    private final OrganizationService organizationService;
    private final OrganizationRepository organizationRepository;
    private final OrganizationDomainRepository organizationDomainRepository;
    private final OrganizationIdpConfigRepository idpConfigRepository;

    @Transactional
    public OrganizationEntity createOrganization(String name, String slug) {
        return organizationService.createOrganization(name, slug);
    }

    @Transactional
    public OrganizationDomainEntity addDomain(UUID organizationId, String domain, boolean verified) {
        requireActiveOrganization(organizationId);
        String normalizedDomain = domain.trim().toLowerCase();
        if (!DOMAIN_PATTERN.matcher(normalizedDomain).matches()) {
            throw new NetheriteException(SystemErrorCode.ERR_INVALID_PARAMETERS,
                    "Invalid domain format: " + domain);
        }
        if (organizationDomainRepository.existsByDomain(normalizedDomain)) {
            throw new NetheriteException(SystemErrorCode.ERR_INVALID_PARAMETERS,
                    "Domain is already mapped to an organization: " + normalizedDomain);
        }
        return organizationDomainRepository.save(OrganizationDomainEntity.builder()
                .organizationId(organizationId)
                .domain(normalizedDomain)
                .verified(verified)
                .build());
    }

    @Transactional
    public OrganizationDomainEntity setDomainVerified(UUID organizationId, UUID domainId, boolean verified) {
        OrganizationDomainEntity domain = requireDomainOfOrganization(organizationId, domainId);
        domain.setVerified(verified);
        return organizationDomainRepository.save(domain);
    }

    @Transactional
    public Optional<OrganizationDomainEntity> removeDomain(UUID organizationId, UUID domainId) {
        OrganizationDomainEntity domain = requireDomainOfOrganization(organizationId, domainId);
        organizationDomainRepository.delete(domain);
        return Optional.of(domain);
    }

    @Transactional
    public OrganizationIdpConfigEntity upsertIdpConfig(UUID organizationId, IdpConfigInput input) {
        OrganizationEntity org = requireActiveOrganization(organizationId);
        validateIdpInput(input);

        OrganizationIdpConfigEntity existing = idpConfigRepository
                .findByOrganizationId(organizationId)
                .orElse(null);

        if (existing == null) {
            OrganizationIdpConfigEntity config = OrganizationIdpConfigEntity.builder()
                    .organizationId(org.getId())
                    .issuerUrl(input.issuerUrl().trim())
                    .clientId(input.clientId().trim())
                    .clientSecret(input.clientSecret())
                    .discoveryUrl(blankToNull(input.discoveryUrl()))
                    .authorizationEndpoint(blankToNull(input.authorizationEndpoint()))
                    .tokenEndpoint(blankToNull(input.tokenEndpoint()))
                    .userinfoEndpoint(blankToNull(input.userinfoEndpoint()))
                    .jwksUri(blankToNull(input.jwksUri()))
                    .scopes(input.scopes() != null && !input.scopes().isBlank() ? input.scopes().trim() : "openid email profile")
                    .claimEmail(input.claimEmail() != null && !input.claimEmail().isBlank() ? input.claimEmail().trim() : "email")
                    .claimName(input.claimName() != null && !input.claimName().isBlank() ? input.claimName().trim() : "name")
                    .claimRoles(blankToNull(input.claimRoles()))
                    .claimGroups(blankToNull(input.claimGroups()))
                    .adminClaimName(input.adminClaimName().trim())
                    .adminClaimValue(blankToNull(input.adminClaimValue()))
                    .redirectUri(blankToNull(input.redirectUri()))
                    .status(input.status() != null && !input.status().isBlank() ? input.status().trim().toUpperCase() : "ACTIVE")
                    .build();
            return idpConfigRepository.save(config);
        }

        existing.setIssuerUrl(input.issuerUrl().trim());
        existing.setClientId(input.clientId().trim());
        if (input.clientSecret() != null && !input.clientSecret().isBlank()) {
            // Blank secret on update preserves the stored secret.
            existing.setClientSecret(input.clientSecret());
        }
        existing.setDiscoveryUrl(blankToNull(input.discoveryUrl()));
        existing.setAuthorizationEndpoint(blankToNull(input.authorizationEndpoint()));
        existing.setTokenEndpoint(blankToNull(input.tokenEndpoint()));
        existing.setUserinfoEndpoint(blankToNull(input.userinfoEndpoint()));
        existing.setJwksUri(blankToNull(input.jwksUri()));
        existing.setScopes(input.scopes() != null && !input.scopes().isBlank() ? input.scopes().trim() : existing.getScopes());
        existing.setClaimEmail(input.claimEmail() != null && !input.claimEmail().isBlank() ? input.claimEmail().trim() : existing.getClaimEmail());
        existing.setClaimName(input.claimName() != null && !input.claimName().isBlank() ? input.claimName().trim() : existing.getClaimName());
        existing.setClaimRoles(blankToNull(input.claimRoles()));
        existing.setClaimGroups(blankToNull(input.claimGroups()));
        existing.setAdminClaimName(input.adminClaimName().trim());
        existing.setAdminClaimValue(blankToNull(input.adminClaimValue()));
        existing.setRedirectUri(blankToNull(input.redirectUri()));
        if (input.status() != null && !input.status().isBlank()) {
            existing.setStatus(input.status().trim().toUpperCase());
        }
        return idpConfigRepository.save(existing);
    }

    @Transactional
    public Optional<OrganizationIdpConfigEntity> removeIdpConfig(UUID organizationId) {
        Optional<OrganizationIdpConfigEntity> existing = idpConfigRepository.findByOrganizationId(organizationId);
        existing.ifPresent(idpConfigRepository::delete);
        return existing;
    }

    @Transactional(readOnly = true)
    public Optional<OrganizationEntity> getOrganization(UUID organizationId) {
        return organizationRepository.findById(organizationId);
    }

    private void validateIdpInput(IdpConfigInput input) {
        if (input.issuerUrl() == null || input.issuerUrl().isBlank()) {
            throw new NetheriteException(SystemErrorCode.ERR_INVALID_PARAMETERS, "Issuer URL cannot be blank");
        }
        String issuerUrl = input.issuerUrl().trim();
        if (!issuerUrl.startsWith("https://") && !issuerUrl.startsWith("http://")) {
            throw new NetheriteException(SystemErrorCode.ERR_INVALID_PARAMETERS,
                    "Issuer URL must be an absolute http(s) URL");
        }
        if (input.clientId() == null || input.clientId().isBlank()) {
            throw new NetheriteException(SystemErrorCode.ERR_INVALID_PARAMETERS, "Client ID cannot be blank");
        }
        // SSO authorization is fail-closed: the Control Plane Admin claim mapping
        // is REQUIRED at provisioning time so an organization can never be left
        // with an IdP that cannot authorize control plane admins.
        if (input.adminClaimName() == null || input.adminClaimName().isBlank()) {
            throw new NetheriteException(SystemErrorCode.ERR_INVALID_PARAMETERS,
                    "adminClaimName is required — SSO authorization is fail-closed and must be explicitly configured");
        }
    }

    private OrganizationEntity requireActiveOrganization(UUID organizationId) {
        OrganizationEntity org = organizationRepository.findById(organizationId)
                .orElseThrow(() -> new NetheriteException(SystemErrorCode.ERR_RESOURCE_NOT_FOUND,
                        "Organization not found: " + organizationId));
        if (!"ACTIVE".equals(org.getStatus())) {
            throw new NetheriteException(SystemErrorCode.ERR_INVALID_PARAMETERS,
                    "Organization is not active: " + org.getSlug());
        }
        return org;
    }

    private OrganizationDomainEntity requireDomainOfOrganization(UUID organizationId, UUID domainId) {
        return organizationDomainRepository.findById(domainId)
                .filter(d -> d.getOrganizationId().equals(organizationId))
                .orElseThrow(() -> new NetheriteException(SystemErrorCode.ERR_RESOURCE_NOT_FOUND,
                        "Domain mapping not found: " + domainId));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record IdpConfigInput(
            String issuerUrl,
            String clientId,
            String clientSecret,
            String discoveryUrl,
            String authorizationEndpoint,
            String tokenEndpoint,
            String userinfoEndpoint,
            String jwksUri,
            String scopes,
            String claimEmail,
            String claimName,
            String claimRoles,
            String claimGroups,
            String adminClaimName,
            String adminClaimValue,
            String redirectUri,
            String status
    ) {
    }
}