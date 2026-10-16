# NetheriteAgent — Architecture & Module Boundaries

## Overview
NetheriteAgent is an enterprise AI Agent Control Plane designed to govern, authorize, and enforce all actions initiated by autonomous AI agents against enterprise systems of record.

---

## Modular Monolith Structure

```
backend/
├── netherite-common        # Canonical models (CanonicalAction, DecisionType, SystemErrorCode)
├── netherite-security      # Multi-tenant security context, Argon2id key hashing
├── netherite-ingestion     # Action ingestion endpoint models & Normalizer SPI
├── netherite-context       # Fact descriptors, FactProvider SPI, DecisionContext model
├── netherite-policy-opa    # Open Policy Agent client, Rego v1 templates, Evaluation SPI
├── netherite-routing       # Deterministic Agent-Resource binding & routing SPI
├── netherite-connectors    # Pluggable ResourceConnectors (REST, GraphQL, MCP) & SSRF guard
├── netherite-state         # Redisson distributed locks, idempotency store, rate limiters
├── netherite-approval      # Asynchronous human approval ticket models & workflow SPI
├── netherite-audit         # Tamper-evident SHA-256 hash-chained audit logging & redaction
├── netherite-llm           # Semantic extraction candidate models & LLM SPI (candidate only)
├── netherite-admin-api     # Administrative REST controllers & DTOs
└── netherite-app           # Spring Boot bootstrap, Flyway migrations, Actuator health
```

---

## Core Principles & Invariants
1. **AI Agent is an Untrusted Initiator**: Every action request is authenticated, normalized, and verified before authorization.
2. **Deterministic OPA Authorization**: All applicable organization policies are evaluated. Conflict resolution enforces `DENY > HUMAN_APPROVAL > ALLOW`.
3. **Infrastructure Failures vs Policy Denials**: Infrastructure errors (OPA down, Redis timeout) return `503 Service Unavailable` (`ERR_SYSTEM_UNAVAILABLE`) and are **never** classified as policy denials (`403 DENY`).
4. **Authoritative Business State**: Enterprise systems of record own the business state. NetheriteAgent owns the enforcement and control-plane state.
5. **Tamper-Evident Audit Logging**: Cryptographic SHA-256 hash chaining guarantees log continuity and tamper detection.
6. **Single Designated Person per Organization**: There are no roles. One organization, at most one designated person; anything else fails closed.

---

## Authorization Model

NetheriteAgent has **no role-based access control**. There is no `role` column, no
permission matrix, no user hierarchy, and no `ORG_ADMIN`/`POLICY_ADMIN`/`AUDITOR`/
`HUMAN_APPROVER`/`AGENT_SERVICE` principal. Two facts decide every request:

| Fact | Source |
|---|---|
| Is the caller a human session or an agent API key? | `AuthenticationFilter` → `principalType` (`USER` / `AGENT`) |
| Is this user the organization's designated person? | `organizations.authorized_person_user_id` (nullable), resolved by `OrganizationAuthorizationService` |

Surface restrictions:

- **Humans** may use management, dashboard, audit, and approval surfaces only when they are
  the designated person of the caller's own organization. An undesignated organization admits
  nobody — configuration, approvals, and audit reads all return `403 ERR_FORBIDDEN`.
- **Agents** are confined to `/v1/actions/**` and `/v1/gateway/execute`; every management or
  approval surface rejects them. Unknown surfaces and unknown principal types fail closed.
- **OIDC/SSO users** are ordinary members. The IdP `adminClaim*` configuration gates whether
  SSO login is admitted at all; it grants no authority and never auto-designates a user.

Lifecycle (owner = the local deployment owner account configured through
`netherite.auth.owner.*`):

- **Bootstrap**: `POST /v1/control/organizations/{orgId}/authorized-person` — owner only, and
  only when the organization has nobody designated (never displaces an existing person). The
  owner's own organization is bootstrapped automatically at first owner login.
- **Transfer**: `PUT /v1/control/organizations/{orgId}/authorized-person` — current designated
  person only, `expectedCurrentUserId` re-checked under a row lock so concurrent transfers
  produce exactly one winner (`409 ERR_CONFLICT`).
