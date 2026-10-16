# NetheriteAgent — Production Deployment

Deployment model: **Docker Compose on a single host, Caddy edge**. No Kubernetes (this
repository has no cluster; Compose genuinely multi-replicas the control plane).

Status refers to the WS4 verification ledger (`docs/workstream-4-production-deployment.md`).
JVM harness evidence (health matrix, migration service, OPA contract, 2-node cross-check) is
recorded there; the **containerized** secret-handling items are proven by the automated P1..P13
suite in `infrastructure/docker/scripts/verify-secret-handling.ps1` (all 13 checks pass).

WS7 (deployment hardening, `docs/workstream-7-deployment-secrets-cicd.md` and
`docs/ci-cd.md`) added: digest pinning of every base/deploy image, build-context
exclusion of secret material in the root `.dockerignore`, the GitHub Actions CI pipeline and a
CD deployment template. The verification items of that workstream are recorded in its ledger.

---

## 1. Architecture Overview

- **app** — Spring Boot 3.3.3 (Java 21) control plane; replicable (`NODE_ID` unique per
  process); serves REST (`/v1/*`) + Actuator (`/actuator/*`).
- **frontend** — React SPA behind **Caddy 2.8** (TLS termination, `/v1`, `/actuator` proxied to
  `app`).
- **postgres 16** — schema `netherite` (Flyway V1..V14, **additive only**; applied migrations
  are frozen by checksum and must never be edited).
- **redis 7** — coordination only: locks, idempotency, leader heartbeats. AOF `everysec`,
  `noeviction`; state rebuildable (TTL-bounded).
- **opa 0.68.0** — authz policies (read-only volume); app fails CLOSED when OPA is down.
- **migrate** — one-shot job (same app image) that applies Flyway migrations then exits 0.

Inbound public services: **frontend :80/:443 only**. PG/Redis/OPA live on an internal bridge
network and are never published.

Secrets are **file-based Docker Compose secrets** (never `.env` interpolation): the app entrypoint
copies `/run/secrets/*` into env and fails fast on any missing/empty secret, so a BCrypt `$`
can never be re-interpreted and a silently-empty secret can never boot the app.

## 2. Topology

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
                schema V1..V14              coordination keys      policies
                (migrate one-shot)          (locks/idempotency)    (fail closed)
```

## 3. Components & Images (pinned)

| Component | Image | Build | Runtime user |
|---|---|---|---|
| app + migrate | `temurin:21-jre-jammy` from `eclipse-temurin:21-jre-jammy` (multi-stage from `maven:3.9.9-eclipse-temurin-21`) | `backend/netherite-app/Dockerfile` | `10001` (non-root) |
| frontend | `caddy:2.8-alpine` (build stage `node:22-alpine`) | `frontend/Dockerfile` | Caddy default |
| postgres | `postgres:16-alpine` | — | postgres |
| redis | `redis:7.4-alpine` | — | redis |
| opa | `openpolicyagent/opa:0.68.0-debug` | — | opa |

All deploy-time images are **digest-pinned** (WS7) — exact `sha256:` references recorded in
`docker-compose.yml` / the Dockerfiles; the floating tags above exist only as human-readable
annotations for re-resolution. There is no `latest` anywhere. To bump an image, resolve its
digest first (`docker pull <tag> && docker image inspect --format '{{index .RepoDigests 0}}'
<tag>` on the target platform) and commit the new `@sha256:...` with the —ws7 run noted in the
ledger.

## 4. Prerequisites

- Docker engine (Compose v2). `docker compose version` must succeed.
- Volume space for `pgdata`/`redisdata`; the compose file sets memory/CPU limits per service.
- For real deployments: a DNS `A` record for `SITE_ADDRESS`, ports 80/443 reachable.
- For local/eval: `SITE_ADDRESS=localhost` uses Caddy's internal CA (browser warning appears).

## 5. Repository Layout (deployment artifacts)

```
infrastructure/
  docker/
    docker-compose.yml     # production stack (single source of truth)
    .env.example           # NON-secret variables only; secrets live as files
    secrets/               # git-ignored, real secrets (see §7) — provisioned by scripts/
    secrets.example/       # committed example/phony values (never real)
    scripts/
      generate-secrets.ps1 # Windows: writes the 5 file secrets (db/redis 64-hex + BCrypt owner hash)
      generate-secrets.sh  # Linux/macOS equivalent
      verify-secret-handling.ps1 # P1..P13 automated proof suite (see §10)
    opa/policies/          # OPA rego (base.rego entry point); mounted read-only
    opa/opa-entrypoint.sh  # writes authz_tokens.json from the opa_policy_token secret
                           # into a container-local tmpfs (/policies-data), then boots OPA
  caddy/
    Caddyfile              # TLS + SPA + /v1,/actuator reverse proxy + telemetry block
