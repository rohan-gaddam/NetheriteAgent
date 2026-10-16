# Workstream 9 — Disaster Recovery, Retention & Operations (evidence ledger)

Status: **completed — COMPLETE AND VERIFIED** (see §5 verification, §8 final status).
Companion docs: `docs/operations-and-recovery.md` (runbook), `docs/production-deployment.md`,
`docs/local-development.md`, `docs/ci-cd.md`, `docs/architecture.md`.

---

## 1. Objective & acceptance

Give the control plane an operational, recoverable story that is **true to the deployed
topology and honest about what is proven**:

- A consistent, restorable **PostgreSQL backup process** using PostgreSQL-native tooling
  against the real compose deployment, with: secret handling through the existing file-secret
  mechanism (never a password on a command line or in logs/artifacts), hard failure on
  unsuccessful backup, output existence/size/structure validation, safe temporary files,
  atomic publication, no silent overwrite of a valid backup, disk-space checks, and a
  sidecar manifest recording timestamp/format/size/schema identity **without sensitive data**.
- A **repeatable restoration workflow**: validation first (clearly distinct from a successful
  restore), restore into a disposable isolated target, schema + migration-history +
  representative-data verification, refusal to target any live/named database, safe disposal
  of temporary resources on success **and** failure, and the pre-service checks runbook.
- **Recovery objectives** (RPO/RTO/retention/cadence) that are realistic and explicitly
  marked as **proposed targets requiring owner approval** unless measured.
- **Retention & cleanup** that is explicit, configurable, dry-run/prefer-reporting, confined
  to the intended data class, protective of the last known-good recovery points, and
  never destructive of user/organization/audit/session data. Policies not established by the
  repository are documented as **owner decisions**, not silently imposed.
- **Redis recovery expectations**: classification of Redis-held state, documented restart
  behavior that preserves session-invalidation and distributed-lock guarantees, no weakened
  auth/network restrictions, and restart/recovery verified (existing IT coverage + an
  operational restart check).
- **Operational safety**: meaningful nonzero exit codes, no secrets/connection strings in
  output, sanitized actionable messages, safe re-run (idempotent), documented prerequisites,
  and operational checks (backup freshness, PG availability, Redis/OPA health, app
  readiness, disk space, failed cleanup). **No claim of scheduled backups or off-host
  durability unless actually configured and verified.**
- **Tests** for the applicable behaviors (backup-failure, invalid-backup, restore-target
  safety, successful restore in an isolated target, retention cutoff + minimum-recovery-point
  protection, cleanup path validation, Redis restart/recovery, secret redaction, idempotency
  and failure cleanup), run against disposable resources only.
- **No regression** of WS1–WS8 and no destructive action against any live application data.
- Terminal report with exactly one status verb.

## 2. Gap assessment (baseline audit — recorded before implementation)

Audit date: 2026-10-10. Source inspection: `docker-compose.yml`, postgres bootstrap
(`postgres/init-db.sh`, `init.sql`), migrations `V1..V13` (`db/migration` + `db/postgresql`),
existing schedulers, prior ledgers (WS1..WS8), `ci.yml`, `cd-deploy.yml`, docs.

### 2.1 PostgreSQL deployment (audited)

| Item | State |
|---|---|
| Deployment | Compose `postgres:16-alpine` (digest-pinned), internal-only, named volume `pgdata`, `restart: unless-stopped`, healthcheck `pg_isready`, read-only rootfs + tmpfs, least-privilege `netherite_app` runtime role (init-db.sh), admin `netherite_admin` used only by the single `migrate` job |
| Migrations | Flyway V1..V13 additive-only, single migrator (`migrate` service, `service_completed_successfully` gate), checksums frozen; pgverify proves 13 applied + stable checksums |
| Data (live compose, verified 2026-10-10) | `netherite` DB at V13 (13 history rows, non-zero checksums), 1 organization, 1 user, 32 audit rows, 0 sessions, 0 idempotency records — a valid production-context backup source |
| Data (pgverify host PG :5433) | `postgres` (bootstrap), `netherite` (dev, V8 only), `netherite_verify`, `netherite_upgrade`, `netherite_distributed`, `netherite_ws4`, `netherite_ws4b` — scratch/verification databases |
| Bootstrap | `init.sql` (uuid-ossp + notice), `init-db.sh` (creates/rotates `netherite_app` password from the `db_app_password` secret) |

