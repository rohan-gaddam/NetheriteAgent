# Workstream 5 — Enterprise Security Hardening

Status: Batches A–C complete and verified (JVM gate). **Batch D — COMPLETE AND VERIFIED**
(real Redis ITs against the harness broker + two-replica Docker compose live panel, all gates
1–10 evidenced below). **Batch E — COMPLETE AND VERIFIED** (real-OPA ITs 11/11 + two-replica
Docker compose live panel, all gates evidenced below). **Batch F — COMPLETE AND VERIFIED**
(edge/Docker/non-root/actuator hardening; live panel gates F1–F11 + full regression 452/452,
 evidenced below). **Batch G — COMPLETE AND VERIFIED** (CLI-password elimination, all-5-secret
 coverage, JVM regression 457/457, live P1–P13 container panel 13/13 — evidence below). **Batch H — COMPLETE AND**
**VERIFIED** (dedicated security test suite: 6 classes / 35 tests, independently executable
`-Dtest=io.netherite.securitysuite.*Test`, no new dependencies; full JVM regression **492/492** —
evidence below). **Batch I — COMPLETE AND VERIFIED** (dependency & secret audit: OSV 157 artifacts,
**107 → 77 findings**, Boot 3.3.13 + bcprov 1.86 + postgresql 42.7.13 + vite 6.4.3; secret scanner
self-test + full scan 0 findings over 392 files (incl. the report file itself); regression
**492/492** + `npm audit` 0 — evidence
below). **Batch J — COMPLETE AND VERIFIED** (Batches A–K reconciled against on-disk evidence; Batch K
supersession justified; environment-gated E2Es and infra/scope limitations recorded; WS5 final
verdict **COMPLETE WITH DOCUMENTED LIMITATIONS** — see Batch J below).

Scope: layered defense against credential-stuffing, botnet floods, tenant-hopping, and
dependency-attack failures — no new third-party rate-limit library is introduced.

---

## Batch ledger

| Batch | Scope | Status |
|---|---|---|
| A | Authentication & authorization hardening | COMPLETE AND VERIFIED (JVM gate) |
| B | SSRF / open-redirect / port-policy hardening | COMPLETE AND VERIFIED (JVM gate) |
| C | Audit integrity (session hashing at rest + append-only audit) | COMPLETE AND VERIFIED — 409/409 regression |
| **D** | **Distributed rate limiting, fail-closed** | **COMPLETE AND VERIFIED — real Redis ITs 44/44 + two-replica compose live panel (gates 1–10)** |
| **E** | **OPA policy rewrite + policy push + OPA bearer auth** | **COMPLETE AND VERIFIED — real-OPA ITs 11/11 + two-replica compose live panel (gates below); full regression 439/439** |
| F | Caddy / Docker / non-root / actuator hardening | **COMPLETE AND VERIFIED — live panel gates F1–F11 + regression 452/452** |
| G | Secrets purge (CLI-password elimination + all-5-secret coverage) | **COMPLETE AND VERIFIED — JVM regression 457/457; live P1–P13 container panel 13/13 (evidence below)** |
| H | Dedicated security test suite | **COMPLETE AND VERIFIED — 35/35 independent suite run + full JVM regression 492/492** |
| I | Dependency & secret audit | **COMPLETE AND VERIFIED — OSV 157 artifacts / 77 findings (baseline 107); Boot 3.3.13, bcprov 1.86, postgresql 42.7.13, vite 6.4.3; secret scan 0 findings / 392 files; regression 492/492 + npm audit 0** |
| J | Docs + ledger finalization | **COMPLETE AND VERIFIED — Batches A–I reconciled, Batch K supersession justified, WS5 verdict issued (see Batch J below)** |
| K | Live Docker verification + final verdict line | **SUPERSEDED** — live verification covered by Batch G's P1–P13 13/13 panel (+ Batch D/E/F live gates); the *final-verdict line* scope is executed by Batch J below |

**Blocker (resolved)**: the Batch D live panel now runs to completion on this host (native Redis
:6379 harness + `docker compose` two-replica topology with Caddy edge). Remaining environment-gated
items (not Batch D gates; recorded in Batch J below): `ws2-human-session.e2e.mjs` needs a test-profile
backend on :8080 + Vite :3000 + Keycloak :8180; `ws3-real-sso.e2e.mjs` needs Keycloak. History: the
D6 IT was previously blocked on Docker-unavailable; that blocker is cleared.

---

## Batch A & B — authentication, authorization, SSRF (summary)

- Agent/api-key authentication hardening (identifiers at rest hashed), fail-closed
  authorization on every protected `/v1` surface (`AuthorizationFilter`, `@Order(3)`),
  human-session integrity (server-derived identity, hashed session tokens at rest).
- SSRF guard + blocklisted ports (`SsrfGuardValidatorImpl`), open-redirect closure, and the
  remote-IP/trusted-proxy precondition that the WS5 rate limiting depends on.

Evidence: WS1–WS4 regression green; full backend regression when Batches A–D completed was
**410/410** (all Batch A/B/C suites included, unchanged). The WS5-final regression is **492/492**
(Batch I gate, see below).

## Batch C — audit integrity (summary)

- User-session token hashed at rest; append-only tamper-evident audit ledger with per-org
  unique `uk_audit_request_event`, SHA-256 chaining with length-canonical hashing,
  server-derived approver attribution (a forged body value can never be attributed).
- Migration V9 + PG-only V10 (least-privilege owner + DDL guard); Flyway V1..V8 byte-identical
  across DBs (expand/contract, all future changes V9+).
- Regression at completion: **409/409**.

## Batch D — distributed rate limiting, fail-closed

Ball requirements covered:

| Requirement | Delivery |
|---|---|
| Distributed, no per-node bypass | Single atomic Lua fixed-window script runs inside Redis — every replica reads the same counters; the control plane never falls back to a per-node limiter |
| Stop floods before cheap BCrypt | `PreAuthRateLimitFilter` at `@Order(0)` runs before `UserAuthenticationFilter` (`@Order(1)`) — prerequisite A (the pre-existing `X-Netherite-Agent-Key` BCrypt verify at `ApiKeyAgentAuthenticationProvider`) is now behind a cheap tier |
| Trusted client IP | `server.forward-headers-strategy: native` (Tomcat `RemoteIpValve`, RFC1918 + loopback trusted proxies default); `ClientIpResolver` uses `getRemoteAddr()` only — client headers are never trusted |
| Fail closed on security-sensitive surfaces, no whole-app outage | Route-level `fail-closed: true` (login, OIDC, agent execution, gateway execute, approvals decision, policy/management writes, control) → 503 `ERR_SYSTEM_UNAVAILABLE` on Redis error; read surfaces (gateway-integration, approval-queue, audit-read, dashboard) follow `default-on-redis-error: fail-open` |
| No aimless limiter state / bounded keys | Fixed windows anchored at first hit; every bucket key carries `PEXPIRE = window`; denied requests roll all buckets back (no quota consumed, no drained sibling) |
| Safe keys | Account email + API-key identifier SHA-256 hashed in the key (`netherite:rl:<route>:<dim>:<sha256>`); org/user/agent are server-derived UUIDs; never secrets |
| Tenant isolation | Identity tier keys only from `TenantContextHolder` — a forged header cannot choose a bucket; per-user AND per-org buckets |
| Detect slow degradation | Dedicated fail-fast Redisson client (`redissonRateLimitClient`, 500 ms timeouts, 0 retries) + `RateLimitDependencyGuard` circuit breaker (open after N consecutive failures, half-open probe) |

### Design

- Two tiers:
  - `PreAuthRateLimitFilter` `@Order(0)` — IP, LOGIN_ACCOUNT, LOGIN_ACCOUNT_IP,
    AGENT_KEY_IDENTIFIER. Login body buffered via `ReplayableBodyRequestWrapper` (bounded
    ≤ 8 KiB) purely to derive the hashed account key; the controller's `@RequestBody` sees a
    fresh replay stream (the `ContentCachingRequestWrapper` alternative was rejected because
    it consumes the stream without replay).
  - `IdentityRateLimitFilter` `@Order(4)` — ORGANIZATION, USER, AGENT from the trusted
    context, after `AuthorizationFilter` `@Order(3)` (unentitled floods already rejected cheaply).
- Atomic Lua (`RedisRateLimitStore`): for every request, INCR all buckets, PEXPIRE on first
  hit, if any exceeds limit → DENY + DECR rollback of all, return `{denied, soonestPttlMs}`.