backend/netherite-app/Dockerfile
backend/netherite-app/docker-entrypoint.sh   # /run/secrets -> env, fail-fast
frontend/Dockerfile
.dockerignore              (root)  /  backend/.dockerignore    # root excludes **/secrets/, **/.env
.github/workflows/
  ci.yml                   # CI pipeline (see docs/ci-cd.md)
  cd-deploy.yml            # CD deployment template (environment-gated, never executed here)
docs/
  production-deployment.md              # this document
  ci-cd.md                              # CI/CD pipeline documentation (WS7)
  workstream-4-production-deployment.md # verification ledger & evidence
  workstream-7-deployment-secrets-cicd.md  # WS7 deployment-hardening ledger
```

## 6. Configuration — Non-Secret Env + File-Based Secrets

**Non-secret** configuration lives in `infrastructure/docker/.env` (copy of `.env.example`).
**Secrets never enter `.env`**: they are file-based Compose secrets under
`infrastructure/docker/secrets/` (git-ignored), written by:

```sh
cd infrastructure/docker
$env:OWNER_PASSWORD = '<owner-login-password>'; powershell -ExecutionPolicy Bypass -File scripts/generate-secrets.ps1   # Windows
OWNER_PASSWORD='<pw>' ./scripts/generate-secrets.sh                                                                  # Linux/macOS
```

The password is never passed as a command-line argument (process-list visible): the
generators read the `OWNER_PASSWORD` environment variable or a hidden
SecureString/TTY prompt. The generator writes fresh values each run (re-running rotates
them): `db_password`, `db_app_password`, `redis_password` and `opa_policy_token` as
random 64-hex files, `owner_password_hash` as a BCrypt hash of the given password
computed with the application's own `spring-security-crypto` (via `scripts/BcryptTool.java`,
which also reads the password from `OWNER_PASSWORD` only — never from `argv`), so the hash
is guaranteed to validate at login and requires no `.env` `$` escaping. It also writes an
**empty** `gemini_api_key` (valid for `LLM_PROVIDER=none`; a real key is required only when
enabling Gemini) and an **empty** `openrouter_api_key` (valid unless enabling OpenRouter).

| Secret file | Injected to | Purpose |
|---|---|---|
| `secrets/db_password` | postgres `POSTGRES_PASSWORD_FILE`; migrate/app `DB_PASSWORD` | Postgres bootstrap + admin auth |
| `secrets/db_app_password` | app `DB_PASSWORD` (least-privileged `netherite_app` role) | Runtime DB auth |
| `secrets/redis_password` | redis `$(cat /run/secrets/redis_password)`; app `REDIS_PASSWORD` | Redis `requirepass` + client auth |
| `secrets/opa_policy_token` | opa mounted authz token data; app `OPA_TOKEN` | OPA bearer auth on every decision/health/push call |
| `secrets/owner_password_hash` | app `OWNER_PASSWORD_HASH` | Owner login BCrypt (`$2a$`/`$2b$`) |
| `secrets/gemini_api_key` | app `GEMINI_API_KEY` (only read when `LLM_PROVIDER=gemini`) | Google Gemini interpretation (optional) |
| `secrets/openrouter_api_key` | app `OPENROUTER_API_KEY` (only read when `LLM_PROVIDER=openrouter`) | OpenRouter chat-completions interpretation (optional) |

`gemini_api_key` / `openrouter_api_key` are special: `docker-compose.yml` always declares them (their source files must
exist for `docker compose up`), but the app only reads the one matching `.env` `LLM_PROVIDER`.
The default (`none`) runs without a real key — the files are simply empty;
if the selected provider's key is missing/empty the app entrypoint aborts (fail closed).

`docker-compose.yml` declares the `secrets:` block and mounts each file read-only at
`/run/secrets/<name>`; only mount **paths** appear in `docker compose config` — the values are
never rendered. The app entrypoint (`docker-entrypoint.sh`) copies each file into the matching
env var and **fails fast** (exit 1, `FATAL` naming only) when a secret is missing/unreadable
or empty; compose additionally aborts at start if a secret's source file does not exist.

| Non-secret variable | Default | Purpose |
|---|---|---|
| `SITE_ADDRESS` | `localhost` | Public hostname for Caddy (ACME for real DNS; `localhost` eval) |
| `BACKEND_UPSTREAM` | `app:8080` | Caddy backend (compose DNS round-robin across replicas) |
| `DB_USER` / `DB_NAME` | `netherite_admin` / `netherite` | Postgres role/schema (app + postgres) |
| `OPA_URL` | `http://opa:8181` | Policy engine endpoint |
| `SESSION_COOKIE_SECURE` | `true` | Session cookie HTTPS-only |
| `NETHERITE_ENV` | `production` | Guardrails: production requires non-blank secrets incl. Redis pw |
| `FLYWAY_ENABLED` | migrate `true` / app `false` | Only the migrate job migrates |
| `SHUTDOWN_TIMEOUT_SECONDS` | `30` | Graceful-drain budget per shutdown phase |
| `APP_REPLICAS` | — | `--scale app=` value (see §15) |
| `OPA_TIMEOUT_MS` | `1000` | OPA call budget; failure ⇒ `ERR_SYSTEM_UNAVAILABLE` |
| `OPA_POLICY_PUSH_ENABLED` | `false` | Master switch for secure policy push. When `true` the control plane keeps the engine synchronized: tenant version activation pushes the Rego module (`PUT /v1/policies/<tenant-module>`), and the periodic reconciliation below restores engine state |
| `OPA_POLICY_PUSH_RECONCILE_INTERVAL_MS` | `60000` | Period of the reconciliation pass that re-installs the base module and every ACTIVE tenant module that vanished. OPA is **in-memory**: after an OPA restart the pushed modules are gone even though the database still lists those versions as ACTIVE. This pass re-pushes them automatically — no application restart needed for recovery |
| `OPA_POLICY_PUSH_MAX_REGO_BYTES` | `100000` | Rego source cap; a larger tenant policy is rejected (`ERR_POLICY_PUSH_REJECTED`) |
| `OPA_POLICY_PUSH_LOAD_BASE_IF_ABSENT` | `true` | Reconcile the embedded canonical base when no base module is present (a disk-mounted base satisfies the presence check, so no redundant push) |
| `OPA_POLICY_PUSH_BASE_POLICY_ID` | `netherite-authz-base` | Module id used when the control plane installs the base itself |