- **Clear**: `DELETE /v1/control/organizations/{orgId}/authorized-person` — owner only, the
  deliberate lockout escape hatch that returns the organization to the fail-closed state.

> **Separation-of-duties limitation.** Collapsing all human authority into one person means the
> designated person both administers policy/approval configuration and personally decides the
> human-approval tickets that configuration produces. There is no independent approver, no
> second administrator, and no auditor role to review that person's own actions. The hash-chained
> audit log records everything they do, but reviewing it is itself restricted to them. Do not
> register them as satisfying regulations that require four-eyes or maker-checker controls;
> document the accepted residual risk before deploying it for regulated workloads.

---

## Observability, Logging & Metrics (WS8)

Everything observable is keyed to **one request identifier**. Caddy (the edge) generates a
UUID (`{http.request.uuid}`) at the earliest touch, forwards it to the app as
`X-Correlation-Id`, and records it in its access line. On the JVM side
`CorrelationIdFilter` (order -100, before every security filter) validates the inbound header
(conservative charset, ≤128 chars, else a fresh UUID), holds it in the SLF4J MDC as
`requestId` for the whole request, and echoes it on every response — including 401/429
short-circuits. Edge, app and security-tier lines for one request therefore share one
identifier.

- **App logs**: JSON structured, one object per line (`ts, level, logger, thread, service,
  environment, requestId, message` + event keys). The `structured` Spring profile selects the
  JSON encoder (logback); the default profile is plain text for local shells. A logback
  sanitizer forces control characters into safe forms before JSON emission, so a
  request-derived string can never corrupt the structured stream.
- **Edge access log** (`infrastructure/caddy/Caddyfile`): Caddy 2.8 JSON to stdout with a
  minimal fixed field set — `format filter` drops the default `request/common_log/
  resp_headers/user_id/bytes_read` objects; `log_append` re-adds only `method, path, proto,
  remote_ip`. The path is logged **without its query string** and headers are **never copied**;
  `log_skip /healthz*` + `log_skip /actuator/health*` drop health-probe noise. Requests
  answered purely at the edge (blocked actuator paths, static SPA) never reach the app and
  carry no request id — a documented empty-join case.
- **Metrics**: Actuator exposes `health, info, metrics, prometheus`; `discovery.enabled=false`
  so the `/actuator` links page is 404. `metrics`/`prometheus` are 404 at the edge and 401
  without an authenticated admin session even inside the compose network. Label cardinality is
  bounded — counters/timers key on small enums, mechanism names, or hex hashes of the value
  summarized — and a constant `management.metrics.tags.environment` tag
  (`development|test|production`) separates environments. No header, agent-key, or secret
  value ever becomes a metric label or a log field (canary-tested).
- **Event taxonomy**: security-relevant outcomes are logged as structured events
  (`event=security.auth_failure`, rate-limit WARNs) with a throttled WARN path so a busy
  limiter cannot flood the log stream.

## Operations, Backup & Recovery (WS9)

- **Durability boundary**: PostgreSQL is the only durable data store (organizations, users,
  sessions, audit records, approvals, agents, idempotency). Redis holds **only** TTL-bounded
  transient coordination (distributed locks, rate-limit counters, leader heartbeat) that is
  reconstructible and must **never** be snapshot-restored.
- **Backup**: `infrastructure/docker/scripts/netherite-backup.ps1` produces versioned,
  validated `pg_dump -Fc -Z 9` archives + JSON manifests (schema version, migration history,
  representative counts, SHA-256 — no secrets), with atomic publish, no-overwrite, disk-space
  guard, and retention that never deletes the newest point or drops below the minimum count.
- **Restore**: `infrastructure/docker/scripts/netherite-restore.ps1` validates a backup
  first (magic + SHA-256 vs manifest + `pg_restore --list`), and `-PerformRestore` restores
  into a **disposable** container with full verification (schema/migrations/counts/append-only
  integrity) and guaranteed cleanup. There is no parameter that can target a live database.
- **Ops & testing**: `netherite-ops-checks.ps1` (read-only health + backup integrity,
  exit 0 only if all green) and `netherite-ops-tests.ps1` (disposable regression suite).
