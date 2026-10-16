-- =============================================================================
-- V7: Alignment of approval_requests schema with the ApprovalRequestEntity JPA contract
--
-- Context: ApprovalRequestEntity has long mapped three columns that no migration
-- created. Under `ddl-auto: validate` against real PostgreSQL this causes schema
-- validation to fail at application startup:
--     * execution_result  TEXT   (approval execution outcome, e.g. final result JSON)
--     * execution_error   TEXT   (approval execution error detail)
--     * version           BIGINT NOT NULL DEFAULT 0  (@Version optimistic locking)
--
-- Forward-only, additive, non-destructive. No existing column is altered or
-- dropped and no existing row is rewritten. Existing rows receive version = 0
-- (the same value ApprovalRequestEntity's @Builder.Default initialises for new
-- entities), preserving backward compatibility.
-- =============================================================================

ALTER TABLE approval_requests
    ADD COLUMN IF NOT EXISTS execution_result TEXT,
    ADD COLUMN IF NOT EXISTS execution_error  TEXT,
    ADD COLUMN IF NOT EXISTS version          BIGINT NOT NULL DEFAULT 0;