App-side placeholders (`DB_HOST`, `OWNER_PASSWORD_HASH`, …) come from the env map in
`application.yml` and are identical to the JVM harness.

## 7. Secrets Management

- Secrets are **host files** under `infrastructure/docker/secrets/` (git-ignored). `.env`
  holds non-secret config only; `.env.example` ships placeholders; `secrets.example/` ships
  phony example values — no real secret anywhere in the repo (scan clean since WS2).
- Compose mounts each secret as `/run/secrets/<name>`; only filenames/paths appear in
  `docker compose config` (P1-verified). No `.env` value interpolation of secrets anywhere, so
  a BCrypt `$2a$10$...` hash is delivered **byte-for-byte** (P5/P8-verified).
- Containers never bake secrets in: `.dockerignore` excludes build secrets; `COPY` layers carry
  only compiled artifacts; at runtime the entrypoint injects `/run/secrets` → env with a
  fail-fast guard — missing/unreadable/empty ⇒ `FATAL`, exit 1 (P3-verified).
- **OPA is bearer-authenticated end-to-end**: the engine boots with
  `--authentication=token --authorization=basic` and `opa-entrypoint.sh` materializes the
  `opa_policy_token` secret into `authz_tokens.json` on a container-local tmpfs
  (`/policies-data`) - it is never written to the host or the repository tree. The app
  receives the same token as `OPA_TOKEN` and authenticates every decision/health/push
  call; a missing or wrong token is refused with 401 and the control plane fails closed.
  The token value appears in no host-facing surface (compose config, `docker inspect`,
  container env probes, and logs are all scanned clean); only its file path is referenced.
- No secret appears in logs, health payloads, metrics, or errors (P9-verified + live nodes).
- Postgres data & Redis state live in named volumes (`pgdata`, `redisdata`), not in images.

## 8. Build

```sh
cd infrastructure/docker
docker compose build            # app (multi-stage, non-root) + frontend (Caddy)
docker compose config           # optional: renders/validates the resolved compose file
```

Contexts are relative to `infrastructure/docker`: app context `../../backend`, frontend
context `../..`. Containers never bake secrets in (the build has no secret inputs — values enter
only at run time via `/run/secrets` mounts). Backend builds with `mvn -q clean install
-DskipTests` (tests run in CI / regression; image build skips them for supply-chain speed) — the
resulting jar is identical to the regression-tested artifact.

## 9. Clean Installation (fresh deploy)

```sh
cd infrastructure/docker
cp .env.example .env                # non-secret config; edit SITE_ADDRESS etc.
$env:OWNER_PASSWORD = '<pw>'; powershell -ExecutionPolicy Bypass -File scripts/generate-secrets.ps1   # provision secrets/
docker compose run --rm migrate # single migration process; exit 0 = schema at target
docker compose up -d --scale app=2
docker compose ps               # all services Up / migrate exited 0
```

Order is guaranteed by `depends_on`: `app` requires `migrate` (`service_completed_successfully`)
plus healthy `redis`/`opa`; `frontend` requires healthy `app`. Replica boot with
`FLYWAY_ENABLED=false` cannot race the migrator.

## 10. Post-Deployment Verification Checklist (24 items) — Docker-verified (WS4 P1..P12)