- 429 → `ERR_RATE_LIMIT_EXCEEDED` + `Retry-After`; 503 → `ERR_SYSTEM_UNAVAILABLE`.
- Production defaults live in `backend/netherite-app/src/main/resources/application.yml`
  under `netherite.rate-limit` (whole table env-overridable); the test base profile ships
  `routes: []` so existing suites (e.g. `OwnerAuthenticationTest`'s 33 logins) are untouched;
  a focused `ratelimit` test profile sets tight per-route limits.

### New / changed files (Batch D)

- `backend/netherite-common/.../SystemErrorCode.java` — `ERR_RATE_LIMIT_EXCEEDED(429)`.
- `backend/netherite-app/.../io/netherite/security/rate/` — `RateLimitProperties` (+`RouteSpec`/
  `BucketSpec`, `PathPatternParser` matching), `RateLimitDimension`,
  `RateLimitDecision`, `RateLimitStore` (+`BucketKey`), `InMemoryRateLimitStore`
  (`provider=memory`), `RedisRateLimitStore` (`provider=redis`, matchIfMissing),
  `RateLimitKeyBuilder`, `ClientIpResolver`, `RateLimitVerdict`, `RateLimitDependencyGuard`,
  `RateLimitService`, `RateLimitResponseWriter`, `PreAuthRateLimitFilter`,
  `IdentityRateLimitFilter`, `ReplayableBodyRequestWrapper`, `RateLimitRedissonConfig`.
- `application.yml` (prod) — `server.forward-headers-strategy: native` + full rate-limit table.
- Test fixtures — `application-ratelimit.yml` (tight per-route limits), base test `routes: []`.

### Evidence (this session run)

- **Unit + integration gate — 36/36** across:
  `RateLimitKeyBuilderTest`, `RateLimitServiceTest`, `InMemoryRateLimitStoreTest`,
  `RateLimitProductionDefaultsTest`, `PreAuthRateLimitFilterIntegrationTest`,
  `IdentityRateLimitFilterIntegrationTest`, `RateLimitFailClosedIntegrationTest`,
  `RateLimitCircuitBreakerIntegrationTest`.
- **Full backend regression — 410/410** (`mvn -o test -pl netherite-app`).
- Highlighted behaviors proven by tests:
  - same-IP flood across accounts → 429 after the IP budget; credential-stuffing across
    fresh IPs → 429 from the hashed account bucket; account+IP bucket binds a source to an
    account; a fresh account from the same IP is never buried; OIDC GET burst → 429; repeated
    attempts with one agent-key identifier → 429 *before* BCrypt/DB work while a different
    identifier is admitted; a 1 s window expires and releases traffic (TTL = bounded key space).
  - per-user budget denies the user then a sibling in the same org still proceeds; a sixth
    brand-new user in a five-budget org is denied by the ORGANIZATION bucket; one tenant's
    exhaustion never affects another.
  - Redis "down" (store throws) → `POST /v1/policies` is 503 `ERR_SYSTEM_UNAVAILABLE`,
    `GET /v1/audit` stays 200 (fail-open) while the store is still exercised; after
    `circuit-break-failures` (2) the breaker opens and the store is no longer called (verified
    `times(2)`) while the response remains 503.
  - production defaults: rate limit enabled, fail-closed set, login/execution/org buckets,
    `forward-headers-strategy: native`.

### Batch D — live panel (complete, this run)

Fixed bugs found while bringing the live panel up (product code):

- **Ambiguous `RedissonClient` injection** — Batch D added a second, dedicated
  `redissonRateLimitClient` bean, leaving `RedisDistributedLockManager` and
  `DistributedCoordinationHealthIndicator` with unqualified `@Lazy RedissonClient` injection
  that failed *on first use* as "Redis unavailable", failing closed even when Redis was healthy.
  Both now inject `@Lazy @Qualifier("redissonClient")`.
- **Eager rate-limit client at startup** — `RedisRateLimitStore` wired
  `@Qualifier("redissonRateLimitClient")` without `@Lazy`, so the compose `migrate` container
  (no Redis wired) crashed on bootstrap. Now `@Lazy @Qualifier("redissonRateLimitClient")`.
- **Stale IT assertions** — classpath tree is V1..V9 (`db/migration`) + V10 (`db/postgresql`, PG-only)
  with Flyway locations `classpath:db/migration,classpath:db/postgresql`. `EnterpriseRuntimeIT` /
  `PostgresApprovalPersistenceIT` were pinned to V1..V8; now they assert exactly V1..V10.
  `PostgresUpgradePathIT` asserted V8 as top-of-tree; it now asserts V1..V9 and exercises a V9
  upgrade phase (`audit_chain_heads` + `uk_audit_request_event`, no-op rerun).
- **V10 grant gap** — `netherite_app` runtime role hit `permission denied for table user_sessions`
  (the original V10 grant block only covered audit tables + `ALTER DEFAULT PRIVILEGES`, which does
  not retroactively apply). V10 now grants CRUD on all public tables/sequences + dynamic
  `GRANT CONNECT ON DATABASE` (a literal `GRANT ... current_database()` is invalid SQL) while
  keeping the append-only `REVOKE UPDATE, DELETE` on audit tables.

Live evidence (this run, 2026-09-26):

| Gate | Check | Result |
|---|---|---|
| 1 | Rate-limit unit + integration gate | 36/36 green |
| 2 | Real-Redis IT suites (8 suites) | 44/44 green: DistributedLockIT 6/6, DistributedIdempotencyIT 7/7, ApprovalExecutionClaimIT 6/6, EnterpriseRuntimeIT 6/6, PostgresApprovalPersistenceIT 6/6, PostgresUpgradePathIT 3/3, RealOpaContractIT 4/4, RedisRateLimitStoreIT 6/6 |
| 3 | Two-replica compose topology | `up -d --scale app=2 --wait` — postgres/redis/opa/app-1/app-2/frontend healthy; migrate clean at schema V1..V10 |
| 4 | Per-IP login flood through Caddy (HTTPS) | 4×/5× 401 `ERR_AUTHENTICATION_FAILED` then 429 `ERR_RATE_LIMIT_EXCEEDED` + `retryAfterSeconds`; no bypass |
| 5 | Concurrent 20-way burst (both replicas, Caddy round-robin) | exactly 5 allowed (401), 15 denied (429), zero 200/500 — Lua INCR is atomic, no per-node bypass |
| 5b | Shared counter space | Redis keys `netherite:rl:owner-login:login-account:<sha256>` present with TTL (LOGIN_ACCOUNT 3600 s window verified at count=5, TTL 3417 s); values are SHA-256 digests, no PII |
| 6 | Redis stopped → policy/login writes | 503 `ERR_SYSTEM_UNAVAILABLE` on login POST and `/v1/policies` (fail-closed) |
| 7 | Redis stopped → reads | `/v1/audit` GET returns 200 while Redis is down (fail-open) |
| 8 | Redis restored → breaker recovery | no lingering 503: first post-restart attempt sat in a short Redisson reconnect window, recovered ≤30 s; valid login 200; flood semantics resume (401×4 → 429) |
| 9 | Caddy E2E over HTTPS | unauth `/v1/auth/me` 401; `http://` → 308 → `https://localhost`; owner login 200 (ORG_ADMIN, `neth_sid` HttpOnly cookie); authed `/v1/auth/me` 200; wrong-pw 401; readiness UP |
| 10 | Regression after fixes | `mvn -o test -pl netherite-app` **410/410, BUILD SUCCESS**; frontend `tsc && vite build` clean. `ws2`/`ws3` browser E2E environment-gated (need test-profile dev stack + Keycloak), outside this panel |

Notes: the Caddy container's bridge IP is the remote peer the apps see (default Tomcat internal-proxies do
not include 172.16/12), so this panel exercises one client IP — exactly the intended single-bucket proof;
for real-client IPs behind the LB, widen `server.tomcat.remoteip.internal-proxies` (config decision, not
changed in Batch D).

---

## Batch E — OPA policy rewrite + policy push + OPA bearer auth

Ball requirements covered:

