-- ============================================================================
-- V9: Audit integrity hardening (shared across PostgreSQL and H2 test runs)
--   1. Tenant-scoped audit uniqueness (one tenant can never shadow another's rows)
--   2. Per-organization append-only hash-chain anchors (audit_chain_heads)
--   3. Backfill chain heads from existing audit rows
-- PostgreSQL-only append-only trigger and least-privilege grants live in
-- db/postgresql/V10 (they cannot be expressed portably for the H2 test engine).
-- ============================================================================

-- 1. Replace the ORIGINAL global (request_id, event_type) constraint with a
--    tenant-scoped one. Prior migrations never renamed the auto-generated
--    constraint, so the legacy name is audit_logs_request_id_event_type_key.
ALTER TABLE audit_logs DROP CONSTRAINT IF EXISTS audit_logs_request_id_event_type_key;

-- NOTE: in an existing deployment with junk pre-claim rows this ADD CONSTRAINT
-- fails; the correct remediation is to rebuild while chain data is fresh. There
-- is no auto-remediation because silently dropping audit rows would violate the
-- append-only contract.
ALTER TABLE audit_logs ADD CONSTRAINT uk_audit_request_event
    UNIQUE (organization_id, request_id, event_type);

-- 2. Per-organization chain anchors. Writers serialize on these rows so the
--    previous-hash read is race-free across replicas (PESSIMISTIC_WRITE).
CREATE TABLE audit_chain_heads (
    organization_id UUID PRIMARY KEY,
    last_hash VARCHAR(64) NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- 3. Anchor existing chains at their current tail (idempotent on re-run).
INSERT INTO audit_chain_heads (organization_id, last_hash, updated_at)
SELECT l.organization_id, l.current_record_hash, l.created_at
FROM audit_logs l
INNER JOIN (
    SELECT organization_id, MAX(created_at) AS max_created
    FROM audit_logs
    GROUP BY organization_id
) m
    ON m.organization_id = l.organization_id
    AND m.max_created = l.created_at
ON CONFLICT DO NOTHING;