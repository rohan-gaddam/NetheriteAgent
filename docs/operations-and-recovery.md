# Operations & Recovery (WS9 runbook)

Status: **WS9 delivered, COMPLETE AND VERIFIED** — procedures in this document are
executed by a human operator on the host that runs the compose stack; the two scripts
(`netherite-backup.ps1`, `netherite-restore.ps1`) are the only backup/restore entry
points and REPLACE the earlier manual §21 one-liner.

Companion docs: `workstream-9-disaster-recovery-retention-operations.md` (evidence
ledger), `production-deployment.md`, `local-development.md`, `ci-cd.md`,
`architecture.md`.

---

## 1. Where durable data lives (and where it does not)

| Data | Location | Recoverability |
|---|---|---|
| Organizations, users, sessions, audit records, approvals, agents, idempotency | **PostgreSQL** (`netherite` DB, volume `pgdata`) | **Backup + restore scripts in this document** |
| Distributed locks, rate-limit counters, leader heartbeat | **Redis** (volume `redisdata`, AOF everysec) | Reconstructible — see §8; **do not snapshot-restore Redis** |
| Structured logs, security events | Docker `json-file` logs | 50 MB × 3 per service (bounded, WS8) |

Everything durable lives in PostgreSQL. Redis holds only TTL-bounded transient
coordination, so Redis recovery is "start empty", never "restore a snapshot".

## 2. Recovery objectives (PROPOSED — require owner approval)

> These numbers are **proposed targets**, not measured commitments. Archiving to
> off-host storage has not been implemented, so a real-world RPO depends on how
> often the operator runs the backup script. A measured RTO is not established.

| Objective | Proposed target | Basis |
|---|---|---|
| RPO (data loss window) | ≤ 24 h (daily backup cadence) | Backup script defaults; **owner to approve cadence scheduler** — no scheduler is configured today |
| RTO (restore time) | ≤ 1 h for full restore + verify | Scripted disposable restore completes in minutes on this hardware; not load-tested |
| Recovery points retained | ≥ 3 (configurable, `-RetentionCount`) | Never deleted below the minimum; newest is never deleted |
| Backup freshness alarm | < 26 h (ops-checks default) | `netherite-ops-checks.ps1` FAILS if stale |

Manual operator cadence is the **current** backup schedule: no cron/scheduler or
off-host transfer is configured. Do not claim otherwise.

## 3. Prerequisites

- Docker engine running on the host (scripted flows use disposable containers).
- Secrets present under `infrastructure/docker/secrets/` (file-secret mechanism).
  Passwords are **read inside** the postgres/redis/opa containers from
  `/run/secrets/*` — never on a host command line or in logs.
- Read-permissions for the operator performing backup/restore.
- For restore: **the target must always be a disposable container** created by the
  restore script. There is no parameter that targets a running/live database.

## 4. Backing up (netherite-backup.ps1)

Location: `infrastructure/docker/scripts/netherite-backup.ps1`.

### 4.1 Normal run (production compose)

```powershell
& .\infrastructure\docker\scripts\netherite-backup.ps1
```

- Dumps the compose `postgres` service (`netherite` DB) with `pg_dump -Fc -Z 9`.
- Publishes atomically:
  - `infrastructure/docker/backups/netherite-pg-<yyyyMMddTHHmmssZ>.dump`
  - `.json` sidecar manifest (timestamp, format, tool, source, server version,
    schema version, migration-history rows, and representative row counts —
    **no secrets, no connection strings**).
- Validates before publish: non-empty, PostgreSQL custom-format magic (`PGDMP`),
  `pg_restore --list` readable with storable content (target-version disposable
  container), SHA-256 recorded.
- Refuses to overwrite an existing same-name backup; refuses to run when free disk
  is below `-MinFreeBytes` (default 1 GiB).
- Fails hard (exit 1, temp `.dump.tmp` cleaned) on any failed step, so a partial
  file is never presented as a backup.

### 4.2 Other sources (verification only)

- `-Source Container -ContainerName <name>` — disposable/CI container with trust
  auth (unix socket). Used by the ops test suite; not a production path.
- `-Source Direct` — local PostgreSQL tools (`-HostConnection -Port -User
  -PasswordFile -PgToolsDir`). For an operator with a stock PG install; the
  password is passed only in the child-process environment.
- `-BackupDir <dir>` overrides the output directory.

### 4.3 Retention (dry run first)

