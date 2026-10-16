package io.netherite.auth.service;

import io.netherite.domain.entity.OrganizationDomainEntity;
import io.netherite.domain.entity.OrganizationEntity;
import io.netherite.domain.entity.OrganizationIdpConfigEntity;
import io.netherite.domain.repository.OrganizationDomainRepository;
import io.netherite.domain.repository.OrganizationIdpConfigRepository;
import io.netherite.domain.repository.OrganizationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Resolves a work email domain to an organization and its IdP configuration.
 *
 * SECURITY: Domain discovery is a ROUTING mechanism only.
 * The domain is NEVER proof of membership or Control Plane Admin authorization.
 * Authorization is enforced after OIDC callback via claim validation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrganizationDiscoveryService {

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[A-Za-z0-9+._-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    private final OrganizationDomainRepository organizationDomainRepository;
    private final OrganizationRepository organizationRepository;
    private final OrganizationIdpConfigRepository idpConfigRepository;

    /**
     * Normalize a submitted work email safely.
     * Trims, lowercases, and validates format.
     *
     * @return normalized email, or empty if invalid
     */
    public Optional<String> normalizeEmail(String rawEmail) {
        if (rawEmail == null || rawEmail.isBlank()) {
            return Optional.empty();
        }
        String normalized = rawEmail.trim().toLowerCase();
        if (!EMAIL_PATTERN.matcher(normalized).matches()) {
            return Optional.empty();
        }
        return Optional.of(normalized);
    }

    /**
     * Extract the domain part from a normalized email.
     * Only the part after @ is used — never the local part.
     */
    public String extractDomain(String normalizedEmail) {
        int at = normalizedEmail.lastIndexOf('@');
        if (at < 0 || at == normalizedEmail.length() - 1) {
            return "";
        }
        return normalizedEmail.substring(at + 1).toLowerCase();
    }

    /**
     * Discover the organization for an email domain.
     * Only VERIFIED domain mappings participate in SSO routing — an unverified
     * mapping must never route users to an organization's IdP.
     * Returns empty if the domain is not mapped to any organization.
     */
    public Optional<OrganizationEntity> discoverOrganization(String domain) {
        if (domain == null || domain.isBlank()) {
            return Optional.empty();
        }
        Optional<OrganizationDomainEntity> domainEntry =
                organizationDomainRepository.findByDomainAndVerifiedTrue(domain.toLowerCase());
        if (domainEntry.isEmpty()) {
            log.debug("No VERIFIED organization domain mapping for: {}", domain);
            return Optional.empty();
        }
        return organizationRepository.findById(domainEntry.get().getOrganizationId());
    }

    /**
     * Find the active IdP configuration for an organization.
     */
    public Optional<OrganizationIdpConfigEntity> discoverIdpConfig(OrganizationEntity organization) {
        if (organization == null) {
            return Optional.empty();
        }
        return idpConfigRepository.findByOrganizationIdAndStatus(organization.getId(), "ACTIVE");
    }

    /**
     * Full discovery: email -> domain -> organization -> active IdP config.
     * Returns a structured result so callers can distinguish
     * unknown-organization from sso-not-configured.
     */
    public DiscoveryResult discoverFromEmail(String rawEmail) {
        Optional<String> normalized = normalizeEmail(rawEmail);
        if (normalized.isEmpty()) {
            return DiscoveryResult.invalidEmail();
        }

        String domain = extractDomain(normalized.get());
        Optional<OrganizationEntity> org = discoverOrganization(domain);
        if (org.isEmpty()) {
            return DiscoveryResult.unknownOrganization();
        }

        Optional<OrganizationIdpConfigEntity> idp = discoverIdpConfig(org.get());
        if (idp.isEmpty()) {
            return DiscoveryResult.ssoNotConfigured();
        }

        return DiscoveryResult.success(org.get(), idp.get());
    }

    /**
     * Structured discovery result with distinct failure reasons.
     */
    public record DiscoveryResult(
            Status status,
            OrganizationEntity organization,
            OrganizationIdpConfigEntity idpConfig
    ) {
        public enum Status {
            SUCCESS,
            INVALID_EMAIL,
            UNKNOWN_ORGANIZATION,
            SSO_NOT_CONFIGURED
        }

        public static DiscoveryResult success(OrganizationEntity org, OrganizationIdpConfigEntity idp) {
            return new DiscoveryResult(Status.SUCCESS, org, idp);
        }

        public static DiscoveryResult invalidEmail() {
            return new DiscoveryResult(Status.INVALID_EMAIL, null, null);
        }

        public static DiscoveryResult unknownOrganization() {
            return new DiscoveryResult(Status.UNKNOWN_ORGANIZATION, null, null);
        }

        public static DiscoveryResult ssoNotConfigured() {
            return new DiscoveryResult(Status.SSO_NOT_CONFIGURED, null, null);
        }

        public boolean isSuccess() {
            return status == Status.SUCCESS;
        }
    }
}
