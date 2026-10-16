-- =============================================================================
-- V5: Enterprise OIDC/SSO Authentication
-- Adds OIDC user linking, per-org IdP config, OIDC flow state storage
-- =============================================================================

-- 1. Add OIDC columns to users (nullable — owner uses password_hash, OIDC users use oidc_subject)
ALTER TABLE users ADD COLUMN oidc_subject VARCHAR(255);
ALTER TABLE users ADD COLUMN oidc_issuer VARCHAR(500);
ALTER TABLE users ADD COLUMN idp_provider VARCHAR(100);

-- Index for OIDC user lookup
CREATE INDEX idx_users_oidc ON users(oidc_subject, oidc_issuer) WHERE oidc_subject IS NOT NULL;

-- 2. Per-Organization Identity Provider Configuration
CREATE TABLE organization_idp_config (
    id                      UUID PRIMARY KEY,
    organization_id         UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    issuer_url              VARCHAR(500) NOT NULL,
    client_id               VARCHAR(255) NOT NULL,
    client_secret           VARCHAR(500),        -- encrypted at application level
    discovery_url           VARCHAR(500),        -- OIDC .well-known/openid-configuration URL
    authorization_endpoint  VARCHAR(500),
    token_endpoint          VARCHAR(500),
    userinfo_endpoint       VARCHAR(500),
    jwks_uri                VARCHAR(500),
    scopes                  TEXT DEFAULT 'openid email profile',
    claim_email             VARCHAR(100) DEFAULT 'email',
    claim_name              VARCHAR(100) DEFAULT 'name',
    claim_roles             VARCHAR(100),
    claim_groups            VARCHAR(100),
    admin_claim_name        VARCHAR(100),        -- claim to check for admin authorization
    admin_claim_value       VARCHAR(255),        -- required value for admin authorization
    redirect_uri            VARCHAR(500),        -- computed callback URL
    status                  VARCHAR(50) DEFAULT 'ACTIVE',
    created_at              TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX idx_idp_config_org ON organization_idp_config(organization_id);
CREATE INDEX idx_idp_config_issuer ON organization_idp_config(issuer_url);

-- 3. OIDC Flow State Storage (nonce, state, PKCE verifier — short-lived)
CREATE TABLE oidc_flows (
    id              UUID PRIMARY KEY,
    state           VARCHAR(255) NOT NULL UNIQUE,
    nonce           VARCHAR(255) NOT NULL,
    code_verifier   VARCHAR(255) NOT NULL,
    organization_id UUID,
    idp_config_id   UUID,
    redirect_uri    VARCHAR(500),
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMP NOT NULL
);

CREATE INDEX idx_oidc_flows_state ON oidc_flows(state);
CREATE INDEX idx_oidc_flows_expires ON oidc_flows(expires_at);
