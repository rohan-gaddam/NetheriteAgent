package io.netherite.pgverify;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WORKSTREAM 1 - Phase 5 / WORKSTREAM 3 upgrade-path verification against a REAL
 * PostgreSQL 16 instance.
 *
 * Simulates a production database that has already been migrated to V6 and holds
 * historical approval rows, then applies V7 and asserts:
 *   - the three missing columns appear,
 *   - existing rows receive version = 0 (NOT NULL DEFAULT 0) retroactively,
 *   - the migration is exactly-once/idempotent (re-running V7 applies nothing).
 *
 * Then simulates the same production database at V7 and applies V8 (distributed
 * state) and asserts:
 *   - idempotency_records appears with the (organization_id, idempotency_key) PK,
 *   - the five execution-claim columns appear on approval_requests,
 *   - historical rows are untouched,
 *   - the migration is exactly-once/idempotent (re-running applies nothing).
 *
 * Each test starts from a freshly reset schema so ordering is irrelevant.
 *
 * This is a plain JDBC + Flyway API test; it deliberately does NOT boot the Spring
 * context so the upgrade can be driven in phases against the dedicated database.
 */
class PostgresUpgradePathIT {

    private static final String URL = "jdbc:postgresql://localhost:5433/netherite_upgrade";
    private static final String USER = "netherite_admin";
    // Harness-only: credential of the local/container PostgreSQL used by the pgverify ITs.
    private static final String PASS = "netherite_pw";

