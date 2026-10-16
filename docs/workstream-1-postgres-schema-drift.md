# WORKSTREAM 1 — Final Report: PostgreSQL Schema Drift Fix for approval_requests

**Status: WORKSTREAM 1 COMPLETE AND VERIFIED**

## Problem

`ApprovalRequestEntity` maps three columns (`execution_result TEXT`, `execution_error TEXT`, `version BIGINT NOT NULL DEFAULT 0` for JPA `@Version`) that no Flyway migration created. The production config (`ddl-auto: validate` + Flyway enabled in `netherite-app/src/main/resources/application.yml`) therefore failed schema validation at startup against real PostgreSQL. The H2 test config (`ddl-auto: create-drop`, Flyway disabled) masked the drift, hiding the defect.

A second, same-class drift was also present: the entity model mapped six JSONB columns with `columnDefinition = "TEXT"` (+ custom converters). Since Hibernate validation compares the entity-declared JDBC type code against the real DB column type, this would also fail validation on PostgreSQL and was part of the same schema contract misalignment.

## Implemented

1. **New migration** `backend/netherite-app/src/main/resources/db/migration/V7__approval_request_schema_alignment.sql`
   - `ALTER TABLE approval_requests ADD COLUMN IF NOT EXISTS execution_result TEXT, ADD COLUMN IF NOT EXISTS execution_error TEXT, ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;`
   - Forward-only, additive, idempotent (`IF NOT EXISTS`), non-destructive. Existing rows are not rewritten; they receive `version = 0` from the DEFAULT, matching `ApprovalRequestEntity`'s `@Builder.Default version = 0L`.

2. **Entity-side JSONB alignment** (required because `ddl-auto: validate` validates the whole mapped model, not just `approval_requests`):
   - `ApprovalRequestEntity`: `canonical_action`, `decision_context`, `triggered_policies` → `@JdbcTypeCode(SqlTypes.JSON)`; Gson/Jackson converters removed.
   - `AuditLogEntity`: `policy_evaluations`, `redacted_parameters`, `redacted_execution_result` → natively typed JSON.
   - `AgentResourceBindingEntity`: `allowed_actions` → `@JdbcTypeCode(SqlTypes.JSON)`.
   - `PolicyVersionEntity.parsed_ast` left unchanged: `String` + `columnDefinition = "jsonb"` matches the DB type name, so validation passes and nothing writes it.

3. **Real-PostgreSQL integration tests** (verify the workstream acceptance criteria on the actual DB):
   - `PostgresApprovalPersistenceIT` (profile `pgverify`, `application-pgverify.yml` in test resources): boots the full context with Flyway V1–V7 + `ddl-auto: validate`; asserts flyway history has exactly V1–V7 with non-zero checksums; asserts the 20 expected columns and their exact canonical PostgreSQL types (`jsonb` for the three JSON columns, `text`/`bigint` for the new ones); verifies JSONB structured round-trip at the repository level; verifies create → resolve sets `execution_result`, `version` increments + `@Version` optimistic locking (stale update rejected), and `execution_error` persistence.
   - `PostgresUpgradePathIT`: end-to-end upgrade path — migrate to V6, insert a legacy approval row using only the pre-V7 columns, apply V7 over the existing schema+data, assert the new columns appear with correct types, the legacy row keeps its data and gets `version = 0`, and re-running migrations applies nothing (exactly-once / idempotent). Cross-run checksum stability covered.
   - Note: `*IT` naming keeps these out of the default surefire run; they run explicitly against the portable PostgreSQL 16.4 instance on `localhost:5433` (Docker unavailable on this machine → locally-installed PostgreSQL fallback, which the workstream explicitly permits).

## Verification

- **Real PostgreSQL 16.4 (fresh DB `netherite`):** Flyway logs show "Successfully applied 7 migrations ... now at version v7"; Hibernate initializes with `ddl-auto: validate` (no validation errors); app boots and `GET /actuator/health` returns `{"status":"UP"}`.
- **`PostgresApprovalPersistenceIT`:** 6/6 pass (Flyway V1–V7, column/type metadata contract incl. NOT NULL + `version` default, JSONB round-trip, `execution_result`/`execution_error`, optimistic locking).
- **`PostgresUpgradePathIT`:** 2/2 pass (V6→V7 upgrade with live legacy row; exactly-once migration).
- **Approval regression suite (H2, default profiles):** `ApprovalEndToEndTest` (3), `ApprovalStateSafetyTest` (5) — all green.
- **Full `mvn test`:** 310 tests, 0 failures, 0 errors, 0 skipped; `BUILD SUCCESS` across all 14 modules.
- psql `information_schema` confirms `approval_requests` now has: `execution_result text`, `execution_error text`, `version bigint NOT NULL DEFAULT 0`; FKs and 4 indexes unchanged; checksums non-zero for all 7 migrations.

## Migration Safety

- Fresh schema: V1–V7 applied cleanly in one run.
- Existing schema (V6 + historical data): V7 only adds columns; legacy rows preserved with `version = 0`.
- Idempotent: re-running migrations after V7 executes 0 new migrations.
- Checksums: 7/7 non-zero and stable.

## Remaining Issues (out of workstream scope)

- `JpaApprovalService.createTicket` uses `new ObjectMapper()` (no JavaTimeModule); serializing a `DecisionContext` containing `Instant` throws, is caught, and stores `{}` into `canonical_action`/`decision_context` instead of the real payload. Pre-existing defect independent of the schema; H2 tests pass because they never assert JSONB content. Recommend a follow-up: inject a Spring-configured `ObjectMapper` (or register `JavaTimeModule`). Not fixed here to respect strict scope (schema contract only).
- `hibernate.dialect` is redundant for PostgreSQL in Spring Boot 3.3 (warning HHH90000025); harmless.
- Testcontainers PG16 preferred on CI runners with Docker; locally-installed PG16 used here per fallback.

## Files Touched

- `backend/netherite-app/src/main/resources/db/migration/V7__approval_request_schema_alignment.sql` (new)
- `backend/netherite-app/src/main/java/io/netherite/domain/entity/ApprovalRequestEntity.java`
- `backend/netherite-app/src/main/java/io/netherite/domain/entity/AuditLogEntity.java`
- `backend/netherite-app/src/main/java/io/netherite/domain/entity/AgentResourceBindingEntity.java`
- `backend/netherite-app/src/test/java/io/netherite/pgverify/PostgresApprovalPersistenceIT.java` (new)
- `backend/netherite-app/src/test/java/io/netherite/pgverify/PostgresUpgradePathIT.java` (new)
- `backend/netherite-app/src/test/resources/application-pgverify.yml` (new)