### 2.2 Backup / restore (gap)

| Capability | State |
|---|---|
| Backup process | **MISSING.** Only a manual one-liner in `production-deployment.md` §21 (`docker compose exec postgres pg_dump ... > file.dump`) — no script, no validation, no failure handling, no manifest, no atomicity, no retention. A valid backup has never been produced or verified by a script |
| Backup validation | **MISSING.** No existence/nonemptiness/format checks; no `pg_restore --list`; no checksum |
| Secrets in backup path | The §21 one-liner omits auth entirely (`pg_dump` requires credentials) — an operator would be forced to improvise; any implementation must use the existing file-secret mechanism |
| Restore workflow | **MISSING.** No validation-before-restore, no disposable-target isolation, no post-restore verification, no live-DB refusal guard |
| Recovery objectives | **MISSING.** No RPO/RTO/retention/cadence/failure-response/recovery-sign-off documentation |
| Off-host / encryption | Not implemented anywhere; must be documented as **not implemented** unless actually done |
| Scheduled backups | No scheduler/orchestrator — a schedule claim would be false; document cadence as owner-proposed until a scheduler is configured |

### 2.3 Retention & cleanup (audited)

| Data class | Storage | Policy today | Away |
|---|---|---|---|
| Application audit records | `audit_logs` + `audit_chain_heads` (append-only trigger, hash-chained) | **No deletion** (V10 trigger hard-rejects UPDATE/DELETE for every role). Any retention that deletes audit rows is **structurally impossible today** without breaking the chain — that is a deliberate safety property | Explicit policy + owner decision; no deletion implemented |
| Security events | Structured log lines + `audit_logs` rows | Docker `json-file` driver `max-size 50m × max-file 3` per service (WS8) | Document; keep bounded rotation |
| App & edge logs | Docker `json-file` per container | Same 50m×3 bound (WS8) | Document |
| DB backups | `pgdata` volume / backup directory | **MISSING** → new policy + script, dry-run/first, min recovery points, never delete last known-good |
| Temp restore artifacts | new disposable containers/volumes/dirs | **MISSING** → guaranteed cleanup on success and failure |
| Redis data | `redisdata` (AOF `everysec`, `noeviction`, 256 MB cap) | Ephemeral, TTL-bounded coordination | Document restart/FLUSH safety |
| Sessions | `user_sessions` (DB) | `SessionCleanupScheduler` purges expired rows (5 min) — exists | Document |
| Idempotency | `idempotency_records` (DB) | `IdempotencyCleanupScheduler` purges expired rows (30 min, bounded batch) — exists | Document |
| OIDC flows | `oidc_flow` (DB) | `OidcFlowCleanupScheduler` purges expired (5 min) — exists | Document |
| Approvals | `approval_requests` | `ApprovalExpirationScheduler` expires stale tickets; completed-replay retention per idempotency window | Document (no wholesale deletion) |
| Users / organizations | DB | No deletion anywhere; owner-bounded lifecycle | Document: do not delete; owner decision if ever needed |

No script or documented procedure exists for any of the "missing" rows above.

### 2.4 Redis & distributed state (audited)

- Redis 7.4 (`redisdata`, AOF `everysec`, `noeviction`, `requirepass` from secret file,
  internal-only, non-root, read-only rootfs). Actual usage (code audit):
  - **Distributed locks** (`RedisDistributedLockManager`, Redisson RLock: SET NX + Lua,
    ownership token, lease+watchdog) — ephemeral coordination.
  - **Rate-limit counters** (Redis broker, TTL-bounded fixed windows, `RateLimitDependencyGuard`
    fail-closed/fail-open per surface) — ephemeral.
  - **Leader heartbeat / coordination** (WS3) — ephemeral.
  - **Idempotency is PostgreSQL-backed (`idempotency_records`), NOT Redis** — durable.
  - **Sessions are PostgreSQL-backed (`user_sessions`), NOT Redis** — durable, token hashed.
- Classification: **durable business data = PostgreSQL only**; Redis holds reconstructible
  transient coordination only. A stale lock/session restored from a snapshot is exactly the
  security hazard WS9 warns about — therefore Redis must **not** be snapshot-restored as a
  whole; recovery = start empty (AOF replays only real past writes; TTLs bound them).
