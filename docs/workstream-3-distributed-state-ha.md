# Workstream 3 — Distributed State, High Availability & Execution Reliability

Status: COMPLETE AND VERIFIED
Last updated: 2026-09-24

This workstream makes the NetheriteAgent control plane safe to run as multiple
replicas against one PostgreSQL database: distributed locking, durable and
cluster-wide idempotency, crash/failure semantics, Redis-outage fail-closed
behavior, coordinated schedulers, and cross-node approval continuation with
recovery. It introduces a **PostgreSQL-backed durable idempotency store** (the DB
is the authority for dedup/state) and a **Redisson-backed distributed lock
manager** for cluster-wide mutual exclusion, incl. watchdog leases for
long-running scheduler cycles.

---

## Phase 0 — Ground truth (as-built inspection)

Infrastructure actually present on this machine:

- PostgreSQL 16 (`git-bash` portable, `netherite_admin` trust auth, port 5433).
  Databases: `netherite` (V1–V8 applied), `netherite_verify` (pgverify),
  `netherite_upgrade` (upgrade path).
- Official Docker for Desktop **absent** → Testcontainers is not available.
- Redis: only `spring.data.redis.*` config properties; **no client dependency
  and no Redis implementation anywhere**. Parent BOM *manages*
  `org.redisson:redisson-spring-boot-starter:3.34.1` but nothing declares it.
- Internet available (verified `repo1.maven.org`, `api.github.com` → 200).

Coordination primitives today (single-JVM only):

- `io.netherite.state.spi.DistributedLockManager`:
  `tryAcquireLock(key, waitTime, leaseTime)`; `releaseLock(key)`.
- `io.netherite.state.spi.IdempotencyStore`: `tryClaim`, `getRecord`,
  `markCompleted`. Enum PROCESSING/COMPLETED/FAILED.
- Sole implementations: `InMemoryDistributedLockManager`,
  `InMemoryIdempotencyStore` (`@Component`, `ConcurrentHashMap`, `synchronized`).
  Single-node only; do not survive restart; `releaseLock(key)` removes a lock
  **without ownership verification** (a stale release could free a newer owner).

Execution/approval wiring:

- `ConnectorExecutionService.execute` (netherite-app): policy → DENY/HUMAN_APPROVAL/ALLOW
  → idempotency check/claim (TTL 30 min) → distributed lock
  (`execution:{org}:{agent}:{actionType}`, wait 5 s, lease 30 s) → route →
  binding → SSRF → connector → audit + health → `markCompleted` → unlock.
  Gaps: no FAILED marking (stuck PROCESSING for 30 min on deterministic
  failures); duplicate PROCESSING returns 403 `ERR_RESOURCE_NOT_BOUND` (not a
  distinct 409); keys not tenant-scoped w/ namespace; no idempotency/execution
  propagation to the external system.
- `ApprovalContinuationService.continueAfterApproval` (`@Async`): re-checks
  agent/resource/binding, then calls `ConnectorExecutionService`. Duplicate
  protection relies on the `@Version` guard of `resolveTicket` only — an
  approved-then-crashed node leaves the ticket APPROVED with `execution_result`
  NULL and **no recovery path**. No claim lease, no attempt bound.
- Schedulers: `ApprovalExpirationScheduler` (60 s), `OidcFlowCleanupScheduler`
  (300 s, idempotent DELETE), `ResourceHealthScheduler` (3600 s, already uses a
  `netherite:health-check:scheduler` lock key but via the in-memory manager;
  releases via `releaseLock(key)`).
- `@EnableScheduling` + `@EnableAsync` at `NetheriteApplication`; no
  `TaskExecutor`/`AsyncConfigurer` defined.

---

## Phase 1 — Gap analysis

Classification:

| Bucket | Findings |
|---|---|
| A. Single node crash / approval crash | `continueAfterApproval` crash → ticket stuck APPROVED/executable forever. No execution claim/lease/attempt bound. |
| B. Multi-node correctness | Idempotency store + locks are `ConcurrentHashMap` in memory → no cross-replica guarantee. Schedulers fire on every replica. `releaseLock(key)` can free another owner's lock. |
| C. Re-execution with unknown outcome | Execution timeouts/crashes leave PROCESSING claims for the full TTL; retry after crash re-claims (documented at-least-once with external dedup key). |
| D. Deterministic errors | Duplicate/conflict and lock failures collapse into 403/`ERR_RESOURCE_NOT_BOUND`; need distinct codes (409 conflict; 503 infra already exists). |
| E. Infrastructure outage semantics | No Redis usage today. Must be fail-closed (503 `ERR_SYSTEM_UNAVAILABLE`), never silently fall back to in-memory. |

Answers to the coordination model questions (design decisions):

