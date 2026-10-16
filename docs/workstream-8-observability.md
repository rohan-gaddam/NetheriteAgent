# Workstream 8 — Observability (evidence ledger)

Status: verified 2026-10-10 — see §5. Companion docs: `docs/architecture.md`
(Observability section), `docs/production-deployment.md` (§12 Metrics, §13 Logging),
`docs/local-development.md` (logging profiles), `docs/ci-cd.md`.

---

## 1. Objective & acceptance

Give the control plane an observability story that is **safe by default** and **joinable
end to end**:

- **One request identifier end to end** — the edge and every application/security-tier log
  line for a single request share one id, and every response carries it back to the caller.
- **Structured application logging** — one JSON object per line in deployed/compose mode,
  with a fixed key set, explicit `requestId`, and security-relevant **events** (auth
  failures, rate-limit WARNs) instead of ad-hoc text; WARN paths are throttled so a busy
  limiter cannot flood the stream (no log-DoS).
- **Edge access logging** — JSON structured at Caddy, fixed field set, **path without query
  string**, **no request/response headers copied**, health-probe lines skipped, and every
  proxied line joinable to the app via the shared request id.
- **No secrets, no sensitive data** in logs, errors, health payloads, or metric labels —
  bodies/Authorization never logged, passwords never echoed, agent keys never echoed
  (canary-tested), metric label cardinality bounded (small enums / mechanism names / hex
  hashes), and a constant `environment` tag separating environments.
- **Metrics exposure hardened** — Actuator discovery page off (`/actuator` → 404), only
  `health,info,metrics,prometheus` registered, `metrics`/`prometheus` 404 at the edge and
  401 without an authenticated admin session even inside the compose network.
- **Nothing regressed**: WS1–WS7 suites stay green; Prometheus scrape integration verified;
  repository secret scan stays clean (new synthetic WS8 literals added to the narrow
  allow-list, never a broad ignore).
- **Terminal report** with exactly the required sections and one status verb.

## 2. Baseline (start of WS8)

| Area | State at start |
|---|---|
| Logging | `logback-spring.xml` plain + `structured` profiles existed from prior hardening; per-request request id absent from MDC at edge; no request id echoed to callers; app events ad-hoc text |
| Correlation | None across the edge↔app boundary; Caddy and JVM produced independent identifiers |
| Edge logs | Caddy default JSON access log (full `request` object incl. headers/query), or the earlier attempt at `format json { remote_ip ... }` field subdirectives — **invalid** in Caddy 2.8 |
| Metrics | `micrometer-registry-prometheus` absent from the app jar; `/actuator/prometheus` 404 in tests (see §4.1); no bounded-label policy; no environment tag |
| Actuator | discovery page exposed; `metrics`/`prometheus` publicly registered |
| Secret hygiene | canonical `secret-scan.ps1` allow-list did not yet know the WS8 synthetic literals |

## 3. Changes implemented (WS8)

### 3.1 Structured application logging (`logback-spring.xml`, `application.yml`)

- Two profiles: **`plain`** (default, local shells) and **`structured`** (deployed/compose/CI).
  `structured` uses a custom `SafeJsonLayout` encoder that emits one JSON object per line with
  fixed keys `ts, level, logger, thread, service, environment, requestId, message` and any
  `event`/extra MDC context; a **log sanitizer/scrubber** forces control characters below
  `0x20` to `U+FFFD` before JSON emission so hostile input can never corrupt or splint the
  structured stream.
- Compose runs the app/migrate with `SPRING_PROFILES_ACTIVE: structured`
  (`docker-compose.yml` `app` + `migrate`), and sets the constant metric tag
  `management.metrics.tags.environment` from `NETHERITE_ENV`.
- Security outcomes are structured events: e.g. `event=security.auth_failure` on rejected
  owner login (message `Owner login rejected: invalid credentials` — the password is never
  echoed), and rate-limit rejections as **throttled** WARNs (one per interval per key), so a
  flood of rejected requests cannot drive a log-volume DoS.

### 3.2 Request correlation (`CorrelationIdFilter`, `io.netherite.observability`)

- Order −100 filter (ahead of every security filter). Inbound `X-Correlation-Id` is adopted
  only when it matches a conservative charset (`[A-Za-z0-9][A-Za-z0-9._\-]{0,127}`), else a
  fresh UUID is generated; the value lives in the SLF4J MDC as `requestId` and is echoed on
  **every** response (a response wrapper sets it at creation time, so short-circuiting auth
  filters cannot bypass it).
- Edge join mechanism (see §3.3): Caddy generates the identifier at the edge and forwards it
  downstream; the app adopts + echoes the same value, so the response header, the edge access
  line and every app log line carry the identical id.

### 3.3 Edge access log (`infrastructure/caddy/Caddyfile`, Caddy 2.8)

Two fixes from earlier attempts are documented because they are Caddy-version realities:

1. **`format json` accepts NO field subdirectives** in Caddy 2.8 (an earlier sketch tried
   `format json { remote_ip {remote_ip} ... }` — the adapter rejects it:
   `unrecognized subdirective remote_ip`). The supported shape is `format filter { <field>
   delete ... }` for dropping fields plus `log_append <key> <placeholder>` for adding them.
2. The request-id placeholder is **`{http.request.uuid}`**, not `{request_id}` (which renders
   null) — Caddy generates the UUID lazily on first use and then records it automatically in
   access lines under the built-in `uuid` field.

Final edge config:

- `log { output stdout; format filter { request/common_log/resp_headers/user_id/bytes_read
  delete }; level info }` + `log_append method/path/proto/remote_ip` → each line carries only
  `ts, level, logger, msg, duration, size, status, remote_ip, proto, path, method, uuid`. The
  path is logged without its query string; no headers are copied.
- `log_skip /healthz*` and `log_skip /actuator/health*` keep probe noise out.
- `reverse_proxy { header_up X-Correlation-Id {http.request.uuid} }` forwards the edge id
  downstream (validated + adopted by the app filter).

### 3.4 Metrics (bounded, tagged, hardened)

- `micrometer-registry-prometheus` added (BOM-managed, `io.micrometer.prometheusmetrics`
  1.13.x — Boot 3.3.13's `PrometheusMetricsExportAutoConfiguration` requires the new
  `io.prometheus.metrics.*` client artifacts, which are on the classpath).
- Label policy enforced by `NetheriteMetrics`: counters/timers key on small enums
  (`rate_limit_mechanism`, `result`), fixed mechanism names, or hex digest-hashes of the
  summarized value — never raw agent keys, identifiers, IPs, or headers (canary-tested).
- `management.endpoints.web.discovery.enabled=false` → `/actuator` links page is 404;
  `management.endpoints.web.exposure.include: health,info,metrics,prometheus`.
- Runtime gating re-verified live: `metrics`/`prometheus` are 404 at the edge (`handle
  @closed`) and **401 `ERR_AUTHENTICATION_REQUIRED` without an authenticated admin session**
  even when scraped from inside the compose network.

### 3.5 Secret scanner allow-list (repo-owned correctness)

- `secret-scan.ps1` gained **two narrow, explicitly-reasoned allow-list entries** (never a
  broad ignore) for the WS8 synthetic canary literal `ntk_live_ws8.canary-secret`: one for
  its home in `ObservabilityIntegrationTest.java` and one for the scanner's own example
  corpus. Self-test still passes all assertions.

### 3.6 Latent deployment bug fixed en route (not WS8-introduced, but WS8's job)

- Rebuilding the app image and booting the real stack exposed a **runtime-only** bean wiring
  defect: `RedisDistributedLockManager` gained a second constructor (metrics injection) but
  neither was `@Autowired`-annotated, so with no no-arg constructor Spring failed at context
  startup (`No default constructor found`) — invisible to the H2 test profile, which never
  instantiates the Redis lock bean. Fixed by `@Autowired` on the 3-arg constructor; the 2-arg
  direct-construction contract used by `DistributedLockIT` is preserved. Full regression
  re-run green after the fix.

## 4. Security analysis

- **"One identifier, safe fields"** is the core invariant: the only cross-system join key is
  the edge-generated UUID; the log streams expose exclusively the fixed field set. A
  request-derived string can influence `path`/`message` but never key names, never headers,
  and never the correlation id beyond the validated charset.
- **No secrets in observability**: the login failure message carries no credential; logback
  sanitizer and the JSON layout keep the stream structurally intact under hostile input;
  scan of every service log stream for all four secret files plus the canary returned zero
  hits (2026-10-10, §5.3). Metric labels are bounded by construction.
- **Log-volume DoS** is bounded: authenticate/authorize failures and rate-limit WARNs route
  through a throttled path; the edge skips only health probes, so nothing else can be gamed
  into unbounded volume.
- **Exposure control**: `/actuator` (links) removed; telemetry endpoints double-gated (edge
  `@closed` + admin-session 401 internally). The demonstration that unauthenticated internal
  scrape fails closed is itself part of the acceptance evidence.

## 5. Verification

### 5.1 Test evidence

