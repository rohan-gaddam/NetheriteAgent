# WORKSTREAM 4 — PRODUCTION DEPLOYMENT & INFRASTRUCTURE

> Status: **IN PROGRESS** (Phases 0–2 complete; Phases 8/10/12 live-verified; JVM regression
> green (360 unit + 19 distributed + 12 pgverify + 4 real-OPA + frontend build); **final
> verdict gated on container verification — Docker engine still pending**)
>
> Scope: build and verify the complete production deployment/infrastructure architecture for
> NetheriteAgent — backend replicas, PostgreSQL, Redis, Open Policy Agent, the React control
> plane, TLS edge, load balancing, secrets, health/readiness, graceful shutdown, schema
> migrations, upgrades and rollback — and prove every acceptance requirement with evidence.
>
> Deployment model (decided): **Docker Compose (production)** with **Caddy** terminating TLS.
> No Kubernetes: this repository has no cluster and a Compose architecture genuinely supports
> the multi-replica control plane.

---

## Phase 0 — Repository Infrastructure & Deployment Baseline

Inspection results (evidence attached; every claim below was verified by direct file reads,
globs, or grep during WS4 Phase 0):

| Concern | Current state | Verdict |
|---|---|---|
| Existing compose | `infrastructure/docker/docker-compose.yml` (dev-grade, `version: '3.8'`): postgres:16-alpine (default pw `netherite_secure_pass`), redis:7-alpine (no auth), opa:latest (unpinned, `0.0.0.0:8181`), all ports published, no app/frontend services, no resource limits, no healthcheck on OPA consumers | Replaced |
| Dockerfiles | none anywhere (glob verified) | Created |
| K8s / Helm | none | N/A (Compose model) |
| CI/CD | none (`.github` absent) | Out of scope; documented as follow-up |
| TLS / reverse proxy | none | Caddy added |
| Secrets hygiene | repo-secret scan clean since WS2; passwords only in dev compose / test fixtures; `.gitignore` covers `.env` | Preserved |
| Flyway schema | V1..V8 applied; `ddl-auto: validate`; `flyway.enabled: ${FLYWAY_ENABLED:true}` | Preserved, single-migrator mode added |
| Fail-closed OPA | `PolicyEvaluationService` maps infra failure → `ERR_SYSTEM_UNAVAILABLE` (503), never ALLOW (netherite-app `policy/service/PolicyEvaluationService.java`, `policy/client/OpaPolicyEngineClientImpl.java`) | Verified working |
| Fail-closed coordination | `DistributedCoordinationHealthIndicator` (Redis) + per-tick fail-closed 503 on lock/idempotency; boot-with-Redis-down + auto-recovery proven in WS3 | Preserved |
| Graceful shutdown | `server.shutdown: graceful` pre-existing; `spring.lifecycle.timeout-per-shutdown-phase` now `${SHUTDOWN_TIMEOUT_SECONDS:30}s`; `AsyncConfig` bounded pool (4/8/200) drains ≤30s on shutdown | Extended |
| Production guardrails | `RuntimeConfigurationValidator` (`!test` profile): rejects H2, blank owner, missing DB_PASSWORD / HTTPS OIDC when `netherite.environment=production` | Verified (uses `NETHERITE_ENV`) |
| Health/readiness | probes enabled, `show-details: when_authorized`; **NEW** liveness/readiness groups + OpaHealthIndicator | Added |
| NODE_ID for replicas | `NodeIdentity` falls back to random `node-<uuid>` per process when `NODE_ID` unset | Replica-safe |

---

## Phase 1 — Gap Analysis

Classification per acceptance taxonomy: **A** = already correct & verified; **B** = implemented
now & verified; **C** = implemented now, verify pending Docker engine; **D** = gap exists,
mitigated/documented; **E** = blocking gap requiring work (any remaining E at final report ⇒
`WORKSTREAM 4 BLOCKED`).