```powershell
# report only (never deletes)
& .\...\netherite-backup.ps1 -PruneOnly
# report + delete with policy
& .\...\netherite-backup.ps1 -PruneOnly -RetentionCount 3 -RetentionDays 14 -Apply
```

Safety properties (script-enforced):

- Strict filename pattern `netherite-pg-<ts>.dump` only (order/.tmp/other names
  are ignored).
- **Never deletes the newest recovery point.**
- Never deletes below `-RetentionCount` minimum recovery points (default 3).
- `-RetentionDays 0` = keep everything regardless of age (used by tests).
- Works on the `.dump` + matching `.json` pair.

## 5. Validating a backup (without touching anything)

```powershell
& .\...\netherite-restore.ps1 -BackupFile <dump>
```

Verifies: file exists and is non-empty → `PGDMP` magic → SHA-256 matches the sidecar
manifest → `pg_restore --list` reads the archive (disposable container, no server
running) → prints `VALIDATED`. Exit code 1 refuses the backup; never trust it.

## 6. Restoring (into a disposable isolated target)

```powershell
# validate + restore + verify + dispose (the full drill)
& .\...\netherite-restore.ps1 -BackupFile <dump> -PerformRestore
```

Flow (all in a brand-new disposable `postgres:16-alpine` container with a fresh
volume — never the live stack):

1. Pre-creates the reproducible roles `netherite_admin`, `netherite_app`.
2. Restores the custom-format dump (`pg_restore --exit-on-error`).
3. Verifies:
   - Schema version and migration-history row count match the manifest;
   - All Flyway checksums are non-zero;
   - Representative row counts match the manifest (organizations, users, audit
     logs, chain heads, sessions, idempotency, approvals, agents);
   - Append-only trigger `trg_audit_append_only` and the tenant-scoped audit
     uniqueness constraint are present;
   - A negative probe proves `UPDATE audit_logs` is still rejected.
4. Disposes the container and volume on success **and** failure.
5. `-KeepContainer` (debug) leaves the disposable target running and prints its
   name/database; the live stack is never touched.

Safety is by construction: the restore target is always the disposable container's
internal database; its name must match the strict `netherite_restore_verify_`
prefix and must not be a reserved name (`netherite`, `netherite_verify`,
`netherite_upgrade`, `netherite_distributed`, `netherite_ws4`, `netherite_ws4b`,
`postgres`, `template0`, `template1`) or the script refuses to proceed.

## 7. Recovery scenarios (ordered procedures)

Common pre-step for every scenario: prove the recovery point first

```powershell
& .\...\netherite-restore.ps1 -BackupFile <dump>
# must print VALIDATED with exit 0 before any production action
```

### 7.1 Accidental data deletion or corruption (single DB)

1. Stop writes: scale the app down (`docker compose stop app`; keep postgres up).
2. Validate the newest backup (§5). If the newest is invalid, work back to an older
   one that validates.
3. Rehearse: run the full disposable restore (§6). Confirm the missing/corrupted
   data is present in the disposable target and the append-only probe passes.
4. Apply: replace the live `netherite` database content with the disposable
   target's content (the safe apply procedure today is restore-the-whole-DB; there
   is no row-level surgery tool). Documented owner decision: no patch tool exists.
5. Re-run `docker compose run --rm migrate` to confirm Flyway sees the restored
   schema as current; then `docker compose start app`.
6. Verify with the ops checks (§9) and update backup freshness.

> **env-gated**: automated "promote disposable → live container" tooling is not
> implemented; step 4 today is a manual, downtime operator action.

### 7.2 Destroyed/failed Flyway migration

- Same as 7.1 but starting from a **pre-migration** backup (advance slowly through
  recovery points if unsure which state is wanted). Never hand-edit
  `flyway_schema_history`; restore the whole DB.

### 7.3 PostgreSQL container/volume loss (full local loss)

1. Validate the newest backup.
2. `docker compose down` the stack (postgres first).
3. Replace the data: the disposable restore (§6) proves the backup is restorable;
   to repopulate the real `pgdata` volume, restore into a disposable target, then
   copy the database out with `pg_dump | pg_restore` into a fresh empty
   postgres service volume (again a downtime action; no in-place magic).
4. Start `migrate`, then the rest of the stack, then verify (§9).

> **env-gated and owner decision**: true off-host restore (another machine / cloud
> volume) is not implemented; the procedures above restore within the same host.