| Check | Command | Result (2026-10-10) |
|---|---|---|
| WS8 observability suite | `mvn -o -pl netherite-app -am test "-Dtest=io.netherite.observability.**" "-Dsurefire.failIfNoSpecifiedTests=false"` | **45/45 green, BUILD SUCCESS** (39 unit + 6 integration, incl. `prometheusRegistryIsExported`; 6-test `ObservabilityIntegrationTest` uses `@TestPropertySource` with `management.prometheus.metrics.export.enabled=true`) |
| Full backend regression (after `@Autowired` fix) | `mvn -o clean test` | **556/556 green, 0 failures/errors, 14 modules BUILD SUCCESS** |
| pgverify ITs (WS7 suite re-run after infra-only recovery) | `mvn -o -pl netherite-app -am test "-Dtest=PostgresApprovalPersistenceIT,PostgresUpgradePathIT,EnterpriseRuntimeIT" "-Dsurefire.failIfNoSpecifiedTests=false"` | **15/15 green** (6+6+3) — PG on `localhost:5433` |
| Frontend build | `npm run build` (frontend/) | EXIT 0 (tsc + vite build) |
| Compose config | `docker compose config --quiet` | PASS |
| Secret scan | `powershell -File infrastructure/docker/scripts/secret-scan.ps1` (+ `-SelfTest`) | **PASS, 0 findings (422 files)**; SelfTest PASS |

> Note on pgverify: the earlier reported failure ("Cannot connect to API: getaddrinfo
> ENOTFOUND opencode.ai") is **not** produced by this repository or any surefire report — it
> is an environment/agent artifact. The real pgverify blocker was a Flyway migrate-time
> checksum mismatch in a stale scratch `netherite_verify` database (recreated) alongside a
> host PostgreSQL that had to be restarted on port 5433 (a portable-PG child-process crash
> `0xC0000142` was recovered by a clean restart; if it recurs it is an environment
> limitation, not a code defect).

### 5.2 WS8 Prometheus test root-cause (recorded for the ledger)

The earlier failing `prometheusRegistry` test traced to a test-runtime property source named
`test` that sets `management.defaults.metrics.export.enabled=false` (origin not found in the
repo or environment), which makes Boot 3.3.13's `@ConditionalOnEnabledMetricsExport` omit the
`PrometheusMeterRegistry` bean (`management.defaults.metrics.export.enabled is considered
false`). The fix is the product-specific override
`management.prometheus.metrics.export.enabled=true` in the test's `@TestPropertySource` —
no scanner/registry mocks, no global property mutation.

### 5.3 Runtime verification (live stack, 2 app replicas behind Caddy, `structured` profile)

Stack rebuilt from current sources: `docker compose build app frontend` → `up -d --scale
app=2`; migrate completed, both replicas healthy, frontend (Caddy) healthy.

| # | Evidence | Outcome |
|---|---|---|
| R1 | `curl -sk -D- https://localhost/v1/agents` → `401` with `X-Correlation-Id`; edge access line `"uuid":"<same>"`; app security-tier line carries same id in `requestId` | **Join edge ↔ app ↔ response header verified** |
| R2 | `POST /v1/auth/login` (wrong password) → `401` with correlation; app line `event=security.auth_failure`, message has no password | Password never echoed |
| R3 | `GET /healthz`, `/actuator/health` → `200` **no edge access line** (log_skip) | Skip rules work |
| R4 | `GET /actuator/prometheus` (edge) → `404`, edge line **no uuid** (edge-only, documented); internally `curl app:8080/actuator/prometheus` → `401 ERR_AUTHENTICATION_REQUIRED` | Telemetry double-gated |
| R5 | `GET /actuator` → `404` (discovery off) | Links page gone |
| R6 | Every service log stream searched for the 5 secret files' head and for `ntk_live_ws8.canary-secret` | **0 hits** — no secrets/canary in streams |
| R7 | Edge access line shape | exactly `ts/level/logger/msg/duration/size/status/remote_ip/proto/path/method/uuid`; path without query; no headers |

Postgres/Redis/OPA all healthy; the stack continues running for operator inspection.

## 6. Known limitations (documented, not hidden)

- **Edge-only requests have no request id.** Responses generated purely at the edge (blocked
  `/actuator/*`, static SPA) never touch `{http.request.uuid}`, so their access lines lack
  `uuid` and nothing joins them to an app line (there is none). This is the intended,
  documented empty-join case.
- **Authenticated Prometheus scraping is session-bound.** An actual Prometheus target must
  scrape with the admin session of a scrape user (`http://app:8080/actuator/prometheus`);
  there is no machine-credential scrape path by design (metrics remain 401 unauthenticated).
- **CI/CD remote gates remain environment-gated** (no Git remote, no production host) —
  unchanged and stated honestly; the local equivalents in §5 replay every step.
- **Portable PostgreSQL on the host is restart-sensitive** (see §5.1 note); it is a host
  tool, not part of the deployed stack, whose postgres service is internal-only.

## 7. Residual follow-ups (non-blocking)

- Wire a real Prometheus/Grafana pivot to the authenticated scrape endpoint (deploy-model
  follow-up; fully supported by the endpoint).
- Consider an internal-only scrape credential (e.g. dedicated least-privilege Patron token)
  if automated scraping of `environment=production` is demanded by operations.
- Extend `log_append` retention of path-length bound if pathological long paths at Caddy are
  ever a concern (currently bounded by Caddy defaults).