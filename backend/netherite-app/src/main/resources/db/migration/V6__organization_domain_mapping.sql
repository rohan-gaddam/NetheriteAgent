-- =============================================================================
-- V6: Organization Domain Mapping for SSO Discovery
-- Maps verified email domains to organizations for OIDC/SSO routing.
-- A domain maps to exactly one organization (uniqueness enforced).
-- An organization may own multiple domains.
-- =============================================================================

CREATE TABLE organization_domains (
    id              UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    domain          VARCHAR(255) NOT NULL UNIQUE,
    verified        BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Domain lookup is the primary query path (email domain -> organization)
CREATE INDEX idx_org_domains_domain ON organization_domains(domain);

-- An organization can have multiple domains but not the same one twice
CREATE UNIQUE INDEX idx_org_domains_org_domain ON organization_domains(organization_id, domain);