Automated proof: `powershell -ExecutionPolicy Bypass -File infrastructure/docker/scripts/verify-secret-handling.ps1`
runs P1..P13 (compose-leak scan, image-layer scan, missing/empty-secret fail-fast, full-stack
deploy health, owner login 200 + wrong-password 401 through Caddy, Redis AUTH, PG auth +
readiness, byte-identical secrets across replicas/host, log redaction, graceful stop exit 0/143,
Redis/PG/OPA stop+restore cycles, edge routing of `/v1` `/actuator` and the SPA, and the
secret-reference contract). All 13 checks
passed on the final run; the checklist below maps to them and the WS4 ledger. Batch F (WS5)
extended these with the live panel gates F1–F11 (TLS floor, edge headers single-emission,
management-path closure, non-root identities + capability audit, cross-bridge isolation,
redis non-root AOF persistence, actuator origin gate) — see
`workstream-5-enterprise-security-hardening.md`.

1. `docker compose ps` — all services Up, `migrate` exited 0, restart policy sane.
2. Only `frontend` publishes host ports (80/443).
3. `netherite_net` — PG/Redis/OPA have no published ports.
4. `curl -k https://SITE_ADDRESS/` serves the SPA (200, index.html).
5. `/v1/*` and `/actuator/*` proxy to app; unknown `/v1` routes 404 from app, gzip on.
6. Caddy telemetry paths (`/telemetry`, `/.local`, `/.well-known` non-ACME) return 404.
7. `https://host/actuator/health` → overall UP; liveness UP; readiness UP (both replicas).
8. `readiness` returns DOWN after PG container stop; liveness still UP.
9. `overall` DOWN when Redis stopped; readiness stays UP (optional Redis, fail-closed holds).
10. `overall` DOWN when OPA stopped; readiness stays UP (fail-closed: decision returns 503).
11. Decision evaluation: low-risk ALLOW, high-risk HUMAN_APPROVAL, missing-resource DENY via OPA.
12. Schema history at **v13** (`flyway_schema_history`), no pending migrations, validate passed.
13. App replicas show unique `dc.details.node` in health detail (node identity).
14. `flyway valid` / migrate idempotency: re-run migrate → `migrationsExecuted=0`, exit 0.
15. Redis `CONFIG GET maxmemory-policy` → `noeviction`; `appendonly yes` (AOF).
16. Redis `requirepass` set; app connects with the password (no AUTH errors in logs).
17. App pods run as non-root (image `USER 10001`); processes not pid 0.
18. Secret values do not appear in `docker compose config`, image layers (`docker history`),
    or container logs (P1/P2/P9).
19. Resource limits present (`docker inspect: HostConfig.Memory/NanoCpus`) for all services.
20. `SESSION_COOKIE_SECURE=true` → session cookie flagged Secure over TLS.
21. Both replicas serve decisions identically through Caddy (round-robin across replicas).
22. Frontend SPA talks to `/v1/*` on the same origin (no mixed-content).
23. `docker stop <app>` → graceful shutdown log line + clean exit; locks not abandoned
    (no stale lock rows after restart — see WS3 lock-proving mechanics).
24. Prometheus scrape target: `/actuator/prometheus` (exposed) — but **401 without an
    authenticated admin session**, even from inside the compose network (WS8 hardening), and
    **404 at the public edge** (`handle @closed`); all metrics carry the bounded
    `environment` tag (`production`) and no header/secret values (WS8, verified live).
25. No `.env`/`.env-example` secrets and no image-baked secrets (P1/P2); login, AUTH, PG auth,
    replica byte-identity, log redaction, graceful stop, dependency recovery and edge routing
    all green in one run (P4..P12).

## 11. Health, Liveness & Readiness

- `/actuator/health` (overall): aggregates all components — `db`, `diskSpace`,
  `distributedCoordination` (Redis), `opa`, `ping`, `livenessState`, `readinessState`.
- `/actuator/health/liveness`: `livenessState` + `ping` — stays UP as long as the process
  serves; does **not** wait on optional dependencies.
- `/actuator/health/readiness`: `readinessState` + `db` — PG loss ⇒ DOWN ⇒ node out of rotation.
- Redis/OPA loss ⇒ overall DOWN (signalled) but **readiness stays UP** (optional path,
  fail-closed preserved). Verified matrix in the WS4 ledger (Phase 8).
- `show-details` is `when_authorized` by default; nodes use
  `MANAGEMENT_ENDPOINT_HEALTH_SHOW_DETAILS=always` for opaque infra probes.
- Container/edge health checks must use `curl -fsSk https://localhost/healthz`: busybox wget
  cannot follow Caddy's HTTP→HTTPS 308 redirect nor skip its self-signed (eval) cert, and a
  compose `healthcheck` overrides the image `HEALTHCHECK`.

## 12. Metrics & Monitoring