| Requirement | Delivery |
|---|---|
| Fail-closed decision engine | `base.rego` (single `evaluation_output` rule, `default decision := "DENY"`); org-tenant modules degrade to base rules when absent; an engine outage returns 503 (never ALLOW); readiness stays UP so an OPA loss never crash-loops a replica |
| OPA bearer auth | Engine runs `--authentication=token --authorization=basic`; app authenticates every decision/health/push call with the `opa_policy_token` file-secret injected as `OPA_TOKEN` by `docker-entrypoint.sh`; a missing/bad token → 401 (verified live) |
| Authorized policy push (secure) | `PolicyPushService` bakes tenant modules onto the pinned package `netherite.authz.tenants.<org>` (reserved `system.*` rejected, size-capped); `PUT /v1/policies/<id>` is the authoritative validator; pre-flight compile is a fast-fail |
| Invalid updates cannot silently replace | PUT rejects parse-broken modules (400 → 422 `ERR_POLICY_PUSH_REJECTED`); OPA installs atomically, so the previously ACTIVE module stays in force; version stays DRAFT |
| Audited ACTIVE ≠ enforced drift | Periodic reconciliation (default 60 s, `OPA_POLICY_PUSH_RECONCILE_INTERVAL_MS`) re-installs the base and every ACTIVE tenant module that vanished — OPA is in-memory, so a daemon restart silently drops modules; recovery is automatic, no app restart |
| Tenant isolation | Each module lives in its own pinned package; org A data/deny cannot affect org B (engine-level cross-check verified) |
| Known, auditable active-policy state | `policy_versions.status`/`activated_at` + `activeVersionId` + per-replica `policySync` health detail (base present, last outcome/time); version create/activate reports exact error codes |

### New / changed files (Batch E)

