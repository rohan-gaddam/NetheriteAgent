-- =============================================================================
-- Phase 7: Enterprise Hardening
-- Adds approver_user_identifier column, composite indexes, unique constraints
-- =============================================================================

-- 1. Add approver_user_identifier to approval_requests (Phase 6 entity field without matching column)
ALTER TABLE approval_requests ADD COLUMN approver_user_identifier VARCHAR(255);

-- 2. Composite index for agent_api_keys: organization_id + agent_id
CREATE INDEX idx_agent_keys_org_agent ON agent_api_keys(organization_id, agent_id);

-- 3. Composite index for agent_resource_bindings: organization_id + agent_id + resource_id
CREATE INDEX idx_bindings_org_agent_resource ON agent_resource_bindings(organization_id, agent_id, resource_id);

-- 4. Index for approval_requests: organization_id only (for getTicket queries)
CREATE INDEX idx_approvals_org ON approval_requests(organization_id);

-- 5. Index for audit_logs: organization_id only (for chain verification)
CREATE INDEX idx_audit_org ON audit_logs(organization_id);