- Spring Boot Actuator `health` and `info` are the only actuator endpoints reachable through
  the edge: `health` is public, `info` requires an admin session
  (`ActuatorAccessFilter`, WS5 Batch F). `metrics`/`prometheus` are **404 at the edge**
  (`handle @closed`) and — because a `Secure` session cookie cannot ride a cleartext origin
  channel — also **401 without an authenticated admin session when scraped from inside the
  compose network** (`ERR_AUTHENTICATION_REQUIRED`, verified live 2026-10-10). An operator
  who wants real scraping can reach `http://app:8080/actuator/prometheus` with the admin
  session of the scrape user; the endpoint is left exposed (not "blocked by SAML") by design,
  gated solely by the session tier.
- WS8 metric hygiene (verified by `ObservabilityIntegrationTest`): the label set is bounded —
  counters/timers key on small enums (`rate_limit_mechanism`, `result`), mechanism names, or
  hex digest-hashes of the payload the label summarizes; **never** raw agent keys,
  identifiers, IPs or header values. A constant `management.metrics.tags.environment`
  tag (`development|test|production` from `NETHERITE_ENV`) separates environments in a shared
  scraper. `/actuator` (the links/discovery page) is **404** — `discovery.enabled=false`.
- Docker native: `docker compose logs -f --tail=200 app`; per-service resource limits alert
  when capped. A real monitoring pivot (Grafana/Prometheus) remains documented as a
  deploy-model follow-up, fully supported by the secured endpoint.

## 13. Logging, Correlation & Redaction

- **App** — JSON structured logs, one object per line (plain-text `logback` profile for local
  shell work; `structured` profile in compose/CI). Fixed keys: `ts, level, logger, thread,
  service (=netherite-agent-control-plane), environment, requestId, message`, plus event keys
  (`event=security.auth_failure`, ...). Startup errors render with an `exception` key (logback
  sanitizer turns raw netherite-agent structure into this safe shape).
- **Correlation** — `CorrelationIdFilter` (order −100) validates an inbound
  `X-Correlation-Id` (conservative charset, ≤128 chars, else a fresh UUID), keeps it in the
  SLF4J MDC (`requestId`) for the whole request and echoes it on **every** response including
  authz/rate-limit short-circuits. Caddy generates the identifier at the edge
  (`{http.request.uuid}`), forwards it downstream as `X-Correlation-Id`, and records it in its
  own access line as `uuid` — so one identifier joins edge, app and security-tier lines for
  the same request. Requests answered purely at the edge (blocked `/actuator/*`, static SPA)
  never touch the app and carry no id — documented empty-join limitation.
- **Edge** (`infrastructure/caddy/Caddyfile`, read-only at `infrastructure/caddy/Caddyfile`
  and baked into the frontend image): Caddy 2.8 access log to stdout — `format filter`
  deletes the default verbose `request/common_log/resp_headers/user_id/bytes_read` fields,
  `log_append` re-adds only `method, path, proto, remote_ip`, so the **path is logged without
  its query string** and **no request/response headers** are copied. `log_skip /healthz*` and
  `log_skip /actuator/health*` keep health-probe noise out of the stream.
- No secrets in logs: DB/Redis/OPA URLs print without credentials; request bodies (including
  login passwords) are never echoed — e.g. the login failure line is
  `Owner login rejected: invalid credentials` (verify: all four secret files plus the
  WS8 canary string are absent from every service log stream, 2026-10-10).
- Migration job logs `migrationsExecuted/success` only — no DDL contents.

## 14. TLS / HTTPS (Caddy)