1. Lock: Redisson `RLock` (SET NX PX + Lua, ownership token, lease expiry,
   optional watchdog). Configurable provider `redis` (default) / `memory`
   (test, pgverify). Fail-closed: any Redis command failure maps to
   `ERR_SYSTEM_UNAVAILABLE`; no fallback.
2. Idempotency: **PostgreSQL-backed durable store** (V8 migration). The DB is
   the authority — it survives restart, is cluster-consistent, and needs no
   distributed algorithm. Atomic claim = `INSERT ... ON CONFLICT DO NOTHING` +
   expired-PROCESSING takeover `UPDATE ... WHERE expires_at < now()`.
3. Cross-node duplicates: dedup at DB PK level + Redisson mutual exclusion for
   the execution body; `@Version` for approval resolution remains.
4. `markFailed` added to the SPI; FAILED records short-TTL so deterministic
   failures are surfaced instead of hanging in PROCESSING for 30 min.
5. Scheduler coordination: watchdog lease locks for health + approval
   expiration (single runner). Approval recovery uses a per-ticket atomic DB
   claim (no global lock needed — the claim is the mutual exclusion).
6. Propagation: `X-Netherite-Execution-Id` + `X-Netherite-Idempotency-Key`
   headers to external systems by the HTTP connector.
7. Redis outage: request-time 503 (fail-closed), process stays up; Redisson
   reconnect restores automatically; health actuator reports coordination
   UP/DOWN.

Migration rule honored: **V1–V7 are never modified**. All schema changes are a
new `V8__distributed_state.sql`.

---

## Phase 2+ — Design summary

Schema (V8, additive):

- `idempotency_records(organization_id, idempotency_key) PRIMARY KEY`,
  `state` CHECK (PROCESSING/COMPLETED/FAILED), `cached_response` TEXT,
  `external_request_id`, `attempt_count`, `created_at/updated_at`,
  `expires_at`. Indexes on state/expires.
- `approval_requests` + columns: `execution_in_progress`,
  `execution_claim_started_at`, `execution_lease_expires_at`,
  `execution_attempts`, `execution_claimed_by`. Index on
  `(status, execution_in_progress, execution_lease_expires_at)`.

Components (all in netherite-app, next to existing impls, or in netherite-state
SPI):

- `NetheriteDistributedProperties` — `netherite.distributed.*` (provider,
  lock-prefix, node-id, timeouts, ttl defaults).
- `RedissonClient` bean (provider=redis): single-server config from
  `spring.data.redis.*`, `StringCodec`, bounded pool/timeouts,
  `destroyMethod=shutdown`, and **`@Lazy` on the bean *and* on both injection
  points** (`RedisDistributedLockManager`, `DistributedCoordinationHealthIndicator`).
  `Redisson.create()` connects eagerly, so without lazy injection the bean would
  be resolved during context refresh and an idle Redis would abort startup —
  verified empirically (boot failed at `redissonClient` factory), then fixed.
  With lazy injection the client is created on first use, and every creation/use
  failure is wrapped fail-closed by the lock manager.
- `RedisDistributedLockManager` + `RedissonDistributedLock` — ownership-safe
  unlock; watchdog overload; fail-closed.
- `JdbcIdempotencyStore` — durable store (SQL-atomic, no JPA flush races).
- `InMemory*` — gated to provider=memory (test/pgverify).
- `ApprovalExecutionCoordinator` (replaces direct `continueAfterApproval` call)
  + `ApprovalExecutionRecoveryScheduler`.

Execution flow (per request), on any replica:

```
policy → DENY/HUMAN_APPROVAL/ALLOW
ALLOW →
  get existing record:
    COMPLETED → replay cached result (IDEMPOTENT_REPLAY)
    PROCESSING & not expired → 409 ERR_IDEMPOTENCY_CONFLICT
    FAILED & within fail-window → 409/short-window (deterministic failure surfaced)
  tryClaim (DB, ttl=executionTtl 30m) → else 409
  lock (wait 5s, lease 30s) → failed → markFailed(short) + 503
  route/binding/SSRF/connector:
    deterministic failure → markFailed(short) + throw
    connector outcome stored; unknown-outcome crash → PROCESSING till expiry → takeover
  success → markCompleted(ttl) → 200
```

Approval continuation flow:

```
approve → resolveTicket (status=APPROVED, @Version) →
  executeClaim(ticketId)  [own tx, atomic UPDATE, lease 15m, attempts+1, nodeId]
  if claimed → @Async run(async, reload entity):
     verify agent/resource/binding (still valid) →
     ConnectorExecutionService.execute(originalAction) →
     success → execution_result + release claim
     transient infra error (503/504) → release lease WITHOUT error → recovery retries
     deterministic error → execution_error + release claim (terminal, no retry)
  else → skip (another replica owns it)
ApprovalExecutionRecoveryScheduler (60s): pick claimed-but-stale
  (execution_in_progress=true AND lease < now AND attempts < 3 AND result/error null)
  → atomic takeover → schedule async. Attempts >= 3 → terminal execution_error.
```