### 7.4 Redis restart / rollback

- Redis holds no durable business data (§1), so **never snapshot-restore Redis**.
- Restart: `docker compose restart redis` (data directory is AOF-replayed; TTLs are
  in the keys and re-apply on replay). Distributed locks self-heal via leases +
  watchdog, rate-limit counters re-accumulate, sessions stay valid because they are
  in PostgreSQL. This behavior is covered by `DistributedLockIT`
  (`redisOutageFailsClosedThenRecovers`) and the ops test suite (restart → PONG).
- Rollback to a previous app image: Redis needs **no** wipe for app-image rollback;
  `redis-cli FLUSHALL` is only used if an operator deliberately wants to reset
  coordination state — never to "restore" it.

### 7.5 Host + volume loss (whole machine)

- **Not recoverable from the host itself.** Restores require a validated backup file
  and another machine with Docker + the secrets. This is the off-host gap: archive
  the `backups/` directory off-host (operator task; no automated transfer exists).

## 8. Startup & dependency order after any recovery

Used by the compose file (`depends_on`), reproduced for reference:

1. `postgres` healthy (`pg_isready`, admin user, target DB)
2. `migrate` runs Flyway (single migrator; `service_completed_successfully` gate)
3. `opa` healthy (policy load + bearer-token health endpoint)
4. `redis` healthy (auth ping)
5. `app` liveness (`/actuator/health/liveness`; startup gate waits on migrate +
   redis + opa)
6. `frontend` (Caddy) healthy (`/healthz` over TLS)

## 9. Post-recovery health verification

```powershell
& .\...\netherite-ops-checks.ps1
```

Checks (exit 0 only if all pass): docker reachable → compose parses → all five
services running + healthy → postgres live counter (read-only) → redis PONG + AOF
last-write status ok → opa health endpoint → app reachable end-to-end via Caddy
`/healthz` → newest backup fresh (< 26 h) with matching SHA-256 → no left-over
`.dump.tmp` → disk ≥ 1 GiB.

### Recovery sign-off checklist

- [ ] Chosen backup validated (`VALIDATED`, exit 0) against its manifest
- [ ] Disposable drill completed (restore + all verification lines PASS)
- [ ] `migrate` re-run clean; Flyway history matches restored schema
- [ ] ops-checks all PASS (exit 0)
- [ ] Backup set refreshed; retention re-run and reviewed
- [ ] Owner records the incident + effective RTO/RPO in the ledger

## 10. Operators tests (regression for the tooling)

```powershell
& .\...\netherite-ops-tests.ps1
```

Disposable-only suite (T01–T12 + redaction probe): invalid/tampered/missing backup
rejection, restore-target safety, unknown-container backup failure with no temp
leftovers, overwrite guard, retention report/apply behavior (min recovery points,
newest never deleted), disposable Redis restart → PONG, ops-checks smoke, backup
from a schema-complete disposable container, full disposable restore, and a
no-secret-in-output probe. Exit 0 = all passed.

## 11. Deferred items (explicitly NOT claimed)

- Off-host storage and restore (operator/manual; no automation).
- Backup encryption (backups are unencrypted on local disk).
- Scheduled/automated backup cadence (manual operator cadence today; owner to
  approve and configure a scheduler).
- Measured RTO (only proposed targets; see §2).
- Audit-record deletion (append-only by design — see below).

## 12. Retention policy summary (owner decision points)

- **Audit records**: no deletion is structurally possible (`trg_audit_append_only`
  hard-rejects UPDATE/DELETE for every role; hash-chained heads). Deliberate safety
  property, keep. Owner decision recorded: do not weaken it.
- **Sessions / OIDC flows / approvals / idempotency**: existing schedulers already
  purge expired rows (5 min / 5 min / 60 s / 30 min bounded). Documented; no change.
- **Database backups**: scripted, dry-run-first, minimum recovery points, newest
  never deleted, strict naming (§4.3). **Owner to approve count/days.**
- **Docker logs**: 50 MB × 3 per service (WS8); keep.

## 13. Security properties of this runbook (fast facts)

- No password or token is placed on a command line at any point; in-container secret
  reads / child-process environment only.
- No parameter can point restore at a live/named database; disposable-target-only.
- Scripts exit non-zero on any failure; partial artifacts are cleaned or refused.
- Script outputs are sanitized through the shared redaction needle-set.