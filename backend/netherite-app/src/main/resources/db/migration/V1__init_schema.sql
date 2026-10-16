-- =============================================================================
-- NetheriteAgent Baseline Multi-Tenant Schema (V1)
-- Database: PostgreSQL 16
-- =============================================================================

-- Enable UUID extension
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- 1. Organizations (Tenants)
CREATE TABLE organizations (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(255) NOT NULL,
    slug VARCHAR(100) NOT NULL UNIQUE,
    status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_org_slug ON organizations(slug);

-- 2. Users & RBAC
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255),
    full_name VARCHAR(255),
    role VARCHAR(50) NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_users_org ON users(organization_id);

-- 3. AI Agents
CREATE TABLE agents (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    trust_tier VARCHAR(50) DEFAULT 'TIER_1',
    status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_agents_org ON agents(organization_id);

-- 4. Agent API Keys (Public Key Identifier + Argon2id Secret Hash)
CREATE TABLE agent_api_keys (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    agent_id UUID NOT NULL REFERENCES agents(id) ON DELETE CASCADE,
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    key_identifier VARCHAR(100) NOT NULL UNIQUE,
    secret_hash VARCHAR(255) NOT NULL,
    description VARCHAR(255),
    expires_at TIMESTAMP WITH TIME ZONE,
    revoked_at TIMESTAMP WITH TIME ZONE,
    last_used_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_agent_keys_identifier ON agent_api_keys(key_identifier);
CREATE INDEX idx_agent_keys_agent ON agent_api_keys(agent_id);

-- 5. Enterprise Resources
CREATE TABLE resources (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    connector_type VARCHAR(50) NOT NULL,
    base_url VARCHAR(1024) NOT NULL,
    encrypted_credentials TEXT,
    sensitivity_level VARCHAR(50) NOT NULL DEFAULT 'MEDIUM',
    description TEXT,
    status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_resources_org ON resources(organization_id);

-- 6. Explicit Agent-Resource Bindings
CREATE TABLE agent_resource_bindings (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    agent_id UUID NOT NULL REFERENCES agents(id) ON DELETE CASCADE,
    resource_id UUID NOT NULL REFERENCES resources(id) ON DELETE CASCADE,
    allowed_actions JSONB DEFAULT '[]'::jsonb,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_agent_resource_binding UNIQUE (organization_id, agent_id, resource_id)
);

CREATE INDEX idx_bindings_agent ON agent_resource_bindings(agent_id);

-- 7. Policies
CREATE TABLE policies (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    status VARCHAR(50) NOT NULL DEFAULT 'DRAFT',
    active_version_id UUID,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_policies_org ON policies(organization_id);

-- 8. Policy Versions (Immutable once activated)
CREATE TABLE policy_versions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    policy_id UUID NOT NULL REFERENCES policies(id) ON DELETE CASCADE,
    version_number INT NOT NULL,
    natural_language_prompt TEXT,
    rego_code TEXT NOT NULL,
    parsed_ast JSONB,
    status VARCHAR(50) NOT NULL DEFAULT 'DRAFT',
    created_by UUID REFERENCES users(id),
    approved_by UUID REFERENCES users(id),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    activated_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_policy_version UNIQUE (policy_id, version_number)
);

CREATE INDEX idx_policy_versions_policy ON policy_versions(policy_id);

-- 9. Asynchronous Human Approval Requests
CREATE TABLE approval_requests (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    agent_id UUID NOT NULL REFERENCES agents(id),
    user_id VARCHAR(255),
    action_type VARCHAR(255) NOT NULL,
    target_resource VARCHAR(255) NOT NULL,
    canonical_action JSONB NOT NULL,
    decision_context JSONB NOT NULL,
    triggered_policies JSONB NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    approver_decision VARCHAR(50),
    approver_notes TEXT,
    approved_by_user_id UUID REFERENCES users(id),
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    resolved_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_approvals_org_status ON approval_requests(organization_id, status);
CREATE INDEX idx_approvals_expires ON approval_requests(expires_at);

-- 10. Tamper-Evident SHA-256 Hash-Chained Audit Logs
CREATE TABLE audit_logs (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    request_id VARCHAR(255) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    agent_id UUID REFERENCES agents(id),
    user_id VARCHAR(255),
    action_type VARCHAR(255) NOT NULL,
    target_resource VARCHAR(255) NOT NULL,
    decision VARCHAR(50) NOT NULL,
    policy_evaluations JSONB,
    redacted_parameters JSONB,
    redacted_execution_result JSONB,
    execution_duration_ms BIGINT NOT NULL DEFAULT 0,
    prev_record_hash VARCHAR(64) NOT NULL,
    current_record_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(request_id, event_type)
);

CREATE INDEX idx_audit_org_created ON audit_logs(organization_id, created_at DESC);
CREATE INDEX idx_audit_request ON audit_logs(request_id);