- Existing IT coverage: `DistributedLockIT.redisOutageFailsClosedThenRecovers` executes a
  real Redis stop/restart and asserts fail-closed then recovery; lease-expiry takeover tests
  prove crashed-holder recovery; `DistributedIdempotencyIT` proves DB-backed durability.

### 2.5 Operational scripts & observability (audited)

- Scripts present: `generate-secrets.ps1/.sh`, `verify-secret-handling.ps1`, `secret-scan.ps1`
  (+ a small `BcryptTool.java` invoked by the generator). Conventions: PowerShell 5.1-compatible,
  exit codes 0/1, sanitized output, no secret printing, `BcryptTool.java` invoked via `java -cp`
  (established "script → small Java tool" precedent).
- No operational checks (backup freshness, PG/Redis/OPA/app health, volume capacity, failed
  cleanup) exist.

### 2.6 CI/CD (audited)

- `ci.yml` jobs: backend regression (556 tests after WS8), frontend build, secret scan,
  compose-validate, docker-build + context leak gate, OSV (non-gating). **No backup/restore or
  ops-suite gate exists.** `cd-deploy.yml` is a documented template, never executed.

### 2.7 Summary

- **Implemented**: PG topology, single migrator (V1..V13), append-only audit, existing
  schedulers, bounded Docker log retention, Redis hardening, WS8 observability gates.
- **Missing (WS9 scope)**: backup process, backup validation, manifest, RPO/RTO/retention
  policy, restore workflow, recovery runbook, retention/cleanup of backups, ops checks,
  ops-suite tests, docs + ledger, CI gate for backup/restore.
- **Unsafe today**: an operator has no verified restore path and no way to know a backup is
  valid; §21's manual one-liner cannot authenticate and provides no validation.
- **Deferred (documented, not claimed)**: off-host storage, encryption, scheduled backups,
  measured RTO — explicitly gated on owner/environment.

## 3. Changes implemented (WS9)

All scripts are Windows PowerShell 5.1-compatible, ASCII-only, exit 0/1, sanitized
output (shared redaction needle-set), and strict about secrets (in-container
`/run/secrets/*` reads; child-process env for Direct mode).

### 3.1 `infrastructure/docker/scripts/netherite-backup.ps1` (new)

- Sources: `Compose` (default; postgres service, in-container secret read),
  `Container` (disposable/CI, unix-socket trust, `-w`), `Direct` (host `pg_dump`
  `+ psql`, `-PasswordFile` enters only the child-process env).
- Produces `pg_dump -Fc -Z 9` → `netherite-pg-<yyyyMMddTHHmmssZ>.dump` + `.json`
  sidecar (timestamp, format, tool, source, `server_version_num`, `schema_version`,
  `schema_history_rows`, representative counts `organizations/users/audit_logs/
  audit_chain_heads/user_sessions/idempotency_records/approval_requests/agents`,
  `size_bytes`, `sha256`). No secrets or connection strings in the manifest.
- Validation before publish: non-empty, `PGDMP` magic, `pg_restore --list` in a
  disposable target-version container (storio content gate), SHA-256 recorded,
  disk-space check (`-MinFreeBytes`, default 1 GiB), atomic rename, no-overwrite of
  an existing same-name backup, `.dump.tmp` cleaned on failure (`-NoTempCleanupOnError`
  for debugging only).
- Retention: `-PruneOnly` (default report-only; `-Apply` deletes), `-RetentionCount`
  minimum recovery points (default 3), `-RetentionDays` (0 = keep all), strict name
  pattern, refuse to delete the newest point, refuse path/link escapes.
- Debugged in-session: script-scope runner vars (dynamic-scope fix), bash
  single-quote-safe SQL embedding (`'\''` for metadata queries), icacls `*SID`
  synthetic-account fix, `docker exec` without the removed `-T` flag (Docker 29
  CLI).

### 3.2 `infrastructure/docker/scripts/netherite-restore.ps1` (new)

- Default = **validate only** (no database touched): existence + non-empty, `PGDMP`
  magic, SHA-256 vs sidecar, `pg_restore --list` in a disposable container.
