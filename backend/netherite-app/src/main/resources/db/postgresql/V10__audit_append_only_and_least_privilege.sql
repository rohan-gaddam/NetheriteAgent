-- ============================================================================
-- V10 (PostgreSQL only): append-only enforcement + least-privilege app role.
-- Kept out of db/migration because H2 (test engine) cannot evaluate SQL-function
-- triggers or publish a netherite_app role. H2 relies on @Immutable at the ORM
-- layer; PostgreSQL additionally hard-rejects UPDATE/DELETE at the database so a
-- compromised application or careless operator cannot rewrite history through any
-- connection (the trigger fires even for privileged roles).
-- ============================================================================

-- 1. Append-only trigger on audit_logs.
CREATE OR REPLACE FUNCTION netherite_guard_audit_append_only()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'audit_logs is append-only; UPDATE and DELETE are forbidden (attempted on %)',
        TG_TABLE_NAME;
END;
$$;

DROP TRIGGER IF EXISTS trg_audit_append_only ON audit_logs;
CREATE TRIGGER trg_audit_append_only
    BEFORE UPDATE OR DELETE ON audit_logs
    FOR EACH ROW
    EXECUTE FUNCTION netherite_guard_audit_append_only();

-- 2. Least-privilege grants for the netherite_app runtime role (created by
--    infrastructure/docker/postgres/init-db.sh). Guarded so hand-rolled
--    environments that haven't created the role still migrate cleanly.
--    Business tables get the full CRUD the application legitimately performs on
--    them (sessions, approvals, agents, organizations, ...); audit_logs and
--    audit_chain_heads remain SELECT/INSERT-only — the append-only trigger below
--    additionally hard-rejects UPDATE/DELETE for every role, including owners.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'netherite_app') THEN
        GRANT USAGE ON SCHEMA public TO netherite_app;
        GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO netherite_app;
        GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO netherite_app;
        REVOKE UPDATE, DELETE ON audit_logs FROM netherite_app;
        REVOKE UPDATE, DELETE ON audit_chain_heads FROM netherite_app;
        EXECUTE 'GRANT CONNECT ON DATABASE ' || quote_ident(current_database()) || ' TO netherite_app';
        ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO netherite_app;
        ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO netherite_app;
    END IF;
END;
$$;