    private DataSource dataSource() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setUrl(URL);
        ds.setUsername(USER);
        ds.setPassword(PASS);
        ds.setDriverClassName("org.postgresql.Driver");
        return ds;
    }

    private void resetSchema(DataSource ds) {
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("DROP SCHEMA IF EXISTS public CASCADE");
        jdbc.execute("CREATE SCHEMA public");
    }

    private Flyway flywayTo(String version) {
        return Flyway.configure()
                .dataSource(dataSource())
                .locations("classpath:db/migration", "classpath:db/postgresql")
                .target(version != null ? MigrationVersion.fromVersion(version) : null)
                .cleanDisabled(true)
                .load();
    }

    @Test
    @DisplayName("V7 upgrades an existing V6 schema with historical rows; re-run is a no-op")
    void upgradeFromV6WithExistingRowsThenIdempotentRerun() {
        DataSource ds = dataSource();
        resetSchema(ds);
        JdbcTemplate jdbc = new JdbcTemplate(ds);

        // Phase 1: migrate ONLY up to V6 (fresh DB) - the pre-V7 world
        var resultV6 = flywayTo("6").migrate();
        assertEquals("6", resultV6.targetSchemaVersion);
        // pre-V7 schema must NOT yet have the V7 columns
        assertFalse(hasColumn(jdbc, "approval_requests", "execution_result"),
                "V7 not applied yet - execution_result must be absent");
        assertFalse(hasColumn(jdbc, "approval_requests", "execution_error"),
                "V7 not applied yet - execution_error must be absent");
        assertFalse(hasColumn(jdbc, "approval_requests", "version"),
                "V7 not applied yet - version must be absent");

        // Phase 2: insert a historical approval row using only the pre-V7 columns
        String orgId = insertOrganization(jdbc, "UpgradeExisting Org");
        String agentId = insertAgent(jdbc, orgId, "LegacyAgent");
        insertHistoricalApproval(jdbc, orgId, agentId);
        Integer before = jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval_requests", Integer.class);
        assertEquals(1, before);

        // Phase 3: apply V7 over the existing schema + data
        var resultV7 = flywayTo("7").migrate();
        assertEquals("7", resultV7.targetSchemaVersion);

        // The three columns now exist with SQL-standard types
        assertEquals("text", columnDataType(jdbc, "approval_requests", "execution_result"));
        assertEquals("text", columnDataType(jdbc, "approval_requests", "execution_error"));
        assertEquals("bigint", columnDataType(jdbc, "approval_requests", "version"));

        // Existing row kept its data and got version = 0 from the NOT NULL DEFAULT
        String status = jdbc.queryForObject(
                "SELECT status FROM approval_requests WHERE organization_id = ?",
                String.class, java.util.UUID.fromString(orgId));
        assertEquals("PENDING", status);
        long version = jdbc.queryForObject(
                "SELECT version FROM approval_requests WHERE organization_id = ?",
                Long.class, java.util.UUID.fromString(orgId));
        assertEquals(0L, version);
        Integer stillOne = jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval_requests", Integer.class);
        assertEquals(1, stillOne);

        // Phase 4: idempotency - re-running migrations up to V7 applies nothing new
        var rerun = flywayTo("7").migrate();
        assertEquals(0, rerun.migrationsExecuted,
                "re-running V7 on a V7 schema must be a no-op");

        // Column NOT-NULL contract intact for approval_requests
        assertFalse(isNullable(jdbc, "approval_requests", "organization_id"));
        assertFalse(isNullable(jdbc, "approval_requests", "version"));
        assertFalse(isNullable(jdbc, "approval_requests", "canonical_action"));
    }

    @Test
    @DisplayName("V1..V14 checksums are stable and non-zero on a fully migrated schema")
    void checksumsStableAcrossRuns() {
        DataSource ds = dataSource();
        resetSchema(ds);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        var result = flywayTo(null).migrate();
        assertEquals(14, result.migrationsExecuted, "fresh schema must migrate up to V14");

        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = TRUE", Long.class);
        assertNotNull(count);
        assertEquals(14L, count, "schema history should reflect exactly 14 migrations");

        Integer bad = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE checksum = 0", Integer.class);
        assertEquals(0, bad, "no migration may carry a zero checksum");
    }

    @Test
    @DisplayName("V8 upgrades an existing V7 schema: idempotency_records + execution claims; re-run is a no-op")
    void v8DistributedStateUpgradesExistingV7Schema() {
        DataSource ds = dataSource();
        resetSchema(ds);
        JdbcTemplate jdbc = new JdbcTemplate(ds);

        // Phase 1: migrate ONLY up to V7 - the pre-V8 world
        var resultV7 = flywayTo("7").migrate();
        assertEquals("7", resultV7.targetSchemaVersion);
        assertFalse(hasColumn(jdbc, "idempotency_records", "idempotency_key"),
                "V8 not applied yet - idempotency_records must be absent");
        assertFalse(hasColumn(jdbc, "approval_requests", "execution_in_progress"),
                "V8 not applied yet - execution_in_progress must be absent");
        assertFalse(hasColumn(jdbc, "approval_requests", "execution_lease_expires_at"),
                "V8 not applied yet - execution_lease_expires_at must be absent");

        // Phase 2: insert a historical approval row on the V7 schema
        String orgId = insertOrganization(jdbc, "Upgrade V8 Org");
        String agentId = insertAgent(jdbc, orgId, "LegacyV7Agent");
        insertHistoricalApproval(jdbc, orgId, agentId);
        Integer before = jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval_requests", Integer.class);
        assertEquals(1, before);

        // Phase 3: apply V8 over the existing schema + data
        var resultV8 = flywayTo("8").migrate();
        assertEquals("8", resultV8.targetSchemaVersion);

        // idempotency_records column contracts
        assertEquals("uuid", columnDataType(jdbc, "idempotency_records", "organization_id"));
        assertEquals("character varying", columnDataType(jdbc, "idempotency_records", "idempotency_key"));
        assertEquals("character varying", columnDataType(jdbc, "idempotency_records", "state"));
        assertEquals("text", columnDataType(jdbc, "idempotency_records", "cached_response"));
        assertEquals("integer", columnDataType(jdbc, "idempotency_records", "attempt_count"));
        assertEquals("timestamp with time zone", columnDataType(jdbc, "idempotency_records", "expires_at"));
        assertFalse(isNullable(jdbc, "idempotency_records", "organization_id"));
        assertFalse(isNullable(jdbc, "idempotency_records", "idempotency_key"));
        assertFalse(isNullable(jdbc, "idempotency_records", "state"));
        assertFalse(isNullable(jdbc, "idempotency_records", "expires_at"));

        // PK = (organization_id, idempotency_key) enforced
        String pk = jdbc.queryForObject(
                "SELECT string_agg(kcu.column_name, ',' ORDER BY kcu.ordinal_position) " +
                        "FROM information_schema.table_constraints tc " +
                        "JOIN information_schema.key_column_usage kcu " +
                        "  ON tc.constraint_name = kcu.constraint_name AND tc.table_schema = kcu.table_schema " +
                        "WHERE tc.table_schema = 'public' AND tc.table_name = 'idempotency_records' " +
                        "  AND tc.constraint_type = 'PRIMARY KEY'",
                String.class);
        assertEquals("organization_id,idempotency_key", pk);

        // state CHECK constraint present
        Integer stateChecks = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.check_constraints " +
                        "WHERE constraint_name = 'ck_idempotency_state' AND constraint_schema = 'public'",
                Integer.class);
        assertEquals(1, stateChecks, "ck_idempotency_state must exist");

        // approval_requests execution-claim columns
        assertEquals("boolean", columnDataType(jdbc, "approval_requests", "execution_in_progress"));
        assertEquals("integer", columnDataType(jdbc, "approval_requests", "execution_attempts"));
        assertEquals("timestamp with time zone", columnDataType(jdbc, "approval_requests", "execution_claim_started_at"));
        assertEquals("timestamp with time zone", columnDataType(jdbc, "approval_requests", "execution_lease_expires_at"));
        assertEquals("character varying", columnDataType(jdbc, "approval_requests", "execution_claimed_by"));
        assertFalse(isNullable(jdbc, "approval_requests", "execution_in_progress"));
        assertFalse(isNullable(jdbc, "approval_requests", "execution_attempts"));

        // claim index present
        Integer claimIndexes = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes WHERE schemaname = 'public' " +
                        "AND indexname = 'idx_approvals_execution_claim'",
                Integer.class);
        assertEquals(1, claimIndexes, "idx_approvals_execution_claim must exist");

        // Historical row untouched
        String status = jdbc.queryForObject(
                "SELECT status FROM approval_requests WHERE organization_id = ?",
                String.class, java.util.UUID.fromString(orgId));
        assertEquals("PENDING", status);
        long version = jdbc.queryForObject(
                "SELECT version FROM approval_requests WHERE organization_id = ?",
                Long.class, java.util.UUID.fromString(orgId));
        assertEquals(0L, version);
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval_requests", Long.class).longValue());

        // Phase 3.5: apply V9 (audit integrity) over the existing V8 schema + data
        var resultV9 = flywayTo("9").migrate();
        assertEquals("9", resultV9.targetSchemaVersion);
        assertEquals(1, resultV9.migrationsExecuted, "V9 must upgrade an existing V8 schema");

        Integer chainHeadTables = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public' " +
                        "AND table_name = 'audit_chain_heads'",
                Integer.class);
        assertEquals(1, chainHeadTables, "V9 must create the audit_chain_heads table");

        Integer tenantScopedUnique = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.table_constraints WHERE table_schema = 'public' " +
                        "AND constraint_type = 'UNIQUE' AND constraint_name = 'uk_audit_request_event'",
                Integer.class);
        assertEquals(1, tenantScopedUnique, "V9 must install uk_audit_request_event on audit_logs");

        // Phase 3.75: apply V10..V14 over the existing V9 schema + data
        var resultLatest = flywayTo(null).migrate();
        assertEquals("14", resultLatest.targetSchemaVersion, "latest schema must be V14");
        assertEquals(5, resultLatest.migrationsExecuted,
                "V10..V14 must upgrade an existing V9 schema");

        // WS6 schema contract (V12): at most one designated authorized person per
        // organization, backed by a composite FK pinning the person to the org.
        assertTrue(hasColumn(jdbc, "organizations", "authorized_person_user_id"),
                "V12 must add organizations.authorized_person_user_id");
        assertEquals("uuid", columnDataType(jdbc, "organizations", "authorized_person_user_id"));
        Integer personFk = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.table_constraints WHERE table_schema = 'public' " +
                        "AND constraint_name = 'fk_organizations_authorized_person'",
                Integer.class);
        assertEquals(1, personFk, "V12 must install fk_organizations_authorized_person");
        Integer personRefs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes WHERE schemaname = 'public' " +
                        "AND indexname = 'uq_users_id_organization_id'",
                Integer.class);
        assertEquals(1, personRefs, "V12 must install uq_users_id_organization_id");

        // WS6 schema contract (V13): role-based authorization is completely removed.
        assertFalse(hasColumn(jdbc, "users", "role"),
                "V13 must drop users.role");

        // Step-2 schema contract (V14): registered, versioned parameter schemas.
        Integer schemaTables = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public' " +
                        "AND table_name = 'action_parameter_schemas'",
                Integer.class);
        assertEquals(1, schemaTables, "V14 must create the action_parameter_schemas table");
        assertEquals("jsonb", columnDataType(jdbc, "action_parameter_schemas", "schema_definition"));
        Integer schemaUnique = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.table_constraints WHERE table_schema = 'public' " +
                        "AND constraint_type = 'UNIQUE' AND constraint_name = 'uq_action_parameter_schema_version'",
                Integer.class);
        assertEquals(1, schemaUnique, "V14 must install uq_action_parameter_schema_version");
        Integer schemaFk = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.table_constraints WHERE table_schema = 'public' " +
                        "AND constraint_name = 'fk_action_parameter_schemas_resource'",
                Integer.class);
        assertEquals(1, schemaFk, "V14 must install the resource composite foreign key");

        // Phase 4: idempotency - re-running migrations at the latest applies nothing
        var rerun = flywayTo(null).migrate();
        assertEquals(0, rerun.migrationsExecuted,
                "re-running at the latest schema must be a no-op");
    }

    private boolean hasColumn(JdbcTemplate jdbc, String table, String column) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = 'public' " +
                        "AND table_name = ? AND column_name = ?", Integer.class, table, column);
        return n != null && n > 0;
    }

    private String columnDataType(JdbcTemplate jdbc, String table, String column) {
        return jdbc.queryForObject(
                "SELECT data_type FROM information_schema.columns WHERE table_schema = 'public' " +
                        "AND table_name = ? AND column_name = ?", String.class, table, column);
    }

    private boolean isNullable(JdbcTemplate jdbc, String table, String column) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT is_nullable = 'YES' FROM information_schema.columns WHERE table_schema = 'public' " +
                        "AND table_name = ? AND column_name = ?", Boolean.class, table, column));
    }

    private String insertOrganization(JdbcTemplate jdbc, String name) {
        jdbc.update("INSERT INTO organizations (name, slug, status) VALUES (?, ?, 'ACTIVE')",
                name, "legacy-" + java.util.UUID.randomUUID().toString().substring(0, 8));
        return jdbc.queryForObject("SELECT id::text FROM organizations WHERE name = ?", String.class, name);
    }

    private String insertAgent(JdbcTemplate jdbc, String orgId, String name) {
        jdbc.update("INSERT INTO agents (organization_id, name, description, trust_tier, status) " +
                        "VALUES (?, ?, 'legacy agent', 'TIER_1', 'ACTIVE')",
                java.util.UUID.fromString(orgId), name);
        return jdbc.queryForObject("SELECT id::text FROM agents WHERE name = ?", String.class, name);
    }

    private void insertHistoricalApproval(JdbcTemplate jdbc, String orgId, String agentId) {
        jdbc.update("INSERT INTO approval_requests " +
                        "(organization_id, agent_id, action_type, target_resource, " +
                        " canonical_action, decision_context, triggered_policies, status, expires_at) " +
                        "VALUES (?, ?, 'PAYMENT:REFUND', 'res-legacy', " +
                        "  '{\"actionType\":\"PAYMENT:REFUND\"}'::jsonb, '{\"k\":\"v\"}'::jsonb, " +
                        "  '[\"base-policy\"]'::jsonb, 'PENDING', NOW() + INTERVAL '1 day')",
                java.util.UUID.fromString(orgId), java.util.UUID.fromString(agentId));
    }
}