- `-PerformRestore` = restore into a brand-new disposable `postgres:16-alpine`
  container (fresh volume, unique name); pre-creates reproducible roles
  `netherite_admin`, `netherite_app`; `pg_restore --exit-on-error`; verification:
  schema version + migration-history count vs manifest, non-zero checksums,
  representative counts vs manifest, `trg_audit_append_only` +
  `uk_audit_request_event` present, negative probe (`UPDATE audit_logs`) still
  rejected; disposable container + volume disposed on success and failure
  (`-KeepContainer` debug).
- Target safety by construction: disposable-only (no live-DB parameter exists);
  target name must match strict prefix `netherite_restore_verify_` and must NOT be
  `netherite`, `netherite_verify`, `netherite_upgrade`, `netherite_distributed`,
  `netherite_ws4`, `netherite_ws4b`, `postgres`, `template0`, `template1`.
- Bug found & fixed during work: `param([string[]]$Args)` silently never bound
  (PS automatic `$args`) which made early runs no-ops — replaced with
  `$DockerArgs`; empty-stdout `.Trim()` null reads guarded; `pg_restore` given
  psql-style `-v ON_ERROR_STOP` removed in favor of `--exit-on-error` + stderr
  surfacing; corrupted em-dash (UTF-8/ANSI round-trip) repaired byte-level.

### 3.3 `infrastructure/docker/scripts/netherite-ops-checks.ps1` (new)

Read-only host-side checks (exit 0 only if all pass): docker reachable → compose
parses → all five services running + healthy (compose `ps --format json`) →
postgres live counter (read-only count) → redis PONG + `aof_last_write_status:ok`
→ opa health endpoint (in-container token) → app E2E via Caddy `/healthz` →
newest-backup freshness (< 26 h default) + manifest present + SHA-256 match → no
leftover `.dump.tmp` → free disk ≥ 1 GiB. Secrets read in-container only; outputs
redaction-prefiltered.

### 3.4 `infrastructure/docker/scripts/netherite-ops-tests.ps1` (new)

Disposable-resources-only regression suite (T01..T12 + redaction probe):
T01 invalid (non-PGDMP) backup rejected; T02 tampered backup rejected vs manifest;
T03 missing file rejected; T04 reserved/wrong-prefix restore targets refused + no
leftovers (and an allowed-prefix run passes); T05 backup from unknown container
fails clean (no `.dump.tmp`, no published artifacts); T06 overwrite guard (two
same-second runs produce valid distinct sets, no temp leftovers); T07 `-PruneOnly`
deletes nothing; T08 `-Apply` honours min recovery points + never deletes newest;
T09 disposable Redis restart → PONG after full restart; T10 ops-checks smoke
(exit 0, 0 FAIL); T11 backup `-Source Container` of a schema-complete disposable DB
+ validate (manifest `schema_version=13`); T12 full disposable restore of the
live-compose backup (verify lines + container/volume disposed); T-redact no secret
material in manifests or ops-checks output. Guard: tests only create
`netherite-ops-*` / `netherite-restore-verify-*` resources.

### 3.5 Docs

- `docs/operations-and-recovery.md` (new runbook): durability map, RPO/RTO
  (PROPOSED targets — owner approval required; measured-RTO/off-host/encryption/
  scheduler NOT claimed), backup/restore usage + safety, scenario procedures
  (corruption, failed migration, container/volume loss, redis restart, host loss),
  startup order, ops-checks + recovery sign-off checklist, retention policy
  summary + owner decisions, deferral list, security fast-facts.
- `docs/production-deployment.md` §21 rewritten to the scripted workflows.
- `.gitignore` / `.dockerignore`: `infrastructure/docker/backups/` ignored.

## 4. Files produced / modified (WS9)