- **Objectives**: RPO ≤ 24 h / RTO ≤ 1 h are **proposed targets requiring owner approval**;
  measured RTO, off-host storage, encryption, and scheduled backups are explicitly not
  implemented. Full runbook: `docs/operations-and-recovery.md`.

## LLM Canonical Action Normalization (Steps 1–3)

The canonical contract is unchanged. Steps 1–2 add the deterministic gate that lets a
semantic-extraction result become a `CanonicalAction`, plus a real source of parameter schemas;
Step 3 adds a provider-independent semantic normalization flow (Google Gemini first) that is
**disabled by default**.

- **`SemanticActionPromoter`** (`netherite-ingestion`) promotes an untrusted
  `SemanticExtractionCandidate` into a `CanonicalAction` only when every field is
  deterministically verifiable. It takes identity solely from `TenantContext`, upper-cases and
  format-checks action/resource, requires the (resource, action) pair to resolve in a
  `RegisteredActionCatalog`, rejects identity/authorization control keys anywhere in the
  parameters, enforces a declared `ActionParameterSchema` recursively, rejects unverifiable
  parameters when no schema exists, and fails closed on any infrastructure error. It never logs
  or echoes raw model output or parameter values.
- **`DbRegisteredActionCatalog`** (`netherite-app`) resolves membership against
  `ResourceEntity` + the agent's active `AgentResourceBindingEntity`; an empty `allowedActions`
  list is **not** "allow all".
- **Registered action parameter schemas (Step 2, migration `V14`)** — an operator (the
  organization's single designated authorized person) declares, versions and activates a
  parameter schema per (resource, action) via `/v1/resources/{resourceId}/action-schemas` (a
  nested path that reuses the existing `resources` admin surface — no new authorization surface,
  no RBAC). Definitions are a **strict subset of JSON Schema** (`type`, `required`,
  `properties`, `additionalProperties` default `false`, `items`; unknown keywords rejected;
  depth ≤ 6; ≤ 256 properties) parsed by `ActionParameterSchemaParser` and stored as `jsonb` in
  `action_parameter_schemas`. Rows are `DRAFT` until activated (at most one `ACTIVE` per
  org/resource/action; a composite FK to `resources(id, organization_id)` enforces tenant
  ownership at the database level). `DbActionParameterSchemaProvider` serves only the active,
  re-validated definition; any negative outcome (malformed org, missing/inactive/ambiguous
  resource, no active version) yields "no schema", and an untrustworthy stored definition throws
  so the promoter fails closed.
- **Semantic normalization flow (Step 3)** — `AgentGatewayController` selects among injected
  `ActionNormalizer`s: a fully structured request uses the deterministic `DefaultActionNormalizer`,
  while a request carrying a natural-language `rawPrompt` (and no full structure) uses
  `SemanticActionNormalizer`. That normalizer calls the configured `LlmProvider` (one SPI,
  `netherite-llm`; Google Gemini implemented) to obtain an untrusted `SemanticExtractionCandidate`,
  then runs the **same** `SemanticActionPromoter` before anything reaches the pipeline. A promoted
  action flows through the unchanged OPA / approval / idempotency / routing / SSRF / connector /
  audit chain.
- **Provider selection and safety** — `netherite.llm.provider` defaults to `none` (no provider bean
  exists; semantic requests fail closed). `gemini` binds the key from `GEMINI_API_KEY` and
  `openrouter` from `OPENROUTER_API_KEY` (never in URLs, logs, errors or responses) and each
  fails startup if blank; an unknown provider fails startup. The Gemini adapter uses the native
  `:generateContent` contract with `x-goog-api-key`, the OpenRouter adapter the OpenAI-compatible
  `chat/completions` contract with a Bearer token; both request JSON, apply the operator SSRF
  guard, disable redirects, enforce timeouts and a response-size bound, and fail closed on any
  transport/parse/shape failure. The LLM interprets only — it never authorizes, and the
  deterministic promoter remains the sole gate.
- **Remaining gap**: only Gemini is implemented (OpenAI/Anthropic/Ollama are supported by the
  interface but not built); parameterized semantic candidates for resources without an active schema
  are still (correctly) rejected as unverifiable. Evidence ledger: `docs/llm-normalization.md`.