`infrastructure/caddy/Caddyfile` (baked into the frontend image):
- Scheme-less host from `{$SITE_ADDRESS:localhost}`; automatic HTTPS: real DNS → ACME
  (Let's Encrypt), `localhost` → internal CA (eval only).
- TLS floor: `tls { protocols tls1.2 tls1.3 }` — exact-version probes confirm TLS1.1 is
  rejected and TLS1.2/1.3 accepted (verified live, WS5 Batch F).
- Site-level `header` block (single header owner — the origin only adds `Cache-Control`):
  `Strict-Transport-Security: max-age=31536000; includeSubDomains`,
  `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`,
  `X-Permitted-Cross-Domain-Policies: none`, `Referrer-Policy: no-referrer`, an exhaustive
  `Permissions-Policy`, and a strict CSP (`default-src 'self'`; the vite bundle has zero
  inline scripts). `server.server-header: ""` additionally suppresses the Tomcat banner
  on direct origin responses.
- Route structure (handle blocks are mutually exclusive, first match wins):
  1. `handle @closed` — 404 for `/actuator/*` management paths (metrics/prometheus/env/beans/
     configprops/mappings/loggers/logfile/heapdump/threaddump/shutdown/refresh/restart), bare
     `/actuator`, and scouting paths `/.git* /.env* /server-status* /wp-admin* /administrator*`.
  2. `handle @api` (`/v1/*`, `/actuator/*`) — proxied to `{$BACKEND_UPSTREAM:app:8080}` with
     `request_body { max_size 4MB }` and transport timeouts 5s/60s/60s. Caddy's
     reverse_proxy defaults overwrite `X-Forwarded-Proto/Host` and append `X-Forwarded-For`
     from the real TCP peer (client-injected values superseded); the app's RemoteIpValve
     trusts internal proxies only.
  3. `handle` — SPA with `/index.html` fallback.
- Session cookie is `Secure` via `SESSION_COOKIE_SECURE=true` (attributes
  `Secure; HttpOnly; SameSite=Lax` verified on both edge and origin responses).
- The edge runs as non-root caddy (uid 10001, `setcap -r` removes the image's file
  capabilities so cap-drop + no-new-privileges apply; `curl` probe image is used for
  health checks).

## 15. Load Balancing & Scaling

- Caddy resolves `app:8080` per request against the compose DNS name → multi-replica
  round-robin across the `app` containers (compose built-in discovery).
- Scale horizontally: `docker compose up -d --scale app=2` (or `app=3`, …). Each replica
  generates a unique `NODE_ID` if unset; coordination uses application-level locks so
  replicas never double-execute side effects (WS3-proven semantics).
- Replicas are interchangeable (stateless HTTP + shared PG/Redis/OPA).

## 16. Database Deployment & Migrations

- `postgres:16-alpine`, internal volume `pgdata`, superuser password from the `db_password`
  secret file (`POSTGRES_PASSWORD_FILE=/run/secrets/db_password`).
- **Single migrator**: `migrate` one-shot job (`--netherite.migrate-only=true`,
  `FLYWAY_ENABLED=true`). Replicas boot `FLYWAY_ENABLED=false`. Proven: fresh DB applies 14
  migrations (V1..V14) → exit 0; re-run → 0 migrations → exit 0; V1..V14 checksums
  byte-identical across DBs (pgverify IT `PostgresUpgradePathIT.checksumsStableAcrossRuns`,
  18/18 ITs green).
- Migration sources: `db/migration` (V1..V9, V12, V13, V14 — the main run-time source, applied
  by the app, migrate job and pgverify profile) and `db/postgresql` (V10, V11). WS6 added `V12`
  (designated authorized person) and `V13` (role removal). LLM normalization Step 2 added `V14`
  (`action_parameter_schemas` — registered action parameter schemas).
- `spring.jpa.hibernate.ddl-auto=validate` enforces Hibernate↔DDL agreement at boot.
- **Never edit an applied migration (V1..V14).** New schema only via new `V15+` files
  (expand/contract; see WS4 Phase 18).

## 17. Redis Coordination

- `redis:7.4-alpine`, internal, `requirepass` from the `redis_password` secret file
  (`command: redis-server --requirepass "$$(cat /run/secrets/redis_password)"`), `appendonly
  yes` (`everysec`), AOF volume `redisdata`, `--maxmemory 256mb --maxmemory-policy noeviction`.
- No eviction ⇒ distributed locks / idempotency keys are never dropped early.
- Coordination state is ephemeral by design (TTLs) — safe to FLUSH on maintenance.

## 18. OPA Policy (fail-closed)

- `openpolicyagent/opa:0.68.0-debug` (busybox wget for its healthcheck; non-root 1000), internal, read-only policy volume (`infrastructure/docker/
  opa/policies`), pinned — no mutable policy state.
- App calls `/v1/data/netherite/authz/evaluation_output`; returns
  `{decision, denyReasons, approvalReasons, allowReasons}`. Client parses exact fields;
  failures → `successful=false` → `ERR_SYSTEM_UNAVAILABLE` (503) — **never ALLOW**.
- Verified live: risk-low → ALLOW, risk-high → HUMAN_APPROVAL, blank resource → DENY,
  unreachable OPA → fail-closed (RealOpaContractIT 4/4; ledger Phase 12).

## 19. Graceful Shutdown & Restart Procedure

- `server.shutdown: graceful` with `spring.lifecycle.timeout-per-shutdown-phase:
  ${SHUTDOWN_TIMEOUT_SECONDS:30}s`. On SIGTERM (docker stop): stop accepting new requests,
  drain in-flight up to the budget, release distributed locks, flush the async state pool,
  then JVM exit 0.
- During phase 16/17: `docker stop <app>` must show the graceful-shutdown log line and no
  abandoned coordination keys (WS3 lock-proving mechanics re-run against the container).
  `[PENDING DOCKER]`
- Point-in-time restart: `docker compose restart app` (same config) is supported and safe.

## 20. Failure Modes & Handling

| Failure | Signal | Behavior |
|---|---|---|
| PG up/down/up | readiness DOWN→UP, overall DOWN→UP | node leaves/returns to rotation |
| Redis down | overall DOWN (dc), readiness UP | optional dependency; fail-closed preserved |
| OPA down | overall DOWN (opa), readiness UP | decisions 503 (fail closed) |
| Migrate failure | `migrate` exits non-zero | compose blocks app start (depends_on) |
| Replica crash | container restart / healthcheck | Caddy routes to surviving replicas |
| Disk full | `diskSpace` DOWN | overall DOWN; monitored |
| Pod capacity | deploy limits | cgroup throttling; metrics alert |

Live JVM matrix (baseline/Redis/OPA/PG each down+restore) — WS4 ledger Phase 8.

## 21. Backup & Restore

> **WS9**: the manual one-liner below is REPLACED by the scripted workflows in
> `docs/operations-and-recovery.md` (runbook). Use only those scripts;
> they are tested and validated end-to-end.

- **Backup**: `infrastructure/docker/scripts/netherite-backup.ps1` — dumps the
  compose `postgres` `netherite` DB (`-Fc -Z 9`), validates (magic, `pg_restore
  --list`, SHA-256), publishes `backups/netherite-pg-<ts>.dump` + `.json` manifest
  atomically, proposes retention (`-PruneOnly`), never deletes the newest point,
  fails hard on any error (temp `.dump.tmp` is cleaned on failure).
- **Restore**: `infrastructure/docker/scripts/netherite-restore.ps1` — validate
  only by default; `-PerformRestore` restores into a brand-new disposable
  postgres:16-alpine container (pre-created roles, verification of schema
  version/migration history/counts/append-only trigger + negative probe), then
  disposes it. **No parameter targets a live/named database — the live stack can
  never be a restore target.**
- **Redis**: coordination only, TTL-bounded; restore = start empty + optionally
  `redis-cli FLUSHALL` on rollback. No persistent business data. **Never snapshot-
  restore Redis** (stale-lock hazard; see `DistributedLockIT`).
- **Migration history** is redeemable from the dump; never copy `flyway_schema_history`
  rows manually.
- **Operational checks**: `netherite-ops-checks.ps1` (read-only: services healthy,
  postgres live, redis PONG + AOF ok, opa health, Caddy `/healthz`, backup freshness
  + integrity, no `.dump.tmp`, disk space). Run after any recovery.
- **Regression suite**: `netherite-ops-tests.ps1` (T01–T12 + redaction probe,
  disposable resources only).

## 22. Upgrade & Rollback

Full expand/contract runbook + rollback constraints (app-image rollback is instant and safe;
schema rollback is restore-only, NOT zero-downtime) → WS4 ledger Phase 18. Summary:
- Upgrade: backup → publish image → `docker compose run --rm migrate` (applies V9+ once) →
  roll replicas (graceful drain) → verify.
- Rollback: redeploy previous image tag; additive schema stays; if a destructive migration
  landed, restore PG from backup (downtime, clearly documented). No down-migrations exist.

## 23. Troubleshooting

- **migrate exits non-zero**: check `docker compose logs migrate` for the Flyway error; a
  checksum mismatch means someone edited an applied migration — do not bypass validate.
- **app never Starts**: `depends_on` waits on migrate success + redis/opa healthy;
  `docker compose logs app` shows the health-check curl probes.
- **readiness DOWN**: PG connection; check `docker compose logs postgres`.
- **overall DOWN but readiness UP**: Redis or OPA; check those logs; application continues
  operating (fail-closed on decisions).
- **TLS errors**: `SITE_ADDRESS` mismatch vs DNS; for local use `localhost`.
- **Secrets**: a missing secret **file** under `secrets/` aborts `docker compose` at startup
  (mount path not found); a missing/empty file at container start makes the entrypoint exit 1
  with `FATAL: required secret '<name>' ...` — the name/path only, never the value (P3).
- **Scale details**: `docker inspect <app> | grep -i -E "NanoCpus|Memory"` for limits;
  `NODE_ID` uniqueness via `dc.details.node` in health.

## 24. Rate limiting (WS5 Batch D)

- Distributed fixed-window limiting over the existing Redis broker (`netherite.rate-limit`,
  env-overridable; ledger & Ball-requirement mapping:
  `docs/workstream-5-enterprise-security-hardening.md`). The table is ordered first-match:
  per-route `buckets` declare `dimension` (IP, LOGIN_ACCOUNT, LOGIN_ACCOUNT_IP,
  AGENT_KEY_IDENTIFIER, ORGANIZATION, USER, AGENT), `limit`, `window-seconds`, and optional
  `fail-closed`.
- **Trusted IP preamble** (mandatory in prod): `server.forward-headers-strategy: native`;
  client IP is always `getRemoteAddr()` — never parse `X-Forwarded-For` in application code.
- **Fail-closed set on Redis error** → 503 `ERR_SYSTEM_UNAVAILABLE`: login, OIDC, agent
  execution, gateway execute, approvals decisions, policy/management writes, control.
  **Fail-open** (read/status surfaces: gateway-integration, approval queue, audit-read,
  dashboard) follows `netherite.rate-limit.default-on-redis-error: fail-open` — a Redis
  outage never blackouts reading.
- **429** body `{error: ERR_RATE_LIMIT_EXCEEDED, retryAfterSeconds, timestamp}` + `Retry-After`.
- Tune via env overrides (example): `NETHERITE_RATELIMIT_ROUTES_0_BUCKETS_0_LIMIT=...`;
  prefer editing the YAML table in code for structured edits.
- **Redis down for long**: the fail-fast `redissonRateLimitClient` (500 ms, 0 retries) trips
  `RateLimitDependencyGuard`; after `circuit-break-failures` consecutive failures the limiter
  stops contacting Redis and answers policy-only while security-sensitive routes stay closed.
- Redis is not a source of truth for admission state (TTL-bounded, rebuildable): a Redis
  restart only resets counters; it never weakens the fail-closed contract.

## 25. Designated Authorized Person operations (WS6)

WS6 removed role-based authorization. Each organization has **at most one designated
authorized person** (column `organizations.authorized_person_user_id`, added by migration
`V12`, all roles removed by `V13`). There are no `ORG_ADMIN`/`AUDITOR`/`HUMAN_APPROVER`
roles, no hierarchy, and no SSO-driven privilege: the IdP `adminClaim*` setting only gates
whether SSO login is admitted.

Operational consequences for deployments:

- **Fail-closed migration**: after `V12` upgrades, every existing organization has nobody
  designated. Configuration management, human-approval decisions, and audit reads are
  `403 ERR_FORBIDDEN` until a person is designated. Upgrade playbooks must include a
  bootstrap step per organization (`POST /v1/control/organizations/{orgId}/authorized-person`,
  deployment-owner session only) or accept the outage.
- **Owner bootstrap convenience**: the owner account's own organization is designated
  automatically at first owner login (`bootstrapOwnerIfUndesignated`); the owner cannot
  bootstrap or seize other organizations.
- **Transfer**: only the current designated person may transfer the designation
  (`PUT .../authorized-person`, body `{userId, expectedCurrentUserId}`). Wait for a 409
  `ERR_CONFLICT` and retry when a notification suggests the designation changed meanwhile.
- **Clear/lockout escape hatch**: `DELETE .../authorized-person` is deployment-owner-only.
  Use it (or the owner-only bootstrap of a replacement) to recover a lost designation.
- **Separation-of-duties limitation**: the designated person both configures policy/approval
  settings and personally passes the human-approval tickets those settings produce. There is
  no independent approver or reviewer role; the audit log is viewed by the same person.
  Assess accepted residual risk before deploying under regulatory four-eyes/maker-checker
  requirements.
- **Database roles** (the `netherite_admin`/`netherite_app` mentions in this document) are
  PostgreSQL roles for schema versus runtime connectivity and are unrelated to application
  authorization.

## 26. CI/CD (WS7)

Full pipeline design is documented in `docs/ci-cd.md`; the evidence ledger is
`docs/workstream-7-deployment-secrets-cicd.md`. Summary for operators:

- **CI** (`.github/workflows/ci.yml`, runs on every push/PR): backend Maven regression
  (Java 21, whole reactor), frontend locked install + production build (Node 22),
  repository-wide secret scan (self-test + full scan), `docker compose config` validation,
  production image builds with a **build-context leak gate** (proves `.dockerignore` keeps
  `secrets/` and `.env` out of every layer), and an OSV dependency scan that is non-gating
  per the WS5 accepted baseline. CI performs validation only — it never deploys.
- **CD** (`.github/workflows/cd-deploy.yml`) is a **template**, never triggered by push and
  **not executed in this repository**. To activate it, attach a self-hosted runner labeled
  `production`, create the `production` environment with the five deploy secrets (+ optional
   `GEMINI_API_KEY` / `OPENROUTER_API_KEY`, each required only when the host `.env` selects its provider) and required
   reviewer, and pre-configure `infrastructure/docker/.env` on the host. Secrets are written to
   git-ignored `secrets/*` files with `umask 0177`; nothing is echoed, built with secret
   `--build-arg`, or rendered into compose outputs. The sixth and seventh files, `gemini_api_key`
   and `openrouter_api_key`, are always materialized (empty unless the matching `*_API_KEY` is
   supplied) so `docker compose up` resolves them; a selected provider with a missing/empty key
   aborts **before** any image is built.
- **Deployment procedure**: `docker compose build` → `docker compose run --rm migrate`
  (single migrator) → `docker compose up -d --scale app=N` → readiness probe through the edge.
  Rollback = redeploy previous image tag; destructive-schema rollback is restore-only (§22).
- **Environment gating**: this repository has no Git remote and no production host attached;
  remote CI execution and CD activation are therefore documented as **environment-gated** and
  were never claimed as run.