- `netherite-app/src/main/resources/opa/base.rego` (and compose `infrastructure/docker/opa/policies/base.rego`, `system/authz.rego`, `opa/opa-entrypoint.sh`) — canonical fail-closed policy + bearer-auth entrypoint (ASCII, mojibake fixed).
- `policy/push/PolicyPushService.java` — push, package pinning, `KNOWN_BASE_MODULE_IDS` base-presence scan (`netherite-authz-base`/`policies/base.rego`/`base.rego`), putPolicy error mapping (engine reject → 422 `ERR_POLICY_PUSH_REJECTED`, transport → 503 `ERR_POLICY_PUSH_FAILED`), periodic `reconcilePolicyState()` (base + ACTIVE versions).
- `policy/push/PolicyPushProperties.java` (+`reconcileIntervalMs`), `policy/push/PolicySyncStatus.java`, `domain/repository/PolicyVersionRepository.java` (+`findAllByStatus`).
- `policy/client/OpaPolicyManageClientImpl.java` — `compileModule` sends `{"query":...,"modules":...}` (OPA needs `query`).
- `domain/entity/PolicyVersionEntity.java` — `@JdbcTypeCode(SqlTypes.JSON)` on `parsedAst` (jsonb vs varchar defect).
- `db/postgresql/V11__audit_chain_heads_lock_fix.sql` — re-grants `SELECT, INSERT, UPDATE` on `audit_chain_heads` (V10's least-privilege revoke collided with the PESSIMISTIC_WRITE `FOR NO KEY UPDATE` lock that requires UPDATE); applied live (flyway history: V11 true).
- `application.yml` — `netherite.opa.policy-push.*` (+`reconcile-interval-ms`), `token` wiring.
- Tests: `PolicyPushServiceTest` (18), `RealOpaContractIT` (4), `RealOpaAuthAndPushIT` (7, incl. authoritative PUT check), `OpaPolicyEngineClientAuthTest` (4), `AgentBindingFactProviderRiskScoreTest` (7), `OpaHealthIndicatorTest` (2).

### The 422-not-503 defect (why the compile fast-fail is not authoritative)

OPA `/v1/compile` with a `query` returns **200 even for a parse-broken module** (verified live);
only `PUT /v1/policies` parses-and-installs atomically and returns the `rego_parse_error` 400.
So `pushTenantPolicy` maps: engine responded with a rejection → 422 `ERR_POLICY_PUSH_REJECTED`
(activation aborted, previously-ACTIVE module untouched); transport failure (status 0) → 503
`ERR_POLICY_PUSH_FAILED`. Live broken-version activation first returned 503 pre-fix, then 422
post-fix, and `activeVersionId` stayed on the valid version.

### Batch E — live panel (complete, this run, 2026-09-27)

Harnesses re-restarted: `netherite-it-opa` (127.0.0.1:8181, bare OPA 0.68.0-debug),
`opa-auth` (127.0.0.1:8282, `--authentication=token --authorization=basic`, harness-only
authz token, value redacted in this doc), `netherite-it-pg` (127.0.0.1:5433). Production topo: `docker compose`
two replicas `netherite-app-1`/`netherite-app-2` (image rebuilt after fixes),
`netherite-opa-1` internal-only, HTTPS edge.

| Gate | Check | Result |
|---|---|---|
| 1 | Harness auth | opa-auth /health + /v1/policies: 401 no-token, 200 with token |
| 2 | Compose config no secret values | `docker compose config` renders secret source names/paths only |
| 3 | Two-replica topology | `up -d --scale app=2 --wait`: app-1/app-2/opa/frontend/postgres/redis healthy, migrate exited 0 |
| 4 | Unauthorized OPA on prod topo | /health no-token 401, /v1/policies no-token 401, bad-token 401, good-token 200; app→OPA authed decision path → HIGH call resolved 202 APPROVAL (not an OPA error) |
| 5 | Malformed push rejected, prior ACTIVE intact | Activating parse-broken v2 (`ee3f4de4…`) → **422 ERR_POLICY_PUSH_REJECTED**; activeVersionId stays `7f6a948a…`, v1 ACTIVE, v2 DRAFT; module still in OPA (200); gateway LOW still DENIED |
| 6 | DENY both risk tiers + isolation | Data overlay `{"enabled":true,"decision":"DENY"}` (module removed first): HIGH INVOICE → 403, LOW INVOICE → 403; engine-level cross-check: org B HIGH risk-70 → `HUMAN_APPROVAL` (unaffected); data removed → regenerate HIGH → 202 APPROVAL; module re-activated → LOW 403 again |
| 7 | OPA stop → fail closed → auto recovery | `stop opa` → gateway LOW **503** `ERR_SYSTEM_UNAVAILABLE` (never ALLOW), readiness stays **UP**; `start opa` → module 404 then auto-restored by reconciliation ≤90 s (base + ACTIVE re-push), LOW 403 / HIGH 202 again — no app restart |
| 8 | Two replicas same policy/auth | Direct per-replica execution against each listener (`app/own :8080`): both **403 DENIED** identical; both run OPA_URL/push=true config |
| 9 | No secret leakage | `docker inspect` env, both `docker logs`, `docker exec` env-probe, and all HTTP responses scanned for the token bytes → 0 hits (entrypoint copies file secret to JVM env, reports names/paths only) |
| 10 | Real-OPA ITs | `RealOpaContractIT` 4/4 + `RealOpaAuthAndPushIT` 7/7 = **11/11** against live harnesses |
| 11 | Full regression | `mvn -o test -pl netherite-app -Dnetherite.realopa-it=true` → **439/439, 0 failures, BUILD SUCCESS**; frontend `tsc && vite build` clean |

Product-code bugs found and fixed during the live panel: `audit_chain_heads` V11 lock grant;
`parsed_ast` jsonb mapping; hard-coded base-module id → `KNOWN_BASE_MODULE_IDS`; broken-rego
activation 503→422 mapping; OPA restart losing pushed modules → periodic reconciliation gate
(see above); plus two scheduler defects surfaced by the fresh log scrutiny —
`SessionCleanupScheduler.purgeExpiredSessions()` called the entity-loading
`deleteByExpiresAtBefore` (EntityManager `remove`) without `@Transactional`, so the purge
threw `TransactionRequiredException` every 5-min cycle and stale sessions accumulated (now
`@Transactional`), and `HealthCheckService` used `!activeChecks.putIfAbsent(...)` — which NPEs
on the *first* check of any resource (`putIfAbsent` returns null then), so every health cycle
logged `NullPointerException` (now guarded with `== null`, verified via the live
`POST /v1/dashboard/health/check` cycle).

---

## Batch F — Caddy / Docker / non-root / actuator hardening

Ball requirements covered:

| Requirement | Delivery |
|---|---|
| TLS floor | Caddy 2.8 `tls { protocols tls1.2 tls1.3 }` (valid directive/token spelling live-checked; `min_protocols` and `TLS1.2` are both rejected by Caddy 2.8) — exact-version probes: **TLS1.1 rejected, TLS1.2/1.3 accepted** |
| Edge security headers + CSP | Site-level `header` block: `Strict-Transport-Security`, `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, exhaustive `Permissions-Policy`, `X-Permitted-Cross-Domain-Policies: none`, strict CSP (`default-src 'self'`; validated against the vite bundle — 0 inline scripts, 1 external module) |
| Payload cap | `request_body { max_size 4MB }` on the API route; proxy timeouts `dial 5s / read 60s / write 60s` |
| Management closure at the edge | First `handle @closed` block 404s `/actuator/metrics|prometheus|env|beans|configprops|mappings|loggers|logfile|heapdump|threaddump|shutdown|refresh|restart*`, bare `/actuator`, and scouting paths `/.git* /.env* /server-status* /wp-admin* /administrator*`; only `/actuator/health*` and `/actuator/info*` proxy to an app socket |
| Origin actuator gate | `ActuatorAccessFilter` (@Order 4): `/actuator/health**` public; every other `/actuator/**` requires a valid session with `ORG_ADMIN`/`PLATFORM_ADMIN` — else 401 `ERR_AUTHENTICATION_REQUIRED` / 403 `ERR_FORBIDDEN`, with TenantContext set; unknown actuator paths 404 (`NoResourceFoundException` handler) instead of the previous catch-all 500 |
| No cache on /v1 & /actuator | Origin `SecurityHeadersFilter` (@Order 0, runs before auth) emits `Cache-Control: no-store` — present even on 401/429 rejections |
| Container hardening | Every service: `read_only`, `tmpfs /tmp`, `cap_drop: ALL`, `no-new-privileges`, `init`. Explicit `user`: app 10001, frontend (caddy) 10001, redis 999:1000, opa already 1000, postgres kept only its 5 required caps. Only the frontend publishes host ports |
| Single header owner | Transport/origin headers are emitted **only at the edge**; the origin filter was reduced to `Cache-Control` after live curl showed proxied responses carrying every header twice (edge + origin) — browsers take the first, but a single owner is the clean, reviewable posture |
| Session integrity under hardening | Session cookie remains `Secure; HttpOnly; SameSite=Lax` (verified on both edge and origin) — so no admin actuator call can ride a cleartext channel |

### New / changed files (Batch F)

- `infrastructure/caddy/Caddyfile` — TLS floor, `header` block (HSTS/CSP/etc.), `@closed`/`@api` handle structure, `request_body`, proxy timeouts (validated `caddy validate`, `caddy fmt` clean).
- `infrastructure/docker/docker-compose.yml` — per-service `user`/`read_only`/`tmpfs`/`cap_drop`/`cap_add`/`security_opt`/`init`; frontend healthcheck now `curl -fsSk https://localhost/healthz` (see bugs below).
- `frontend/Dockerfile` — non-root caddy uid 10001 (`addgroup/adduser`), `setcap -r /usr/bin/caddy`, `apk add --no-cache --virtual .hc-deps curl`, curl-based HEALTHCHECK, `USER caddy`.
- `netherite-app/src/main/java/io/netherite/security/filter/SecurityHeadersFilter.java` — @Order 0 `Cache-Control: no-store` for `/v1/` and `/actuator/`.
- `netherite-app/src/main/java/io/netherite/security/filter/ActuatorAccessFilter.java` — @Order 4 origin gate (health public; rest admin-session only).
- `netherite-app/src/main/java/io/netherite/api/GlobalExceptionHandler.java` — `NoResourceFoundException` → 404 `ERR_NOT_FOUND`.
- `application.yml` — `server.server-header: ""` (no Tomcat banner).
- Tests: `ActuatorAccessFilterTest` (10), `SecurityHeadersFilterTest` (3).

### Batch F — live panel (complete, this run, 2026-09-27)

Production topo (`netherite_netherite_net`, two replicas, Caddy HTTPS edge, all images rebuilt
after hardening): app-1/app-2/frontend/opa/postgres/redis **all healthy**; migrate exited 0.

| Gate | Check | Result |
|---|---|---|
| F1 | Edge closure matrix | `/actuator/health`, `/actuator/health/readiness`, `/actuator/health/liveness` 200 public; `/actuator/info` 401 at edge (origin admin gate); `/actuator/metrics`, `/actuator/env`, bare `/actuator`, `/.git/config`, `/server-status` → **404** (edge `@closed`, first handle block) |
| F2 | TLS floor | Exact-version probes (Python ssl, min==max) against `netherite-frontend-1:443`: TLS1.1 **REJECTED**, TLS1.2 accepted, TLS1.3 accepted |
| F3 | Headers single-emission | Proxied API response: HSTS, CSP, nosniff, X-Frame-Options DENY, Referrer-Policy, Permissions-Policy, X-Permitted-Cross-Domain-Policies each exactly once, `Cache-Control: no-store`, `Server: Caddy` (no Tomcat banner) |
| F4 | Runtime identities + capabilities | `docker exec id`: app 10001(app), frontend 10001(caddy), redis 999/1000, opa 1000; PID1 `CapEff`: all-zero on app/frontend/redis/opa, postgres `0xcb` = CHOWN+DAC_OVERRIDE+FOWNER+SETGID+SETUID (minimal set, cap_add-only) |
| F5 | Published ports | `docker ps`: only frontend publishes host ports (80/443). Host probes: 127.0.0.1:8080/5432/6379 closed, 443 open (127.0.0.1:8181 = OPA *test harness* for ITs, loopback-only) |
| F6 | Cross-bridge isolation | Default `bridge` → `netherite-app-1:8080`, postgres:5432, redis:6379, opa:8181, frontend:443 (internal IPs) all **fail** (no route) |
| F7 | Redis non-root AOF persistence | `user: 999:1000`, `/data` + appendonlydir owned redis:redis; set key → `docker restart netherite-redis-1` → value survives (AOF everysec, read-only container) |
| F8 | Origin actuator gate (in-network) | unauth `/actuator/metrics` → **401**; `/actuator/health` + readiness public 200; admin-over-**http** → 401 because the `Secure` cookie is withheld on cleartext (correct); so metrics is effectively closed: edge 404 + no cleartext admin path. Prometheus-style scrape therefore requires a deliberate protected scrape endpoint behind the edge (documented in production-deployment) |
| F9 | Batch A–E smoke over hardened edge | login 200 + `Secure` cookie; admin `/actuator/info` 200; gateway `INVOICE:CREATE` LOW and HIGH keys → **403 DENIED** with the OPA verdict shape + requestId (consistent with the ACTIVE deny policy; keys authenticate, reach the OPA pipeline, no 401/500 artifacts) |
| F10 | Compose/hardening facts | `docker compose config` renders no secret values; `docker inspect` env/`docker logs`/HTTP responses carry no token bytes (Batch E precedent re-checked on the rebuilt images) |
| F11 | Full regression | `mvn -o test -pl netherite-app -Dnetherite.realopa-it=true` → **452/452** (baseline 439 + the 13 new Batch F tests), 0 failures, **BUILD SUCCESS**; frontend `tsc && vite build` clean |

Product/infrastructure bugs found and fixed during the live panel:

- Caddy 2.8 directive drift: `min_protocols` is an unknown subdirective and the protocol tokens are
  lowercase (`tls1.2`, not `TLS1.2`) — validated interactively, then pinned to
  `protocols tls1.2 tls1.3`.
- Caddy route semantics: a bare site-level `respond @closed 404` is sorted **after** the `handle`
  group, so closed paths fell through to the SPA (200). Moved closure into `handle @closed` as the
  **first** handle block (mutually exclusive) — all closed paths now 404.
- The compose service `healthcheck` **overrides** the image `HEALTHCHECK`: the container kept running
  the old `wget -qO- http://localhost/healthz` (which cannot follow Caddy's auto-HTTPS 308 nor skip
  the self-signed cert) even though the rebuilt image shipped a curl probe. Fixed by switching the
  compose healthcheck to `curl -fsSk https://localhost/healthz` — frontend healthy.
- `caddy:2.8-alpine` ships **file capabilities** on the binary; under `cap_drop: ALL` +
  `no-new-privileges: true` the base image's `tini` fails `exec caddy: Operation not permitted`.
  Fixed with `setcap -r /usr/bin/caddy`.
- postgres with `cap_drop: ALL` broke the entrypoint (root cannot chown/chmod the volume, endless
  `chmod/find: Operation not permitted` loops). Kept `cap_add: [CHOWN, DAC_OVERRIDE, FOWNER,
  SETGID, SETUID]` — the minimal set the image entrypoint needs before it gosu-drops to `postgres`.
- The overridden redis `command` makes the image entrypoint skip its `gosu` branch and its `/data`
  chown, so the live volume stayed root-owned. Chowned `redis:redis` and pinned `user: 999:1000` —
  persistence verified (F7).
- Duplicate security headers on proxied responses (edge + origin both stamped nosniff/HSTS/…).
  Origin filter trimmed to `Cache-Control`; the edge is the single header owner (F3).
- `server.server-header: ""` suppresses the Tomcat banner on direct origin responses.

---

### Batch G — secrets purge (complete and verified)

Goal: no owner password may ever appear on a command line, in a Java argv, or in any output
stream; all five file secrets are provisioned and verified end-to-end; temp files are cleaned
with `finally`/`trap`; the contract (compose mounts == entrypoint requirements == inventory) is
locked by an automated test.

**Changes:**

- `infrastructure/docker/scripts/generate-secrets.ps1` — owner password reads `$env:OWNER_PASSWORD`
  or a hidden SecureString prompt only (no `-OwnerPassword` CLI parameter; nothing process-list
  visible). `Get-BcryptHash` runs `BcryptTool.java` with the password passed **via the
  `OWNER_PASSWORD` environment variable** (the java child never sees an argv password), and the
  function's `finally` block removes the extracted `spring-security-crypto`/`spring-jcl` temp dir
  and restores/removes the env var on every path. Fixed `$PSScriptRoot` param-default evaluation
  (unavailable under `[CmdletBinding()]` in PS 5.1 `-File` invocation) by resolving `-AppJar` /
  `-SecretsDir` defaults in the body.
- `infrastructure/docker/scripts/generate-secrets.sh` — same env-only contract (`OWNER_PASSWORD`,
  no positional arg), `umask 0177`, `trap 'rm -rf "$TMP"' EXIT`; `opa_policy_token` aligned to 32
  bytes (64 hex) to match the ps1 generator and the live secret inventory.
- `infrastructure/docker/scripts/BcryptTool.java` — *no functional change* (already env-only, exit 2
  on missing/empty `OWNER_PASSWORD`); stripped a UTF-8 BOM that made the Java 21 single-file launch
  fail (`illegal character: '\ufeff'`) — verified `BOM-free` and hashing successfully.
- `infrastructure/docker/scripts/verify-secret-handling.ps1` — preflight now checks **all five**
  secret files (db_password, db_app_password, redis_password, opa_policy_token,
  owner_password_hash); P1/P2/P9 needles extended to all five; P3 phony set writes all four
  non-owner secrets; **P8 rewritten** to a per-secret target map (postgres/app/redis/opa) with
  `sha256sum` comparison against the host file; **P13 added** — static `require_secret`↔compose
  contract (app mounts db_app_password, redis_password, owner_password_hash, opa_policy_token and
  **never** db_password; top-level secrets inventory = exactly the 5 `file: ./secrets/<name>`
  entries). Also fixed the same `$PSScriptRoot` param-default issue and removed the deprecated
  `-OwnerPassword` CLI parameter (P5 already used env-only `$OwnerPassword`).
- `infrastructure/docker/scripts/secret-scan.ps1` — allowlist entry for the P5 bad-login probe
  literal (`definitely-wrong-password`: intentional, sent to the live app expecting 401), keeping
  the full-repo scan at 0 findings.
- `docs/production-deployment.md`, `infrastructure/docker/.env.example`, `infrastructure/docker/.env`
  — all `-OwnerPassword '…'` CLI invocations replaced with the `$env:OWNER_PASSWORD` /
  `OWNER_PASSWORD='…'` env form; secret inventory table now covers all five files; P1..P13 wording.
- `backend/netherite-app/src/test/java/io/netherite/security/SecretHygieneRegressionTest.java`
  (new, 5 tests) — G-1 main `application.yml` carries no literal BCrypt / 64-hex / agent-key /
  AKIA; G-2 the six production secret bindings are `${ENV:}` placeholders exactly
  (`DB_PASSWORD`, `REDIS_PASSWORD`, `OPA_TOKEN`, `OWNER_PASSWORD_HASH`, `WEBHOOK_SECRET`,
  `MAIL_PASSWORD`); G-3 compose inventories exactly the five `file: ./secrets/<name>` entries;
  G-4 the app service mounts exactly its four runtime secrets and never `db_password`; G-5 the
  entrypoint requires exactly the app mount set.

**Verification:**

| Check | Result |
|---|---|
| PS parse (`verifier`, both generators, scanner) | OK (0 syntax errors) |
| `generate-secrets.sh` `bash -n` | OK |
| `secret-scan.ps1 -SelfTest` | PASS |
| Full-repo `secret-scan.ps1` | **364 files scanned, 0 findings, PASS** |
| Generator in isolated temp dir (synthetic password, real jar) | exit 0; 5 files: db/redis/opa/db_app 64-hex, owner hash 60-char BCrypt; `has-triple-dollar=false`; no value echoed; **0 stray `netherite-bcrypt-*` temp dirs** |
| Generator default-arg invocation (documented form, no `-AppJar`) | exit 0 (PSScriptRoot fix verified) |
| `BcryptTool.java` env-only sentinels | missing env → exit 2; empty env → exit 2; hash on stdout otherwise |
| JVM regression `mvn -o test -pl netherite-app` (JDK 21 `.tools/jdk-21`) | **457/457, 0 failures, BUILD SUCCESS** (incl. SecretHygieneRegressionTest 5/5) |
| Frontend `tsc && vite build` | clean |
| Live P1–P13 `verify-secret-handling.ps1` (Docker engine up, project `netherite`, compose HTTPS edge) | **13 passed / 0 failed, exit 0** |
| P1 compose config exposes no secret values | PASS |
| P2 secrets not baked into the app image (build + image-filesystem grep + packaged yml) | PASS |
| P3 missing/empty owner hash aborts startup (compose abort / entrypoint FATAL exit 1) | PASS |
| P4 full stack deploys healthy (2 app replicas + frontend healthy) | PASS |
| P5 owner login 200 (BCrypt survives intact); wrong password 401 | PASS |
| P6 Redis requires AUTH and authenticates with provisioned secret | PASS |
| P7 PostgreSQL authenticates with provisioned secret; readiness UP | PASS |
| P8 all five secrets byte-identical (`sha256sum`) across every mounted service/replica | PASS |
| P9 no secret value leaks into container logs | PASS |
| P10 graceful stop exits 0/143 (clean shutdown, never SIGKILL) + healthy restart | PASS |
| P11 dependency down/recovery: redis, postgres (readiness DOWN), opa — overall DOWN then UP | PASS |
| P12 Caddy edge routes /v1 /actuator and serves the SPA over HTTPS | PASS |
| P13 static secret-ref contract (entrypoint == compose app mounts == 5-file inventory, no db_password on app) | PASS |

**Blocker (resolved):** the live-container half of Batch G was initially gated on the Docker engine
being down. With the engine running (Docker Desktop, WSL2), the full `verify-secret-handling.ps1`
panel ran to completion — **P1–P13: 13 passed / 0 failed** (see the verification table above).
Two verifier corrections were required and are part of Batch G:

- `verify-secret-handling.ps1` P5 input: interactive `Read-Host -AsSecureString` prompt replaced
  with the env-only `$env:OWNER_PASSWORD` contract (SecureString prompt retained as fallback for
  interactive runs; throws when stdin is redirected and the env var is absent). This matches the
  generators' env-only contract and keeps the password off the command line / process list.
- `verify-secret-handling.ps1` edge transport: previously forced `SITE_ADDRESS=:80` (plain HTTP)
  to dodge Caddy's auto-HTTPS redirect, but Batch F's hardened Caddy `tls` block cannot adapt an
  HTTP-only `:80` listener (`Error: adapting config ... attempts to configure TLS connection
  policies`) — first run failed P4/P5/P12 with the frontend crash-looping. Corrected to probe the
  real TLS edge (`https://localhost` with `curl -k`, same posture as the compose healthcheck) by
  pinning `SITE_ADDRESS=localhost`. No P1–P13 assertion was changed or weakened; the run then
  passed 13/13. Live verification was performed against the local dev stack (project `netherite`,
  `infrastructure/docker/docker-compose.yml`); named volumes `netherite_pgdata` /
  `netherite_redisdata` were preserved throughout (no volume deletion, no `down -v`).
  After the run the stack is back in its normal HTTPS edge mode and fully healthy.

---

### Batch H — dedicated security test suite (complete and verified)

Goal: a clearly-identifiable, independently runnable security test suite that complements the
per-feature tests (Batch A authorization, B SSRF, C audit, D rate-limit fail-closed, F edge/actuator,
G secrets) with fail-closed, filter-boundary, role × surface matrix, approval-tenant-isolation,
DENY side-effect, and error-hygiene regression coverage. No mocks where real integration is feasible,
no new dependencies, no secret printing, nonzero exit on failure.

**Suite location & execution:**

- `backend/netherite-app/src/test/java/io/netherite/securitysuite/` (package `io.netherite.securitysuite`,
  6 classes / 35 tests, JUnit 5; JDK-built-in `com.sun.net.httpserver.HttpServer` stub for the OPA
  engine — no Mockito at the auth/OPA boundary; real `OpaPolicyEngineClientImpl` +
  `PolicyEvaluationService.resolvePrecedence`, real filters via `MockMvc`).
- Independent gate (JDK 21 `.tools/jdk-21`, `.tools/maven/bin/mvn.cmd`):
  `mvn -o test -pl netherite-app -Dtest=io.netherite.securitysuite.*Test -Djacoco.skip=true`
  (bare `-Dtest=io.netherite.securitysuite.*` is rejected by surefire — the `*Test` suffix is required).
- Also runs inside the ordinary full gate: `mvn -o test -pl netherite-app`.

**Coverage by class:**

| Class | Tests | Coverage |
|---|---|---|
| `OpaFailClosedSecurityTest` | 13 | Real OPA client vs a loopback HTTP stub: precedence on valid allow/deny/mixed/approval payloads; advisory `decision` without reasons → DENY; empty / non-map / null / malformed / HTTP 500 / unreachable responses → unsuccessful (fail closed) + `isHealthy()` false + no exception escapes the service; SSRF-guarded URL blocked client-side with zero HTTP requests; null rule set → DENY |
| `FilterBoundarySecurityTest` | 4 | `Cache-Control: no-store` present even on 401 (`/v1/agents`, `/actuator/info`); path variants (trailing slash, double slash, case-change) fail closed with 4xx — no auth bypass; agent-key path tricks → 403; rejection bodies keep `ERR_AUTHENTICATION_FAILED` / `ERR_FORBIDDEN` JSON shape |
| `AuthorizationMatrixSecurityTest` | 6 | Role × surface grid: ORG_ADMIN full management; PLATFORM_ADMIN non-owner → 403 on `/v1/control/organizations` (owner-gated, not role-gated); POLICY_ADMIN policies-only; AUDITOR audit-only; HUMAN_APPROVER approvals-only; AGENT_SERVICE → 403 on every management surface incl. gateway execute, while approvals GET stays 200 (current behavior, see observations) |
| `ApprovalSecurityBoundaryTest` | 5 | Anonymous approve → 401; agent key → 403; ORG_ADMIN passes the filter but is rejected 403 `ERR_FORBIDDEN` at the controller (`HUMAN_APPROVER` literal — divergence locked); cross-tenant approve → 404 `ERR_RESOURCE_NOT_FOUND` (GET → `TICKET_NOT_FOUND`) and the victim ticket stays `PENDING`; same-tenant approve → `APPROVED` |
| `DenialSideEffectSecurityTest` | 2 | OPA DENY → `PolicyEvaluationService` throws, **zero** connector invocations (counting `@Primary` bean override, `allow-bean-definition-overriding`), audit event `eventType=DENY`, `decision=DENY`, password redacted to `[REDACTED]`; the raw secret value is never present in stored redacted parameters |
| `ErrorHygieneSecurityTest` | 5 | 401 (missing / invalid / revoked key) and 403/404 rejection bodies contain no token echo, no `ntk_live_` prefix, no `java.` package paths, no `Exception` / `Caused by` / `StackOverflow` strings |

**Results (this session run):**

| Check | Result |
|---|---|
| Independent suite gate `-Dtest=io.netherite.securitysuite.*Test` | **35/35, 0 failures/0 errors** (surefire reports for all six classes clean) |
| Full JVM regression `mvn -o test -pl netherite-app` | **492/492, 0 failures, BUILD SUCCESS** (457 Batch A–G baseline + 35 Batch H) |
| `test-compile` | clean |
| New dependencies | none (JUnit 5 + JDK built-in HTTP stub) |
| stdout/stderr/secrets | no real token/password bytes printed; rejection-format assertions in `ErrorHygieneSecurityTest` |

**Failures found on the first suite run (all agent-side test-expectation defects, not production bugs):**

- `/actuator` exact root is a public links index (`ActuatorAccessFilter` gates `/actuator/**` only) →
  the gated-surface assertion moved to `/actuator/info` (401 + `no-store`), matching Batch F's
  edge-closed posture for the bare root.
- `/v1/control/organizations` GET is owner-gated at the controller, not filter-role-gated → a
  non-owner PLATFORM_ADMIN now correctly expects 403 `ERR_FORBIDDEN`.
- Cross-tenant POST approve resolves to 404 `ERR_RESOURCE_NOT_FOUND` while GET resolves to
  `TICKET_NOT_FOUND` → both asserted, plus the victim ticket remains `PENDING`.
- ORG_ADMIN approve on a non-UUID target: the controller's role check (403) precedes UUID parsing
  (would-be 404) → expect 403 `ERR_FORBIDDEN`.

**Observations locked as current behavior (product-decision items, not changed):**

- `/v1/approvals` GET is readable by any authenticated role, including `AGENT_SERVICE` — broader than
  the approvals POST decision surface (ADMIN/HUMAN_APPROVER). Consider narrowing reads to
  approval-actionable roles.
- The exact `/actuator` root (links index) is public on the origin; in production the Caddy edge
  `@closed` block 404s it (Batch F), so this only affecting deep links to a dev-profile origin.

**Non-goals preserved:** no assertion weakened, no test disabled, no production code changed for
Batch H; existing `*Test` suites and fixtures untouched; suites intentionally avoid duplicating the
existing `io.netherite.security.*` / approval / policy / connectors coverage.

---

## Batch I — dependency & secret audit (complete and verified)

Goal: inventory the shipped runtime dependency graph (Maven `netherite-app` + frontend), query
the OSV database for known vulnerabilities per exact version, remediate only safe same-line
patch/line-level findings with full regression gates, re-scan to verify reduction, re-run the
Batch G secret audit, and assess supply-chain posture. Full report: `docs/audit/batch-i/report.md`;
all evidence raw files live under `docs/audit/batch-i/`; reproducible scanner/enrichment scripts
are committed there.

### Baseline (2026-09-28)

- Inventory: `mvn -pl netherite-app dependency:list` → **157 unique artifacts**; OSV
  `querybatch` (raw `osv-raw-0-baseline.json`) → **107 findings**; per-advisory enrichment
  (`osv-enriched-baseline.csv`). Boot 3.3.3 / Spring FW 6.1.12, bcprov 1.78.1 (2 **CRITICAL**),
  logback 1.5.15, json-smart 2.5.0 (HIGH), jackson 2.17.2, netty 4.1.115, postgresql 42.7.7.
- Frontend: `npm audit` 2 findings (vite HIGH `server.fs.deny` + `.map` traversal /
  `launch-editor` NTLM; esbuild MODERATE dev-server fetch DoS — dev-time only). e2e `npm audit`
  exit 0, untouched.

### Remediations applied (validated by regression; Maven = backend/pom.xml)

| Change | Rationale | Clears |
|---|---|---|
| `spring-boot.version` 3.3.3 → **3.3.13** | Largest in-line, BOM-managed jump on the 3.3.x (EOL) line — pulls Spring FW 6.1.21, Tomcat 10.1.42, logback 1.5.18, jackson 2.17.3, netty 4.1.122.Final, json-smart 2.5.2, spring-security-crypto 6.3.10, spring-data 3.3.13, micrometer 1.13.15 | 2024–2025 Spring FW/logback/Tomcat/security set incl. CRITICAL CVE-2025-24813, HIGH CVE-2025-22228, CVE-2024-50379/56337, CVE-2024-38816/19/20, CVE-2024-57699 (json-smart), netty 2024/25 set |
| `bouncycastle.version` 1.78.1 → **1.86** | Latest on Central; no app Java code references `org.bouncycastle` directly (bcprov backs spring-security-crypto BCrypt) | All 4 BC advisories incl. 2 CRITICAL (CVE-2025-14813, CVE-2026-8763) |
| dead `<flyway.version>` property removed | Was inert (`10.17.1`); Boot BOM already manages flyway 10.10.0 — graph unchanged before/after | — |
| `org.postgresql:postgresql` → **42.7.13** explicit `dependencyManagement` entry after the BOM import | Runtime-active driver (Hikari→`PgConnection`); same-line patch. A `<postgresql.version>` property override is **inert** — Maven interpolates the imported BOM against its own properties, so an explicit entry after import is required | CVE-2026-42198 + CVE-2026-54291 (both HIGH) |
| frontend `vite` ^5.4.3 → **^6.4.2** (lock regenerated; resolved 6.4.3, esbuild 0.25.12) | Both frontend dev-server advisories fixed only in 6.4.2+; the Dockerfile `npm ci` consumes the updated lock | 2 frontend findings |

### Residual findings (77) — reachability triage (full classified set: `osv-enriched.csv`)

- **NOT APPLICABLE (technology absent / not wired):** Tomcat auth realms / DIGEST / FORM /
  LockOutRealm (app uses custom filters; only `spring-security-crypto` is on the classpath, no
  `spring-boot-starter-security`), WebDAV / WebSocket / SSE / JSP / script-template, HTTP/2
  (plain HTTP behind the Caddy edge), netty *server-side* SNI + TLS-trust + DNS-resolver and
  decoder CVEs (netty is Redisson's deep client dep; `DnsNameResolver` never used; servlet
  server), jackson default-typing / subtyped-array / `InetSocketAddress` SSRF classes (no
  `enableDefaultTyping`/`activateDefaultTyping`, no such intake types), actuator CloudFoundry
  endpoint (not exposed; exposure = health,info,metrics,prometheus, auth-gated), Artemis temp
  dir (no Artemis), spring static-resource handler CVEs (no `addResourceHandlers`/versioned
  resources/static serving), log4j-api MapMessage JSON (logback is the active logger; no
  log4j2 runtime), commons-lang3 recursion DoS (utility, LOW posture), assertj XXE (HIGH,
  **test-scope, not shipped**), xmlunit (LOW, test-scope).
- **NOT WIRED (present but never invoked):** httpclient5 5.3.1 / httpcore5 5.2.5 / httpcore5-h2
  CVEs — **every** outbound REST client is `new RestTemplate(new
  NoRedirectSimpleClientHttpRequestFactory())` (JDK `HttpURLConnection`); OIDC uses the JDK
  `HttpClient`; Boot's `RestTemplateBuilder`/HttpComponents auto-config is never selected.
  Documented; deliberately NOT overridden (no runtime benefit, adds BOM divergence). Recommend
  dependency pruning or the Boot 3.5 line (resolves httpclient 5.6.x anyway).
- **GENUINE LIMITED / only clearable on the 3.5.x line (Boot 3.3.3.x is EOL):** Spring FW 6.1.21
  residual 2026 CVEs — the reachable subset is small (MVC path traversal MODERATE, AntPathMatcher
  LOW; the heavy static-resource/JSP/SSE/SpEL classes are N/A); micrometer 1.13.15 metrics DoS
  (actuator/prometheus exposed but auth-gated); Boot 3.3.13 predictable-temp-dir (containers run
  a fresh `tmpfs /tmp` + non-root); 2026 Tomcat CRITICALs that only patch on 10.1.45+/Boot 3.5
  (plain-HTTP, JSON API, no WebDAV/WebSocket/realms/a11y surface).
- **Action: schedule Boot 3.5.x (Spring 6.2.x) as the follow-up** — it resolves the whole 2026
  wave (Tomcat, micrometer 1.15.x, Spring FW 6.2.x, httpclient 5.6.x); re-run this scan pipeline
  (scripts are committed) after the upgrade.

### Secret audit (Batch G re-verification)

- Scanner (`infrastructure/docker/scripts/secret-scan.ps1`) **self-test PASS**. Full-repo scan
  initially **FAILED (1 finding)**: `ErrorHygieneSecurityTest.java:94` carries the Batch H
  synthetic canary agent-key literal (masked here — see the scanner allowlist), which was not
  allowlisted. Allowlist extended narrowly (repo-test fixture path + scanner-self-scan), then
  **full scan PASS — 0 findings** (`secret-scan-report.txt`; file count = scanned authored surface,
  392 once this report exists under `docs/`). Note: this document must not reproduce canary
  literals verbatim, or the scanner flags the doc itself.
- `.env`: key names only (listed in the report), no secret values — DB/Redis/OPA/owner secrets
  are git-ignored file secrets under `infrastructure/docker/secrets/`.
- Static P13 contract re-verified on the rebuilt stack inputs: `docker-entrypoint.sh`
  `require_secret` set == compose app mounts (`db_app_password`, `redis_password`,
  `owner_password_hash`, `opa_policy_token`; never `db_password`) == 5-file secret inventory —
  intact. The live P1–P13 panel was **not re-run** in Batch I (needs an operator-supplied
  `OWNER_PASSWORD` + full stack rebuild); Docker engine 29.8.0 is available if an operator runs
  it. `SecretHygieneRegressionTest` 5/5 and Batch H 35/35 are green in the final regression.

### Supply-chain posture

- Pinned: `maven:3.9.9-eclipse-temurin-21` (build), `eclipse-temurin:21-jre-jammy` (runtime —
  **floating minor tag**), `node:22-alpine` (**floating minor**), `caddy:2.8-alpine`,
  `postgres:16-alpine`, `redis:7.4-alpine` (**floating patch**); `openpolicyagent/opa:0.68.0-debug`
  exactly pinned. Runtime hardening is already strong (`read_only`, `cap_drop: ALL`,
  `no-new-privileges`, non-root, `tmpfs /tmp`) — the residual risk is **unplanned base-image
  drift** from floating tags.
- Recommendation (documented, not changed in Batch I): pin base images by digest; introduce
  automated dependency-update/scanning (Renovate/Dependabot or scheduled OSV + native `npm
  audit` — the repo has no CI today); add Maven/npm build-artifact checksum/`npm ci
  --ignore-scripts`-style verification for supply-chain observability.

### Regression gates (final state)

| Gate | Result |
|---|---|
| OSV after remediation (final inventory, `osv-findings.csv`/`osv-enriched.csv`) | 157 artifacts → **77 findings** (baseline 107; −28 boot/bcprov, −2 postgresql; intermediates archived as `osv-*-r2-boot-bump.csv`) |
| Full JVM regression `mvn test` (Boot 3.3.13, bcprov 1.86, pg 42.7.13) | **492/492, 0 failures, BUILD SUCCESS** (incl. Batch H 35/35 and SecretHygieneRegressionTest 5/5) |
| Frontend | `npm audit` **exit 0**; `tsc && vite build` clean (vite 6.4.3 / esbuild 0.25.12) |
| e2e | `npm audit` exit 0 (untouched) |
| Secret scanner | Self-test PASS; full scan **392 files, 0 findings, PASS** |

---

## Batch J — docs + ledger finalization (complete and verified)

Goal: reconcile the WS5 ledger against the actual repository evidence for every batch (A–K),
document outstanding limitations and accepted risks precisely, and issue a defensible WS5 final
verdict. This is a documentation and verification phase — no production code, tests, scanners, or
run-time configuration was changed, and no previously completed batch was reopened. Existing
evidence was reused; only targeted, low-cost checks were re-run where stored evidence needed
confirmation.

### Scope

- Reconcile the ledger's stated status against each batch's documented acceptance criteria and the
  on-disk evidence (code, tests, IT suites, audit artifacts, reports).
- Confirm Batch K is recorded as superseded **with** the evidenced replacement (Batch G live
  P1–P13) rather than silently omitted.
- Record the environment-gated `ws2`/`ws3` browser E2Es (never claimed to have passed), the exact
  meaning of "prerequisite C", and the Batch I residual-vulnerability posture.
- Finalize the ledger: Batch J status + the WS5 final verdict, using the ledger's own status
  vocabulary.

### Batch A–I reconciliation (status = ledger; evidence = on-disk, verified this phase)

| Batch | Ledger status | Acceptance / evidence | Reconciliation outcome |
|---|---|---|---|
| A | COMPLETE AND VERIFIED (JVM gate) | `AuthorizationFilter` (`security/filter/AuthorizationFilter.java`), agent/human-session auth hardening, hashed-at-rest keys | Status holds; evidence present |
| B | COMPLETE AND VERIFIED (JVM gate) | `SsrfGuardValidatorImpl` + `SsrfGuardValidatorImplTest`, open-redirect closure, remote-IP precondition | Status holds; evidence present |
| C | COMPLETE AND VERIFIED — 409/409 | Audit ledger append-only/tamper-evidence, session-token hashing, V9/V10 migrations, `409/409` at completion | Status holds; regression count is a historical milestone (later batches raised the total to 492/492) |
| D | COMPLETE AND VERIFIED — real-Redis ITs 44/44 + live panel gates 1–10 | 8 IT suites (DistributedLockIT, DistributedIdempotencyIT, ApprovalExecutionClaimIT, EnterpriseRuntimeIT, PostgresApprovalPersistenceIT, PostgresUpgradePathIT, RealOpaContractIT, RedisRateLimitStoreIT) verified present; live gate table incl. fail-closed/fail-open/breaker | Status holds; evidence present |
| E | COMPLETE AND VERIFIED — real-OPA ITs 11/11 + live panel gates 1–11 | `base.rego`, `PolicyPushService`, `RealOpaContractIT` 4/4 + `RealOpaAuthAndPushIT` 7/7 (both `@EnabledIfSystemProperty(named="netherite.realopa-it")`), live panel + bug fixes documented | Status holds; evidence present |
| F | COMPLETE AND VERIFIED — F1–F11 + 452/452 | `Caddyfile`, hardened `docker-compose.yml`, `SecurityHeadersFilter`/`ActuatorAccessFilter` + tests, live gate table | Status holds; evidence present |
| G | COMPLETE AND VERIFIED — 457/457 + live P1–P13 13/13 | `generate-secrets.ps1`/`.sh`, `BcryptTool.java`, `verify-secret-handling.ps1`, `secret-scan.ps1` (+`SecretHygieneRegressionTest` 5/5) verified present; live 13/13 panel table | Status holds; evidence present |
| H | COMPLETE AND VERIFIED — 35/35 + full regression 492/492 | 6 classes / 35 tests in `io.netherite.securitysuite` verified present; standalone + full-gate results recorded | Status holds; evidence present |
| I | COMPLETE AND VERIFIED — OSV 157→77, 492/492, secret scan 0 | `docs/audit/batch-i/report.md` (12 sections) verified present; PostgreSQL JDBC **42.7.13** in `maven-dependency-list/tree`; OSV baselines/intermediate/final; `maven-test-run.log` (492/492, BUILD SUCCESS); secret-scan self-test + full scan **PASS** re-verified this phase | Status holds; evidence present |
| J | This section | — | COMPLETE AND VERIFIED (finalized below) |
| K | SUPERSEDED — Batch G's live P1–P13 panel | Batch G table records P1–P13 all 13/13; the *live-Docker-verification + final-verdict* scope of K is replaced by Batch G's live panel (and the WS5 verdict issued in this Batch J section) | SUPERSEDED is justified and now explicitly evidenced |

### Cross-batch outstanding items (recorded, not hidden)

- **Environment-gated browser E2Es (`ws2`, `ws3`):** present in `e2e/` but **not executed** as WS5
  gates. `ws2-human-session.e2e.mjs` needs the `test`-profile backend on :8080 + Vite :3000 +
  Keycloak :8180; `ws3-real-sso.e2e.mjs` needs a real Keycloak realm. No claim of execution is made;
  they remain outside the WS5 live panels.
- **Prerequisite C ("`*IT` never executes under the ordinary surefire gate"):** satisfied. `*IT`
  classes are excluded from the ordinary `mvn test` gate by surefire's default includes and, where
  applicable, `@EnabledIfSystemProperty` (e.g. `netherite.realopa-it=true`). Verified: the
  `maven-test-run.log` 492-test gate contains **54 test classes, none ending `IT`**; the Docker/RDBMS
  suites ran only in their explicit Batch D/E live panels. This is the convention the verification
  notes reference as prerequisite C.
- **Batch I residual vulnerabilities (77 OSV references):** not all remediated — accurately
  classified as NOT APPLICABLE / NOT WIRED / GENUINE LIMITED, with the genuine-limited remainder
  accepted for the EOL Boot 3.3.x line and a **Boot 3.5.x follow-up recommended** (`report.md`
  sections 4/10). The closure documentation does **not** imply all 77 were fixed.
- **No CI / automated dependency-update flow** (Renovate/Dependabot absent) and **floating Docker
  base-image tags** (runtime temurin/node minor; caddy/postgres/redis patch) — documented in Batch I,
  retained as follow-up recommendations, not mandatory WS5 blockers.
- **Live P1–P13 not re-run during Batch I** — operator-gated (`OWNER_PASSWORD`); Batch G's live
  13/13 remains the executed evidence; the static secret contract was re-verified instead.
- **Unused Apache HTTP stack (httpclient5/httpcore5)** advisories — reachability-N/A, with pruning
  or Boot 3.5 recommended; no override was applied (documented reason: no runtime benefit).

### Verification performed (Batch J)

| Check | Result |
|---|---|
| All Batch A–I referenced implementation/test files exist (sampled + IT suites full set) | PASS |
| Batch I report present, 12 sections, matches ledger | PASS |
| PostgreSQL JDBC resolved = 42.7.13 (dep list + tree) | PASS |
| `maven-test-run.log` = 492/492, BUILD SUCCESS, zero `*IT` classes in the ordinary gate | PASS |
| `secret-scan.ps1 -SelfTest` and full-repo scan (this phase) | PASS (0 findings, 392 files) |
| Ledger status header vs individual rows vs per-batch sections | CONSISTENT (no residual "PENDING" or duplicate Batch I heading) |
| Batch K supersession — replacement evidence exists (Batch G P1–P13 13/13) | PASS |
| No unrelated batch/module file modified in Batch J | PASS (Batch J touched only this ledger) |
| `.git` metadata present for diff-based review | NO — repository has no Git metadata; not initialized |

### Final WS5 verdict

**COMPLETE WITH DOCUMENTED LIMITATIONS.**

All mandatory WS5 acceptance criteria are satisfied and evidenced: authentication/authorization
and SSRF hardening (A/B), audit integrity (C), distributed fail-closed rate limiting with live
two-replica panels (D), OPA policy rewrite/push + bearer auth with real-OPA ITs and live panels
(E), edge/container/actuator hardening with live F1–F11 gates (F), secrets purge with live P1–P13
13/13 (G), a dedicated independently-runnable security suite 35/35 within a 492/492 regression
(H), and a full dependency + secret audit reducing OSV findings from 107 to 77 with a clean secret
scan (I). Live Docker verification is validly superseded by Batch G. Batch J is now complete.

The workstream closes **with documented, non-blocking limitations**: the residual 77 OSV
references (majority not-applicable/not-wired; genuine-limited remainder explicitly accepted for
the EOL Boot 3.3.x line with a scheduled Boot 3.5.x follow-up), the unexecuted `ws2`/`ws3`
environment-gated browser E2Es, floating Docker base-image tags, the absent CI/dependency-update
flow, and the unused Apache HTTP stack. None of these satisfies an unfulfilled mandatory WS5
criterion; each is a recorded recommendation or an explicitly accepted risk under the established
criteria. This verdict does not certify the product as universally production-ready, nor does it
claim every conceivable security risk has been eliminated; it asserts that the documented WS5
acceptance criteria are met with the above limitations on record.

---

## Verification notes (cross-batch rules)

- Never run `docker`-dependent suites without the engine; `*IT` never executes under the
  ordinary surefire gate (prerequisite C of the WS5 ledger).
- `mvn -o ... -pl netherite-app`; cross-module edits need the module installed first;
  `surefire:test` does not recompile — `test-compile` first.
- Flyway V1..V8 are frozen (byte-identical checksums across `netherite`, `netherite_ws4`,
  `netherite_ws4b`); new schema work ships V9+ only, expand/contract.