| Path | Change |
|---|---|
| `infrastructure/docker/scripts/netherite-backup.ps1` | new (backup + manifest + retention) |
| `infrastructure/docker/scripts/netherite-restore.ps1` | new (validate + disposable restore) |
| `infrastructure/docker/scripts/netherite-ops-checks.ps1` | new (read-only operational checks) |
| `infrastructure/docker/scripts/netherite-ops-tests.ps1` | new (T01..T12 + redaction probe) |
| `docs/operations-and-recovery.md` | new runbook |
| `docs/production-deployment.md` | §21 rewritten |
| `docs/local-development.md` | §1.1 backup/restore/ops commands |
| `docs/architecture.md` | new "Operations, Backup & Recovery (WS9)" section |
| `docs/ci-cd.md` | new §5 "WS9 operational gates" |
| `infrastructure/docker/scripts/secret-scan.ps1` | approved-list: two narrow doc entries for the already-approved WS8 synthetic canary `ntk_live_ws8.canary-secret` (path+rule+literal+reason; 0 findings after) |
| `.gitignore` / `.dockerignore` | ignore `infrastructure/docker/backups/` |

## 5. Verification & evidence

All commands run 2026-10-10 on the WS9 host (Docker 29.8.0 CLI, Compose v5.5.1,
Windows PowerShell 5.1). The compose stack was live and healthy throughout; no
destructive command was ever run against it.

### 5.1 Backup produced and validated (live Compose source)

```
PS> ...\netherite-backup.ps1
== base name: netherite-pg-20261010T073309Z
== verified: size=64845 bytes, sha256=da02a753850a..., pg_restore --list OK (159 entries)
== backup published: ...\backups\netherite-pg-20261010T073309Z.dump
== manifest published: ...\backups\netherite-pg-20261010T073309Z.json
Retention: 1 backup(s) <= minimum recovery points 3; nothing to prune.
```

Manifest contents (no secrets): `created_at 2026-10-10T07:33:09Z`, `format
postgres-custom-compressed`, `tool pg_dump -Fc -Z 9`, `source Compose`,
`server_version_num 160015`, `schema_version 13`, `schema_history_rows 13`,
`organizations 1`, `users 1`, `audit_logs 32`, `audit_chain_heads 1`,
`user_sessions 0`, `idempotency_records 0`, `approval_requests 6`, `agents 2`,
`size_bytes 64845`, `sha256 da02a75385…`.

### 5.2 Restore drill (validate + disposable + verify + dispose)

```
PS> ...\netherite-restore.ps1 -BackupFile <dump> -PerformRestore
PASS: ... (PGDMP) present
PASS: SHA-256 matches the sidecar manifest (da02a753...)
PASS: pg_restore --list read the archive (159 object entries)
== restoring into disposable container 'netherite-restore-verify-<ts>' (database 'netherite_restore_verify_<ts>')
PASS: disposable PostgreSQL container ready
PASS: backup copied into disposable container
PASS: reproducible roles (netherite_admin, netherite_app) pre-created
PASS: disposable database '<db>' created
PASS: pg_restore completed successfully into the disposable target
PASS: migration history matches manifest (schema v13, 13 rows)
PASS: all migration checksums are non-zero
PASS: restored organizations row count (1) matches manifest
PASS: restored users row count (1) matches manifest
PASS: restored audit_logs row count (32) matches manifest
PASS: restored audit_chain_heads row count (1) matches manifest
PASS: restored user_sessions row count (0) matches manifest
PASS: restored idempotency_records row count (0) matches manifest
PASS: restored approval_requests row count (6) matches manifest
PASS: restored agents row count (2) matches manifest
PASS: append-only trigger trg_audit_append_only present
PASS: tenant-scoped audit uniqueness constraint (uk_audit_request_event) present
PASS: append-only protection still rejects UPDATE on audit_logs (negative probe)
== RESTORE SUCCEEDED ...
== disposed container: ...   == disposed volume: ...
```

### 5.3 Negative paths (all exit 1, no resources left)

- Tampered dump (bit flip): `pg_restore --list rejected` / with sidecar present:
  `SHA-256 does not match the sidecar manifest`.
