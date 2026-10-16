-- =============================================================================
-- V8: Distributed state — cluster-wide idempotency + approval execution claims
--
-- Context (Workstream 3): running the control plane as multiple replicas requires
-- coordination state that survives process restarts and is consistent across
-- nodes. PostgreSQL is the authority for idempotency/execution state.
--
-- 1. idempotency_records: durable, tenant-scoped idempotency keys. Claimed with
--    INSERT ... ON CONFLICT DO NOTHING (PK = org + key). State transitions:
--    PROCESSING (in-flight) -> COMPLETED (cached result, replay window) or
--    FAILED (deterministic failure, short window). PROCESSING/terminal rows
--    whose expires_at has passed may be re-claimed (crash takeover / retention).
-- 2. approval_requests += execution claim columns: cross-node exclusive claim &
--    lease for the async approval continuation, with an attempt bound so a
--    crashing node is recovered by another replica and a poisoned ticket
--    eventually becomes terminal.
--
-- Forward-only, additive, non-destructive. V1..V7 are untouched.
-- =============================================================================

CREATE TABLE IF NOT EXISTS idempotency_records (
    organization_id    UUID        NOT NULL,
    idempotency_key    VARCHAR(255) NOT NULL,
    state              VARCHAR(20)  NOT NULL DEFAULT 'PROCESSING',
    cached_response    TEXT,
    external_request_id VARCHAR(128),
    attempt_count      INTEGER      NOT NULL DEFAULT 1,
    expires_at         TIMESTAMPTZ  NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_idempotency_records PRIMARY KEY (organization_id, idempotency_key),
    CONSTRAINT ck_idempotency_state CHECK (state IN ('PROCESSING', 'COMPLETED', 'FAILED'))
);

CREATE INDEX IF NOT EXISTS idx_idempotency_records_expires ON idempotency_records(expires_at);
CREATE INDEX IF NOT EXISTS idx_idempotency_records_state ON idempotency_records(state);

ALTER TABLE approval_requests
    ADD COLUMN IF NOT EXISTS execution_in_progress       BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS execution_claim_started_at  TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS execution_lease_expires_at  TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS execution_attempts          INTEGER     NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS execution_claimed_by        VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_approvals_execution_claim
    ON approval_requests(status, execution_in_progress, execution_lease_expires_at);