| # | Requirement | Class | Notes / evidence |
|---|---|---|---|
| 1 | Production topology exists & is deployable | A/C | Phase 2 below; container deploy pending Docker |
| 2 | Application containerizable (Dockerfile) | C | `backend/netherite-app/Dockerfile` (multi-stage, non-root) |
| 3 | Edge/frontend containerizable (Dockerfile + TLS) | C | `frontend/Dockerfile` (Node build → Caddy) |
| 4 | Secrets separated from code | A | `.env` git-ignored; compose `${VAR:?...}` fail-fast; `.env.example` placeholder-only |
| 5 | No secret in images/logs/health/metrics/errors | A | none baked; `.dockerignore`; scan clean; redaction in place (WS2) |
| 6 | Internal network for PG/Redis/OPA (no publish) | C | compose: only frontend publishes 80/443 |
| 7 | TLS/HTTPS termination | C | Caddy (internal CA local, ACME prod); `SESSION_COOKIE_SECURE=true` |
| 8 | Load balancing across replicas | C | Caddy `reverse_proxy app:8080` (DNS multi-A round-robin) |
| 9 | Horizontal scaling | C | `docker compose up -d --scale app=2`; NodeIdentity unique per process |
| 10 | Liveness vs readiness distinct | B | liveness = process alive (200 even with Redis/OPA down); readiness = `readinessState,db` |
| 11 | Readiness not failed by optional Redis (fail-closed holds) | B | readiness group excludes coordination + OPA (they remain in overall `/actuator/health`) |
| 12 | PG down ⇒ never report healthy | A | ready=DOWN via `db` indicator; DB pool connection-timeout 20s |
| 13 | OPA down ⇒ fail closed, never default-allow | A | `PolicyEvaluationService` 503; OpaHealthIndicator DOWN |
| 14 | Redis no eviction of coordination state | C | `--maxmemory 256mb --maxmemory-policy noeviction`; AOF everysec |
| 15 | Resource limits all services | C | `deploy.resources.limits` per service |
| 16 | Container security (non-root, pinned images) | C | app runs `USER 10001`; images pinned (postgres:16-alpine, redis:7.4-alpine, opa:0.68.0, caddy:2.8-alpine, temurin:21-jre-jammy) |
| 17 | One migration process among replicas | C | migrate one-shot service (`--netherite.migrate-only=true`, FLYWAY_ENABLED=false on app) |
| 18 | Existing migrations never changed | A | V1..V8 untouched; new schema only V9+ |
| 19 | Graceful shutdown drains work / releases locks | B | graceful + 30s drain; WS3 already proved no abandoned locks on node kill during execution |
| 20 | Upgrade/rollback runbook | pending | Phase 18 (expand/contract; no rollback claim where unsafe) |
| 21 | Clean-deploy verification (24 items) | pending | Phase 16 (needs Docker) |
| 22 | Failure testing | pending | Phase 17 (needs Docker) |
| 23 | Full regression | pending | Phase 20 |
| 24 | `docs/production-deployment.md` (23 sections) | pending | Phase 19 |

---

## Phase 2 — Production Topology

```
                    ┌─────────────────────────────────────────────────┐
                    │   frontend  (Caddy, published :80 :443)          │
                    │   SPA dist  +  TLS + LB /v1,/actuator → app      │
                    └───────────────┬─────────────────────────────────┘
                                    │  netherite_net (bridge)
                    ┌───────────────▼─────────────────────────────────┐
                    │      app (replicas, NODE_ID unique each)        │
                    │      FLYWAY_ENABLED=false, fail-closed          │
                    └───┬────────────────────────┬─────────────────┬──┘
                        │                        │                 │
                ┌───────▼───────┐        ┌───────▼───────┐  ┌──────▼──────┐
                │  postgres 16  │        │    redis 7    │  │     opa     │
                │  internal     │        │  internal,    │  │  internal,  │
                │               │        │  AOF + noevict│  │  pinned, ro │
                └───────────────┘        └───────────────┘  └─────────────┘
                schema V1..V8               coordination keys      policies
                (migrate one-shot)          (locks/idempotency)    (fail closed)
```