Scheduler coordination:

- `ResourceHealthScheduler`: watchdog lock `netherite:lock:health-check:scheduler`,
  release via handle (ownership-safe).
- `ApprovalExpirationScheduler`: watchdog lock `netherite:lock:approval-expiration:scheduler`.
- `OidcFlowCleanupScheduler`: idempotent DELETE, unchanged (documented).
- Cleanup: `IdempotencyCleanupScheduler` deletes rows with `expires_at < now`.

Observability:

- `NodeIdentity` (random per process) stamped in claim/lease columns and logs.
- `DistributedCoordinationHealthIndicator` — Redis reachability (provider redis).
- Structured logs already present; add acquisition-latency + outcome logs.

Failure semantics (buckets B/E):

- Redis down (locks): every coordination call → 503 `ERR_SYSTEM_UNAVAILABLE`;
  startup unaffected; automatic recovery via Redisson reconnect.
- DB down: existing behavior (503/DB errors), unaffected by this workstream.
- Node crash mid-claim: lock expires per lease (watchdog → ~30 s);
  idempotency PROCESSING expires after TTL then re-claimable;
  approval claim lease expires then recovery takes over.

Verification plan (real Redis 5.0.14 + real PG 5433, two real process nodes):

1. IT suite under `distributed` profile (real PG + real Redis) — lock
   exclusivity/lease/ownership across two independent Redisson clients,
   fail-closed manager, JdbcIdempotencyStore claim/concurrency/takeover/
   isolation/replay/markFailed, approval claim SQL.
2. Two-process E2E (8080 + 8081): boot both; coordination health UP; stop Redis
   → both fail closed 503; restart → both recover; scheduler locks visible in
   Redis with TTLs; cross-node idempotency/approval single-execution.
3. Full regression: backend unit suite, pgverify ITs (V1..V8), default-profile
   boot with V8 applied, frontend `tsc && vite build`.

All three executed — see Evidence log.

## Evidence log

### 1. Distributed IT suite (real PostgreSQL 5433 + real Redis 6379) — GREEN

Run: `mvn -pl netherite-app test -Dtest=DistributedLockIT,DistributedIdempotencyIT,ApprovalExecutionClaimIT`

- `DistributedLockIT`: **6/6** — exclusivity/ownership across two independent
  Redisson clients, lease expiry + crashed-holder takeover, watchdog release,
  fail-closed on unreachable Redis (`RedisConnectionException` →
  `NetheriteException ERR_SYSTEM_UNAVAILABLE`, healthy manager unaffected),
  real outage (shutdown → blocked → restart → recovered without restart).
- `DistributedIdempotencyIT`: **7/7** — durable claim/takeover/completion on
  PostgreSQL (`INSERT ON CONFLICT`, expired-PROCESSING takeover), tenant
  isolation (UUID org binding), replay of COMPLETED, `markFailed` short window.
- `ApprovalExecutionClaimIT`: **6/6** — atomic cross-replica execution claim,
  expired-lease takeover (backdated lease), attempt bound → terminal error.
- Aggregate: `Tests run: 19, Failures: 0, Errors: 0` + `BUILD SUCCESS`.

Fixes made during verification (bugs this run exposed):

- `JdbcIdempotencyStore`: `organization_id` is `uuid` in V8 → all org
  bindings now `CAST(? AS uuid)` (CLAIM/TAKEOVER/SELECT/COMPLETE/FAIL) plus
  the IT `attemptCount` helper; instant params bound as `OffsetDateTime`
  (pgjdbc-native) instead of `SqlParameterValue`.
- `ApprovalExecutionClaimIT.expiredLeaseAllowsTakeover`: test bug — lease was
  still in the future; backdated `past` (now-20 min → lease = now-5 min).
- `DistributedLockIT.tenantIsolation`: self-contradictory assertion corrected
  (same-org re-claim denied + independent org record present).
- `DistributedLockIT.failClosedWhenRedisUnreachable`: `Redisson.create()`
  throws *synchronously* when the endpoint refuses connections, so the test
  now builds against a live client, `shutdown()`s it, and verifies the
  fail-closed wrapper + healthy-manager-keeps-working.
- `realOutageBlocksThenRecovers`: post-recovery acquire converted to a bounded
  retry loop (the single-shot acquire raced the Redisson pool reconnect).

### 2. pgverify IT suite (real PostgreSQL, Flyway V1..V8, ddl-auto validate) — GREEN

