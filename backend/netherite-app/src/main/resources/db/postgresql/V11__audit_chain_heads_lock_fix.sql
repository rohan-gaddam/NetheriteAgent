-- ============================================================================
-- V11 (PostgreSQL only): restore UPDATE on audit_chain_heads to netherite_app.
-- V10's least-privilege lockdown revoked UPDATE/DELETE from BOTH audit tables,
-- but the audit integrity chain legitimately advances the per-organization head
-- (JpaAuditService) behind a PESSIMISTIC_WRITE lock that PostgreSQL only grants
-- to a row's UPDATE-capable role. With UPDATE revoked, SELECT ... FOR NO KEY
-- UPDATE is rejected with "permission denied for table audit_chain_heads" and
-- NO audit event can be appended. audit_logs remains append-only: UPDATE/DELETE
-- stay revoked there AND the netherite_guard_audit_append_only trigger hard-
-- rejects both for every role. audit_chain_heads is bookkeeping written only by
-- the integrity chain; restoring UPDATE does not weaken the append-only audit
-- guarantee.
-- ============================================================================

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'netherite_app') THEN
        GRANT SELECT, INSERT, UPDATE ON audit_chain_heads TO netherite_app;
    END IF;
END;
$$;