Deployment steps (also in `docs/production-deployment.md`):
1. `cp .env.example .env` and set strong secrets (never commit).
2. `docker compose build` — app image (multi-stage Java 21, non-root) + frontend image (Caddy).
3. `docker compose run --rm migrate` — the single Flyway process.
4. `docker compose up -d --scale app=2` — replicas boot with Flyway disabled and join Redis coordination.

---

## Phase status ledger

| Phase | Status |
|---|---|
| 0 Baseline inspection | DONE |
| 1 Gap analysis | DONE |
| 2 Topology | DONE (above) |
| 3 Containerization (Dockerfiles) | AUTHORED — verify pending Docker engine |
| 4 Config & secrets | AUTHORED — verify pending |
| 5 Network security | AUTHORED — verify pending |
| 6 TLS/HTTPS (Caddy) | AUTHORED — verify pending |
| 7 LB & scaling | AUTHORED — verify pending |
| 8 Health/readiness | CODE DONE + **JVM VERIFIED** (matrix below) |
| 9 Graceful shutdown | CODE DONE — final proof pending container `docker stop` (Windows JVM harness has no clean SIGTERM) |
| 10 DB deployment (single migrator) | CODE DONE + **JVM VERIFIED** (fresh/idempotent runs, exit codes, checksum identity) |
| 11 Redis deployment | AUTHORED — verify pending |
| 12 OPA deployment | **VERIFIED LIVE** (client-contract IT 4/4; decision mapping + fail-closed) |
| 13 Resource limits | AUTHORED — verify pending |
| 14 Container security | AUTHORED — verify pending |
| 15 Deployment artifacts | AUTHORED — verify pending |
| 16 Clean-deploy verification (24 items) | BLOCKED: Docker engine absent on this host |
| 17 Failure testing | BLOCKED: Docker engine absent |
| 18 Migration/upgrade/rollback | DONE (analysis above; container drill pending Phases 16/17) |
| 19 production-deployment.md | DONE (23 sections; container-gated items marked PENDING DOCKER) |
| 20 Final regression + acceptance report | JVM regression DONE (below); final verdict pending Phases 16/17 |