Run: `mvn -pl netherite-app test -Dtest=EnterpriseRuntimeIT,PostgresApprovalPersistenceIT,PostgresUpgradePathIT`

- `EnterpriseRuntimeIT`: **6/6** (updated F-1 → exactly `V1..V8`, 8 rows).
- `PostgresApprovalPersistenceIT`: **6/6** (updated exact-`V1..V8` + 8
  non-zero checksums; context boots against V8 with Hibernate validate).
- `PostgresUpgradePathIT`: **3/3** —
  - V6 → V7 upgrade over historical rows + idempotent re-run (reset schema so
    ordering is irrelevant; re-run target pinned to `7`),
  - **new V7 → V8 upgrade test**: `idempotency_records` shape (uuid +
    varchar PK, text cached_response, integer attempt_count, timestamptz
    expires, `ck_idempotency_state`), five execution-claim columns on
    `approval_requests` (booleans/integer NOT NULL, timestamptz, varchar),
    `idx_approvals_execution_claim`, historical row untouched (version=0,
    PENDING), re-run at latest = no-op,
  - checksum determinism: fresh schema migrates exactly 8, no zero checksums.

### 3. Default-profile runtime boot (real PG, Flyway on `netherite`) — GREEN

- Flyway: `Migrating schema "public" to version "8 - distributed state"` →
  `Successfully applied 1 migration … now at version v8`; Hibernate
  `ddl-auto: validate` passed (`EntityManagerFactory` initialized).
- Health: `/actuator/health` `200 {"status":"UP","groups":["liveness","readiness"]}`.

### 4. Redis-outage semantics (production JVM) — GREEN

- Boot with Redis **down**: application **starts** (log: `Started
  NetheriteApplication`), Tomcat serves, Flyway/Hibernate unaffected — the
  `@Lazy` bean + `@Lazy` injection-point fix is what makes this possible
  (without it, startup aborts at `redissonClient` — reproduced and fixed).
- While down: each scheduler tick logs
  `Redis coordination dependency unreachable during lock … failing closed`
  (`NetheriteException: Distributed coordination dependency (Redis)
  unavailable`) — process stays up, no in-memory fallback, no crash.
- `/actuator/health` reports `503 {"status":"DOWN"}` (coordination indicator
  DOWN, liveness probes still visible).
- Redis restarted: **no application restart needed** — next tick logs
  `connections initialized for localhost/127.0.0.1:6379`, locks resume,
  health flips back to `200 UP` on the next request.

### 5. Two-process E2E (nodes A+B, shared PG + shared Redis) — GREEN

- Node A `node-a` :8080 (PID …28268) and node B `node-b` :8081 (PID …28244)
  booted from the same jar against the same `netherite` DB + same Redis;
  both `200 UP` with working liveness/readiness groups.
- Coordinated schedulers: both nodes tick every 60 s on the same Redis lock
  keys (`netherite:lock:approval-expiration:scheduler`,
  `netherite:lock:health-check:scheduler`) — acquire → run → ownership-safe
  release cycle observed per node (`Lock acquire(watchdog) … -> true` then
  `Released lock …`); with a non-overlapping tick window only one node runs
  each job per cycle (mutual-exclusion semantics themselves asserted by the
  19 ITs above, including multi-client exclusivity and lease takeover).
- An externally-held lock key made **both** nodes step aside on every tick —
  instead of double-running they failed closed on the unexpected lock state
  (`WRONGTYPE … failing closed`) and auto-resumed once the key expired —
  demonstrating no silent fallback under any coordination anomaly.
- Redis outage on the pair: **both** nodes logged fail-closed every tick
  (A at :49/:50, B at :49/:50) while Redis was down; both recovered on the
  first tick after Redis returned (`Lock acquire … -> true`), both health
  endpoints back to `200` — no node restarts.
- Node restarts exercised repeatedly (both nodes were killed and relaunched
  several times during this scenario); each restart rejoined and resumed
  coordinating against the shared state.

### 6. Regression — GREEN

- Backend unit suite: `Tests run: 358, Failures: 0, Errors: 0` +
  `BUILD SUCCESS` (includes the WS2 SSO/security suites: OidcValidation 18,
  OwnerAuthentication 33, ManagementEndpointAuthentication 12,
  OrganizationDiscovery 12).
- Frontend: `npm run build` (`tsc && vite build`) — 1593 modules, built in
  3.44 s.

### 7. Verification-harness note (not product code)

The long-lived `redis-server` spawned during outage tests holds inherited
console/pipe handles, which kept PowerShell/`Start-Process -Wait` pipelines
open after Maven finished. All verification runs therefore use a detached
launch (`Start-Process … /c mvn > log 2>&1`) and poll the log file for
`BUILD SUCCESS|FAILURE`. This affects only how tests are invoked in this
environment, not the product.