- Non-PGDMP file: magic check FAIL. Missing file: `not found`. Reserved target
  names (`netherite`, `custom_db`): `Refusing restore target name …` BEFORE any
  container creation; no `netherite-restore-verify-*` leftovers (verified after
  refusal and after T04's allowed-prefix probe).
- Backup from unknown container: exit 1, no `.dump`, `.json`, or `.dump.tmp` under
  the output dir.

### 5.4 Ops test suite

```
PS> ...\netherite-ops-tests.ps1
== 0 FAIL, 20 PASS ==    (EXIT=0)
```

Covers T01..T12 + T-redact (see §3.4). Includes Redis full-restart → PONG (T09) and
a `-Source Container` round-trip against a schema-complete disposable DB (T11,
manifest `schema_version=13`). No test container/volume left behind (final
leftover scan empty).

### 5.5 Ops checks (read-only, live stack)

```
PS> ...\netherite-ops-checks.ps1
PASS × 14 (docker, compose, 5 services healthy, postgres count, redis PONG + AOF
ok, opa health, caddy /healthz 200, backup freshness + integrity, no .dump.tmp,
disk >= 1 GiB)
== 0 FAIL, 14 PASS ==    (EXIT=0)
```

### 5.6 Redis restart evidence

- `DistributedLockIT.redisOutageFailsClosedThenRecovers` (existing WS4 IT): real
  Redis stop/restart → fail-closed then recovery; lease-expiry takeover tests prove
  crashed-holder recovery.
- T09 (this WS): disposable Redis `docker restart` → PONG before and after, clean
  disposal.

## 6. Regression (WS1–WS8, plus new gates)

All gates re-run on 2026-10-10 (JDK 21 via `.tools/jdk-21`, Maven via `.tools/maven`,
Windows PowerShell 5.1). **Result: all gates green; no regression.**

| Gate | Command | Result |
|---|---|---|
| Backend full suite | `mvn -o -pl netherite-app -am test` (from `backend/`, JAVA_HOME=jdk-21) | **556 tests, 0 failures, 0 errors — BUILD SUCCESS** |
| PostgreSQL ITs | `mvn -o -pl netherite-app -am test -Dtest=PostgresApprovalPersistenceIT,PostgresUpgradePathIT,EnterpriseRuntimeIT` (pgverify profile, PG on localhost:5433) | **15 tests, 0 failures — BUILD SUCCESS** |
| Frontend build | `npm run build` (`tsc && vite build`) | exit 0, built in 3.6 s |
| Compose validation | `docker compose config -q` | exit 0 |
| Secret scan | `secret-scan.ps1` (after §4 approved-list additions) | **0 findings, PASS** |
| Ops checks | `netherite-ops-checks.ps1` | **14/14 PASS, exit 0** |
| Ops tests | `netherite-ops-tests.ps1` | **20/20 PASS (T01..T12 + redact), exit 0** |

Final workspace hygiene verified: `infrastructure/docker/backups/` contains only
the real recovery point (`netherite-pg-20261010T073309Z.dump` + `.json`); no
`netherite-restore-verify-*` containers or volumes remain.

## 7. Remaining risks & owner decisions

| Item | Risk / decision needed |
|---|---|
| Off-host storage | Backups live on the same host/disk as the DB; host+volume loss is not recoverable from the host itself. **Owner: configure off-host archiving.** |
| Backup encryption | Backups are unencrypted on local disk. **Owner: adopt encryption if local disk is untrusted.** |
| Scheduled backups | Cadence is a manual operator action today. **Owner: approve a scheduler** (this would also let RPO become a measured commitment). |
| Measured RTO | Only proposed (≤ 1 h). **Owner: run a timed drill and record effective RTO/RPO.** |
| Retention values | RPO ≤ 24 h / retention ≥ 3 points are proposed defaults. **Owner: approve count + days.** |
| No restore-to-live tooling | The verify+restore path is demonstrated, but promoting a disposable restore into the real `pgdata` volume is a deliberate manual downtime action. **Owner: approve a promotion tool if zero-risk automation is desired.** |
| Audit retention | Append-only by design; no deletion is structurally possible/desirable. **Owner: record this as accepted policy.** |
| 256-bit hashes, chain heads | Unchanged from WS3; restores preserve chain integrity (verified by constraint + trigger presence and append-only probe). |

## 8. Final status

**COMPLETE AND VERIFIED** — backup process, disposable restore workflow (+ full
verification and negative-path battery), retention policy with min-recovery-point
and never-delete-newest safety, Redis recovery expectations (documented + tested),
read-only operational checks, a disposable ops test suite, a runbook, updated docs,
and WS9 evidence ledger are all delivered and green. Deferred items (off-host,
encryption, scheduler, measured RTO) are explicitly documented as owner decisions,
not claimed. **Blocking owner decisions before WS10**: approve RPO/RTO/retention
values, off-host archiving, and scheduler cadence.