**Blocker**: `docker`, `wsl`, `podman` all absent; only failed-install leftovers under
`AppData\Local\Programs\DockerDesktop\` and `AppData\Local\Docker`. User is installing
Docker Desktop. All non-container verification proceeds meanwhile on the existing JVM
harness (Redis :6379, PostgreSQL :5433, OPA :8181, Keycloak :8180).

---

## JVM-harness live evidence (Phases 8, 10, 12)

Node A (node-a, replicas-safe `NODE_ID`): jar built from the hardened
`application.yml` (liveness/readiness groups, `OpaHealthIndicator`, OPA
`/v1/data/netherite/authz/evaluation_output`), default profile, PG:5433 `netherite`,
Redis:6379, OPA:8181, `MANAGEMENT_ENDPOINT_HEALTH_SHOW_DETAILS=always`.

### Phase 8 — health/readiness matrix (node-a)

| Scenario | `/actuator/health` | liveness | readiness | db | opa | distributedCoordination |
|---|---|---|---|---|---|---|
| Baseline | UP | UP | UP | UP | UP | UP |
| **Redis down** | DOWN (503) | UP | **UP** | UP | UP | DOWN |
| Redis restored | UP | UP | UP | UP | UP | UP |
| **OPA down** | DOWN (503) | UP | **UP** | UP | DOWN | UP |
| OPA restored | UP | UP | UP | UP | UP | UP |
| **PG down** | DOWN (503) | UP | **DOWN** | DOWN | UP | UP |
| PG restored | UP | UP | UP | UP | UP | UP |

Readiness excludes the optional Redis + OPA (fail-closed still holds), so traffic is not
blackholed while the node stays out-of-rotation on PG loss. `overall` still reports every
dependency so operators see Redis/OPA degradation. PG down ⇒ node is never healthy
(readiness + overall DOWN). Detail bytes: `dc.details.node=ws4-node-a`,
`opa.details.dependency=opa`.

### Phase 10 — migration service (single migrator)

`java -jar netherite-app-0.1.0-SNAPSHOT.jar --netherite.migrate-only=true` with
`FLYWAY_ENABLED=true`:

| Run | Database | Exit | Flyway result |
|---|---|---|---|
| Fresh | `netherite_ws4b` (new) | 0 | applies 8 migrations → v8 |
| Idempotent | `netherite_ws4` (already v8) | 0 | `initialVersion=8, migrationsExecuted=0, success=true` |
| Validate | all DBs | 0 | "Successfully validated 8 migrations" |

Web server is disabled in migrate-only mode (`MigrateOnlyWebServerConfig`, Tomcat on
port -1) so the job never opens a port during rollouts.

Flyway checksums are byte-identical across `netherite`, `netherite_ws4`,
`netherite_ws4b` for V1..V8 (`1|-1414020610, 2|1306395696, 3|-2078596908,
4|-147957356, 5|1283835699, 6|-1107567546, 7|275619373, 8|-1551006978`) — V1..V8
unchanged, reproducible fresh, safe to deploy next to existing data.

### Phase 12 — OPA decision mapping (live 0.68.0, `infrastructure/docker/opa/policies`)

RealOpaContractIT (`netherite.realopa-it=true`) against the pinned OPA: 4/4 PASS —
low risk → ALLOW, high risk → HUMAN_APPROVAL, missing/blank resource → DENY
(hardened `base.rego`), unreachable OPA → `successful=false` (fail closed, never ALLOW).
`/v1/data/netherite/authz/evaluation_output` verified directly (risk10 → ALLOW,
risk70 → HUMAN_APPROVAL, empty resource → DENY).
---

## Phase 18 � Migration / Upgrade / Rollback (expand�contract)


Schema discipline (enforced during WS4, verified above in Phase 10):
- V1..V8 are frozen and byte-identical across DBs (checksums match). All future changes ship as NEW
  migrations **V9+ only**, and must be written in expand/contract form: additive `ALTER ... ADD COLUMN`
  with nullable/defaulted columns and backfilled data first (expand), usage gate in the code, then a
  later maintenance release performs the optional contract step (drop/reify) with an explicit migration.
- Every release re-runs `validate` (the migrate service validates all 8 migrations in ~200ms; the runner
  calls `migrate()` which validates first). A migration that was edited in place is rejected at boot � the
  old app will refuse to start rather than silently diverge.

### Upgrade procedure (zero- or near-zero-downtime)

1. **Backup first**: `pg_dump -Fc netherite` to a dated archive (full restore path below). Redis is
   coordination-only (locks TTL-bounded, idempotency keys TTL-bounded, leader heartbeats self-healing);
   its state is rebuildable and needs no dump.
2. **Publish the new image** without touching the running stack (`docker build` + tag with a monotonic ref).
3. **Migrate the schema once**: `docker compose run --rm migrate` using the NEW app image. Flyway applies
   V9+ inside a single transaction (PostgreSQL transactional DDL). App replicas are untouched � they keep
   serving on the old schema, which is still valid (expand phase is additive; `ddl-auto: validate` passes).
   The migrate container exits 0 only when the schema is at the target version with success=true.
4. **Roll the replicas**: `docker compose up -d --scale app=2 --build` recreates app containers. Each
   `docker stop` sends SIGTERM ? Spring graceful shutdown drains in-flight requests up to
   `SHUTDOWN_TIMEOUT_SECONDS:30`, releases locks, and flushes the state flush-pool before exit. Caddy
   terminates TLS and fronts `/v1/*` + `/actuator/*`; its reverse proxy retry policy absorbs the brief
   per-replica blip. For a rolling (staggered) pattern under Compose, downscale to 1, recreate, then
   upscale back to 2 � documented instead of claiming K8s-grade zero-downtime.
5. **Verify**: both replicas report readiness=UP, overall=UP, schema at latest version, and a smoke
   decision evaluates through OPA (ALLOW/HUMAN_APPROVAL/DENY per risk) on BOTH replicas; frontend serves
   the new build behind TLS.

### Rollback procedure (honest constraints)

- **Application rollback is safe and instant**: redeploy the previous pinned image ref
  (`docker compose up -d --scale app=2` with the old tag). The old app validates against the NEW schema;
  because V9+ must be additive, `ddl-auto: validate` still passes, so rollout back is non-breaking.
- **Schema rollback is NOT zero-downtime and ONLY via restore**: Flyway has no down-migrations in this
  project. If an upgrade shipped a destructive/contract V9+ that must be reverted, the only correct path is
  `pg_restore` from the backup in step 1 (requires a maintenance window). If the migration was purely
  additive it is left in place (removing an applied migration would corrupt history � never do that).
- **Redis on rollback**: coordination is ephemeral � flush it (`redis-cli FLUSHALL` is safe; keys TTL
  anyway) so locks/idempotency state cannot pin old-version assumptions.
- Consequence: the project makes **no zero-downtime schema-rollback claim**; app-image rollback is the
  supported fast path. This is the honest, defensible position for the deploy model.

### Why this satisfies "never modify an applied migration"

`flyway_schema_history.checksum` for V1..V8 is identical on the live DB and two freshly-migrated DBs
(see Phase 10 table). Any attempt to edit an applied V<9 migration produces a checksum mismatch at the next
`migrate`/`validate` run and the migrate service exits non-zero, aborting deployment before replicas start.

---

## Phase 20 � regression results (2026-09-24)

| Suite | Command | Result |
|---|---|---|
| Full backend *Test (unit + IT via surefire, includes new OpaHealthIndicatorTest 2/2) | `mvn test` (reactor, infra live) | 360 tests, 0 failures, 0 errors - BUILD SUCCESS (netherite-app ~2:09) |
| Distributed ITs | `mvn test -pl netherite-app -Dtest=ApprovalExecutionClaimIT,DistributedIdempotencyIT,DistributedLockIT` | 19/19 PASS (6+7+6) |
| pgverify ITs | `mvn test -pl netherite-app -Dtest=PostgresApprovalPersistenceIT,EnterpriseRuntimeIT` | 12/12 PASS (6+6) |
| Real-OPA contract | `mvn test -pl netherite-app -Dtest=RealOpaContractIT -Dnetherite.realopa-it=true` | 4/4 PASS (ALLOW/HUMAN_APPROVAL/DENY/fail-closed) |
| Frontend | `tsc && vite build` (fresh `npm ci` from lockfile) | SUCCESS - 1593 modules, dist/ rebuilt (vite 5.4.21) |
| Live 2-node JVM deploy | node-a :8080 + node-b :8081, shared PG/Redis/OPA | Both UP; health + coordination details verified |

Notes: `npm audit` reports 2 advisories (1 moderate, 1 high) in the lockfile's transitive dev deps - not a WS4 acceptance item, recorded for future triage. Two stale WS2/WS3 vite dev servers were found holding `node_modules` (esbuild/rollup) and stopped during the frontend rebuild.

---

## Phase 21 - containerized secret-handling verification (P1..P12) (2026-09-25)

Automated live proof. Suite: `infrastructure/docker/scripts/verify-secret-handling.ps1` (Win)
- P1..P12 against the running compose stack; engine at
`C:\Users\LEGION\AppData\Local\Programs\DockerDesktop\resources\bin\docker.exe`.
- Secrets: `db_password`/`redis_password` 64-hex from `generate-secrets.ps1`;
  `owner_password_hash` = BCrypt `$2a$10$CQmhh...` (60 bytes, no BOM/newline) - the same value
  delivered to app replicas at runtime.

### Setup fixes discovered while hardening the harness

1. **OPA healthcheck**: `openpolicyagent/opa:0.68.0` is distroless (no shell/wget), so its
   `wget` healthcheck could never pass and `depends_on: service_healthy` permanently blocked
   app startup. Switched to **`openpolicyagent/opa:0.68.0-debug`** (busybox wget, runs
   non-root uid/gid 1000). Updated in `docker-compose.yml` + `docs/production-deployment.md`.
2. **Caddy auto_https**: with `SITE_ADDRESS=localhost`, plain `http://localhost` gets a 308
   → https, defeating in-container/CLI verification over plain HTTP. The suite sets
   `$env:SITE_ADDRESS = ':80'` (OS env wins over `.env`) so the verification edge runs plain
   HTTP; production uses `SITE_ADDRESS=<fqdn>` for automatic HTTPS.
3. **PowerShell 5.1 native-arg mangling**: `curl.exe -d '{"a":"b"}'` had its embedded quotes
   stripped, producing `{a:b}` JSON 500s; `.NET HttpWebRequest` pooled keep-alive went stale
   after container churn (truncated body). Final P5 transport: fresh `curl.exe -s -o NUL -w
   '%{http_code}' --data-binary "@file"` process per attempt with a 120s retry loop. Correct
   credentials → 200, wrong password → 401 through Caddy.
4. **Side-effect-free fail-fast proof**: P3 runs against a throwaway `_p3tmp` compose project
   (owner secret deliberately missing/empty) so bind-mount creation side effects can never
   touch the real `secrets/` tree.
5. **Recovered owner secret after accidental corruption**: an earlier P3 draft moved the real
   secret files, letting Docker auto-create `secrets/owner_password_hash` as a directory tree.
   The intact 60-byte BCrypt was recovered from
   `secrets\owner_password_hash\owner_password_hash.bak\owner_password_hash.bak` and restored
   as a file (mount-side-effect guard added to the preflight: each path must exist and NOT be
   a directory).

### Result (final run)

```
P1  compose config exposes no secret values ........ PASS
P2  secrets not baked into the app image .......... PASS
P3  missing/empty secret aborts startup .......... PASS
P4  full stack deploys healthy (2 app replicas) .. PASS
P5  owner login 200 / bad password 401 (BCrypt) .. PASS
P6  Redis requires AUTH + authenticates .......... PASS
P7  PostgreSQL auth + readiness UP ............... PASS
P8  secrets byte-identical across replicas+host .. PASS
P9  no secret value leaks into container logs .... PASS
P10 graceful stop exit 0 + shutdown log line ..... PASS
P11 dependency failure/recovery (redis/pg/opa) ... PASS
P12 Caddy edge routes /v1 /actuator + SPA ........ PASS
Steps executed: 12 / passed: 12 / failed: 0
ALL SECRET-HANDLING CHECKS PASSED
```

Live stack state after the run: postgres/redis/opa healthy, migrate completed
(`service_completed_successfully`), app x2 healthy (non-root), frontend healthy, edge serves
the SPA. WS4 JVM nodes restarted on the rebuilt jar: node-a :8080 and node-b :8081 both report
overall UP (db/distributedCoordination/opa/liveness/readiness) with `dc.details.node` =
`ws4-node-a` / `ws4-node-b`; they now fail fast if `OWNER_PASSWORD_HASH` is absent.

### Final verdict

**WORKSTREAM 4 SECRET HANDLING FIX — COMPLETE AND VERIFIED**
