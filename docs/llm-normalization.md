# LLM Canonical Action Normalization — Evidence Ledger

Status: **Step 3 complete — a provider-independent LLM normalization flow, with Google Gemini as the
first concrete provider, is implemented and verified; the semantic path is wired into the existing
ingestion pipeline and is disabled by default (`provider=none`).** OpenRouter
(`provider=openrouter`, OpenAI-compatible chat/completions) is implemented as a second
concrete provider through the same interface, gating, and deterministic promotion.
Step 1 (deterministic promotion validator) and Step 2 (operator-authored parameter schemas) remain
intact and unchanged in contract. This ledger records the read-only audit findings and the Step 1,
Step 2 and Step 3 deliverables.

Companion docs: `docs/architecture.md` (section "LLM Canonical Action Normalization").
Current Flyway schema head is **V14** (`docs/production-deployment.md` §16). No new migration was
introduced by Step 3.

---

## 1. Scope

Implement, in deterministic and fail-closed fashion, the two halves a future semantic-extraction
result would need to become a `CanonicalAction`:

- **Step 1** — the validator that promotes an untrusted `SemanticExtractionCandidate` into a
  `CanonicalAction`, and the catalog that resolves a registered (resource, action) pair.
- **Step 2** — a real, tenant-isolated, versioned **source of parameter schemas** so that a
  legitimate parameterized semantic action can be verified deterministically instead of being
  rejected as unverifiable.

Explicitly **out of scope for Steps 1–2**: any LLM SDK, provider, API key, Ollama config, prompt
template, network call, or new Docker service; RBAC; changes to the canonical contract; wiring the
promoter into a live ingestion controller; connector execution / approval / policy changes.

---

## 2. Read-only audit findings (recorded 2026-10-10)

Source inspection across `netherite-llm`, `netherite-ingestion`, `netherite-app`,
`netherite-common`, `netherite-security`, the then-current migrations `V1..V13`, and tests.

| Area | Finding |
|---|---|
| LLM module | Contains exactly 2 files: `LlmProvider` (SPI, **0 implementors**) and `SemanticExtractionCandidate` (DTO). No AI SDK dependency, no prompt templates, no HTTP call, no Ollama reference. |
| LLM config | `netherite.llm.provider` (`LLM_PROVIDER`, default `none`) and `netherite.llm.model` (`LLM_MODEL`, default `gpt-4o-mini`) are **inert** — read by no code. |
| Unused fields | `ActionIngestionRequest.rawPrompt`, `PolicyVersionEntity.naturalLanguagePrompt`, and `NormalizationResult.requiresSemanticFallback` are never consumed (the last is always `false`). |
| Deterministic core | `DefaultActionNormalizer` is real: upper-cases action/resource, UUID action id, identity from `TenantContext`. Canonicalization is deterministic today. |
| Trust boundary | Identity is hardened — `organizationId`/`agentId` always come from `TenantContext`, never the request body. |
| Catalog gap | **No per-resource action catalog and no parameter schema existed.** Resource existence is `ResourceEntity` (`status` default `ACTIVE`); the only action allow-list is `AgentResourceBindingEntity.allowedActions` (`List<String>`, default empty). `ERR_INVALID_PARAMETERS(400)` was defined but unused. |
| Verdict | **PARTIALLY** — the deterministic half is real and verified; the LLM / heterogeneous-format half is absent. |

---

## 3. Step 1 deliverable — deterministic promotion validator

### 3.1 New files

| File | Module | Purpose |
|---|---|---|
| `netherite-ingestion/.../spi/RegisteredActionCatalog.java` | ingestion | Fail-closed SPI: resolve a candidate (resource, action) to a canonical registered target. |
| `netherite-ingestion/.../spi/ActionParameterSchemaProvider.java` | ingestion | SPI supplying a declared parameter schema, if any. |
| `netherite-ingestion/.../model/RegisteredActionTarget.java` | ingestion | Positive catalog match (canonical resource name only — server-derived). |
| `netherite-ingestion/.../model/ActionParameterSchema.java` | ingestion | Declarative required/allowed/typed parameter schema (recursive since Step 2). |
| `netherite-ingestion/.../model/SemanticActionPromotionResult.java` | ingestion | Success/failure + errors + whether a schema was enforced. |
| `netherite-ingestion/.../promotion/SemanticActionPromoter.java` | ingestion | The validator (plain class, no Spring, no logging). |
| `netherite-ingestion/.../promotion/ActionParameterRules.java` | ingestion | Single source of truth for identifier/key/reserved rules (shared with Step 2). |
| `netherite-app/.../ingestion/catalog/DbRegisteredActionCatalog.java` | app | DB-backed catalog: `ResourceEntity` + `AgentResourceBindingEntity`. |

### 3.2 Promotion rules (all fail-closed)

1. **Trusted identity only.** `organizationId`, `agentId`, `userId` come from
   `TenantContext`. Organization and agent identities must be present; `principalType` must
   be `AGENT`. Candidate output can never influence identity.
2. **Well-formed identifiers.** Action type and target resource are trimmed, control
   characters rejected, upper-cased, then matched against
   `^[A-Z0-9]+(?:[:_-][A-Z0-9]+)*$` (action) and `^[A-Z0-9][A-Z0-9._-]*$` (resource) with
   length bounds (128 / 256).
3. **Catalog membership.** The (resource, action) pair must resolve in
   `RegisteredActionCatalog`. `DbRegisteredActionCatalog` rejects: malformed org/agent UUID;
   resource missing, inactive, or matching more than once (ambiguous); no active binding;
   binding with an empty `allowedActions` (**never "allow all"**); action not explicitly in
   the allow-list. `*` is honored only when explicitly stored.
4. **Parameter structure.** Parameter map (default empty) must be within size/`key`/string/
   nesting bounds, keys must match `^[A-Za-z0-9][A-Za-z0-9._:-]*$`, no blank keys, no
   case-variant duplicates (ambiguous), and only JSON-safe value types are accepted.
5. **Reserved keys rejected.** Identity/authorization control keys (`organizationId`,
   `agentId`, `userId`, `principalType`, `tenant`, `decision`, `approved`, `authorization`,
   `role(s)`, `permission(s)`, `admin`, `override`, `bypass`, ...) are rejected anywhere in
   the parameter tree, after lower-casing and stripping `_`/`-`/`.`.
6. **Schema enforcement / unverifiable rejection.** If a schema is declared it is enforced
   (required present, closed-object allowed set, per-type, recursively). If **no schema**
   exists and parameters are supplied, the parameters are rejected as **unverifiable** rather
   than silently accepted. Empty parameters are permitted.
7. **Fail closed on infrastructure failure.** A catalog or schema-provider exception yields a
   rejection (`... failing closed`), never an allow.
8. **No metadata leakage.** The promoted action gets a fresh `actionId`/`timestamp`;
   `idempotencyKey` and `sourceMetadata` are null. Raw model output, model id, and parameter
   **values** are never logged or echoed into errors (only keys/identifiers appear).

The promoted `CanonicalAction` is a conventional action: it must still pass the full existing
OPA authorization, human-approval, idempotency, connector-execution, and audit pipeline. The
validator grants no authority and is independent of those components.

---

## 4. Step 2 deliverable — registered action parameter schemas

Step 2 gives the Step 1 promoter a real, DB-backed schema source: an operator (the organization's
single designated authorized person) can declare, version and activate a parameter schema for a
registered (resource, action) pair. Nothing about the promoter's fail-closed contract changed —
it now simply has a positive data source for the schema branch instead of a permanent gap.

### 4.1 Wire format — strict JSON-Schema subset

Only these keywords are expressible and accepted; **unknown keywords are rejected**, so an
operator cannot smuggle in a feature the promoter does not enforce:

| Keyword | Meaning |
|---|---|
| `type` | One of `STRING`, `NUMBER`, `BOOLEAN`, `OBJECT`, `ARRAY` (case-sensitive). Every node must declare it. |
| `required` | Boolean; when true this property must be present in its parent object. |
| `properties` | Declared children of an `OBJECT` node. |
| `additionalProperties` | Default **`false`** — an object is **closed**: any key not declared is rejected. |
| `items` | Element schema of an `ARRAY` node (required for arrays). |

Grammar invariants (enforced by `ActionParameterSchemaParser`): root must be `OBJECT`; every node
declares a `type`; `ARRAY` declares `items`; `OBJECT`/scalars must not declare `items`; scalars and
arrays must not declare `properties`; property names must be well-formed
(`^[A-Za-z0-9][A-Za-z0-9._:-]*$`, ≤128) and must not collide with a reserved
identity/authorization name; **max depth 6**; **max 256 properties per object**.

### 4.2 New files

| File | Module | Purpose |
|---|---|---|
| `netherite-app/.../db/migration/V14__action_parameter_schemas.sql` | app | Additive migration: table, unique index, composite FK, check + active index. |
| `netherite-app/.../domain/entity/ActionParameterSchemaEntity.java` | app | jsonb-backed, versioned entity (`DRAFT`/`ACTIVE`/`SUPERSEDED`). |
| `netherite-app/.../domain/repository/ActionParameterSchemaRepository.java` | app | Org-scoped lookups (by active status, by version, list). |
| `netherite-app/.../ingestion/schema/ActionParameterSchemaParser.java` | app | Strict-subset parser/validator (untrusted input). |
| `netherite-app/.../ingestion/schema/ActionParameterSchemaException.java` | app | Signals an invalid/untrustworthy schema definition. |
| `netherite-app/.../ingestion/provider/DbActionParameterSchemaProvider.java` | app | `ActionParameterSchemaProvider` implementation (fail-closed). |
| `netherite-app/.../domain/service/ActionParameterSchemaAdminService.java` | app | createDraft / activate / list / findActive (tenant-isolated). |
| `netherite-app/.../api/ActionParameterSchemaController.java` | app | `/v1/resources/{resourceId}/action-schemas` admin surface. |
| `netherite-app/.../api/model/ActionParameterSchemaCreateRequest.java` | app | Request DTO. |
| `netherite-app/.../api/model/ActionParameterSchemaResponse.java` | app | Response DTO. |

### 4.3 Migration V14 (forward-only; V1..V13 untouched)

- `CREATE UNIQUE INDEX uq_resources_id_organization_id ON resources (id, organization_id)` —
  referenced-column support for the composite FK below.
- `CREATE TABLE action_parameter_schemas` — `id`, `organization_id`, `resource_id`,
  `action_type` (128), `version_number`, `schema_definition` **jsonb**, `status` (default
  `DRAFT`), `created_by`, `created_at`, `activated_at`.
  - `uq_action_parameter_schema_version` UNIQUE `(organization_id, resource_id, action_type, version_number)`.
  - `ck_action_parameter_schema_status` CHECK `status IN ('DRAFT','ACTIVE','SUPERSEDED')`.
  - `fk_action_parameter_schemas_resource` FOREIGN KEY `(resource_id, organization_id)` →
    `resources (id, organization_id)` `ON DELETE CASCADE` — a **database-level** guarantee that
    a schema belongs to a resource of the **same** organization (mirrors the V12 composite-FK
    pattern; application code alone would not prevent a cross-tenant row).
- `CREATE INDEX idx_action_parameter_schemas_active ON action_parameter_schemas
  (organization_id, resource_id, action_type, status)` — makes the active lookup a single probe.

### 4.4 Administration, isolation and fail-closed behavior

- **Authorization (no RBAC).** The admin endpoints are nested under `/v1/resources/...` on
  purpose: they reuse the existing organization administration surface, so `AuthorizationFilter`
  gates them with the existing `resources` surface and only the organization's designated
  authorized person may reach them. The controller additionally calls
  `OrganizationAuthorizationService.requireAuthorizedPersonForTenant()` +
  `requireTenantOrganizationId()`; the organization is taken from the trusted session, never from
  the request. **No new authorization surface or filter rule was introduced.**
- **Versioned + inert until activated.** `createDraft` inserts a `DRAFT` and returns it; drafts
  are never served. `activate` validates the definition again, marks the current `ACTIVE` version
  `SUPERSEDED`, and is idempotent when the target is already `ACTIVE`. Version numbers increment.
- **Tenant isolation.** Every service/repository lookup is scoped by `organizationId`. An
  unknown resource, or a version addressed against a different resource, resolves to
  `ERR_RESOURCE_NOT_FOUND` (404); a caller addressing another organization's resource id gets 404.
- **Provider fail-closed outcomes.** `DbActionParameterSchemaProvider` returns "no schema"
  (→ parameters become unverifiable → rejected) when: the organization identifier is malformed;
  the resource is missing, inactive, or matches more than once (ambiguous); no row or no
  **active** version exists. If a stored definition no longer parses/validates it **throws**,
  which propagates to the promoter and makes it **fail closed** rather than enforce a schema it
  cannot trust. A definition is re-validated on **read** as well as on write, so a corrupted or
  hand-edited row can never become a weaker rule.
- **Recursive enforcement.** `SemanticActionPromoter.validateNodeAgainstSchema` walks the
  declared schema against the candidate parameters: closed objects reject undeclared keys
  (`unsupported parameter '<path>'`), `required` properties must be present
  (`missing required parameter '<path>'`), scalars/arrays are type-checked
  (`parameter '<path>' has an invalid type (expected ...)`), and nesting is bounded.

---

## 5. Step 3 deliverable — provider-independent semantic normalization (Google Gemini first)

Step 3 adds the missing half: a real, provider-independent bridge from natural language to the
Step 1/2 deterministic pipeline. There is exactly **one** provider contract (`LlmProvider`), exactly
**one** candidate format (`SemanticExtractionCandidate`), and exactly **one** deterministic gate
(`SemanticActionPromoter`). Google Gemini is the first concrete provider; the architecture supports
future providers through the same interface. The subsystem is **disabled by default** and the LLM
**never** authorizes anything.

### 5.1 Request normalization flow

1. The gateway receives a request that is either fully structured (`action` + `resource`) or carries a
   natural-language `rawPrompt`.
2. `AgentGatewayController` selects the first `ActionNormalizer` that `supports` the request:
   - a fully structured request always uses the deterministic `DefaultActionNormalizer` (unchanged);
   - a `rawPrompt` request without full structure uses `SemanticActionNormalizer`.
3. The semantic normalizer calls the configured `LlmProvider.extractCandidateAction(prompt, orgId)`
   and receives an **untrusted** `SemanticExtractionCandidate` (or nothing).
4. The candidate is handed to the deterministic `SemanticActionPromoter`, which enforces trusted
   identity, catalog membership, reserved-key rejection and parameter-schema conformance.
5. Only a **successful** promotion produces a `CanonicalAction`; it then flows through the unchanged
   `ConnectorExecutionService` chain (OPA → approval → idempotency → routing → SSRF → connector →
   audit).
6. Every failure — no provider, provider error, invalid/unpromotable candidate — fails **closed**.

### 5.2 New and changed files

| File | Module | Purpose |
|---|---|---|
| `netherite-app/.../llm/config/LlmProperties.java` | app | `netherite.llm` binding (provider, timeouts, bounds, `gemini.*`, `openrouter.*`). Default `provider=none`; `openrouter` selects the OpenRouter chat/completions provider. |
| `netherite-app/.../llm/config/LlmProviderConfiguration.java` | app | Declares the always-on `SemanticActionPromoter` bean and the opt-in provider bean. |
| `netherite-app/.../llm/config/LlmConfigurationValidator.java` | app | `!test` fail-fast: unknown provider selector refuses startup. |
| `netherite-app/.../llm/provider/LlmCandidateDecoder.java` | app | Strict (fail-closed) decoder from model text to candidate; rejects unknown/trailing/missing fields. |
| `netherite-app/.../llm/provider/GeminiLlmProvider.java` | app | Google Gemini `generateContent` provider (native REST contract). |
| `netherite-app/.../llm/semantic/SemanticActionNormalizer.java` | app | The semantic `ActionNormalizer` that engages the provider only when needed. |

Changed: `GatewayActionRequest` (adds `rawPrompt`; `action`/`resource` are now optional at the bean
level but must be present for the structured path — enforced by the normalizers), `AgentGatewayController`
(selects among injected `ActionNormalizer`s instead of hard-wiring the deterministic one),
`application.yml` (`netherite.llm` block expanded; no secrets). `netherite-llm` is unchanged (keeps the
dependency-free SPI/DTO).

### 5.3 Gemini provider contract

- `POST {base-url}/v1beta/models/{model}:generateContent`.
- Authorization via the `x-goog-api-key` header — the key is **never** placed in the URL, logs, errors,
  or responses.
- Request body: `systemInstruction.parts[].text`, `contents[].parts[].text` (role `user`),
  `generationConfig.responseMimeType = application/json`, `generationConfig.maxOutputTokens`,
  `generationConfig.temperature = 0.0`.
- Response: text is read from `candidates[0].content.parts[].text`; the model identifier is taken from
  `modelVersion` when present.
- **Structured output choice:** `responseMimeType: application/json` plus an explicit system prompt is
  used instead of Gemini's `responseSchema`, because that schema dialect cannot faithfully express an
  arbitrary `parameters` object; the model output is still decoded under a strict, closed contract.
- **Fail-closed and bounded:** the operator-configured SSRF guard validates the endpoint, redirects are
  disabled, connect/read timeouts are enforced, the response body is size-bounded, and any transport
  error, non-2xx status, malformed body, missing candidate, unknown field or trailing content yields
  "no candidate" (the request then fails closed).

### 5.4 Provider selection and configuration

- `netherite.llm.provider` defaults to `none`: **no** `LlmProvider` bean exists and any semantic request
  fails closed.
- `netherite.llm.provider=gemini` creates exactly one provider bean. The key is bound from
  `GEMINI_API_KEY` (`netherite.llm.gemini.api-key: ${GEMINI_API_KEY:}`); a blank key **fails startup**
  with a message that names the variable and never contains a key value.
- An unrecognized provider selector **fails startup** (`LlmConfigurationValidator`, `!test` profile) so
  a typo cannot silently disable semantic normalization.
- No secret is checked in: `application.yml`, images, logs, exception messages, test output and client
  responses contain no API key.

### 5.5 Provider extension point

Adding OpenAI, Anthropic or an Ollama adapter requires only a new class implementing `LlmProvider` plus
a `@ConditionalOnProperty` bean in `LlmProviderConfiguration`; the normalizer, promoter, decoder,
identity model and full downstream pipeline are provider-agnostic and unchanged.

---

## 6. Evidence

All numbers below are from tests executed **in the Step 2 session** (JDK 21, offline, bundled
Maven). Nothing is quoted from the earlier Step 1 baseline run.

- **Focused ingestion tests**: `SemanticActionPromoterTest` — **40/40 pass** (was 33 after
  Step 1; +7 recursive/nested-array/open-object cases for the Step 2 schema model).
- **Focused app tests** (new Step 2 classes): `ActionParameterSchemaParserTest` **16/16**,
  `ActionParameterSchemaAdminServiceTest` **11/11**, `DbActionParameterSchemaProviderTest`
  **6/6**, `ActionSchemaAdminSecurityTest` (MockMvc) **6/6** — **39/39 total**.
- **PostgreSQL integration** (`pgverify` profile, real PostgreSQL 16, Flyway V1..V14 +
  `ddl-auto: validate`): `PostgresActionSchemaPersistenceIT` **3/3**, `EnterpriseRuntimeIT`
  **6/6**, `PostgresApprovalPersistenceIT` **6/6**, `PostgresUpgradePathIT` **3/3** — **18/18,
  0 failures**. V14 applied cleanly on top of an existing V13 database (proves the upgrade
  path); a duplicate `(org, resource, action, version)` insert is rejected by PostgreSQL with
  `uq_action_parameter_schema_version`; the jsonb definition round-trips; a draft is invisible
  to the provider until activated; another organization cannot see or address the schema.
- **Full backend reactor regression** (`mvn -o -pl netherite-app -am test`, JDK 21, offline):
  **BUILD SUCCESS**. `netherite-app` **595/595** (baseline 556 at Step 1; +39 Step 2 tests),
  `netherite-ingestion` **40/40** (baseline 33; +7), `netherite-common` 1/1,
  `netherite-security` 1/1 — **637 tests, 0 failures, 0 errors, 0 skipped**.
- **Secret-scan**: `secret-scan.ps1 -SelfTest` → `SELF-TEST: PASS`; repo scan → **456 files,
  0 findings**. One narrow approved-list entry was added for the WS9 ledger's reference to the
  already-approved WS8 synthetic canary literal (`ntk_live_ws8.canary-secret`), matching the
  existing `ci-cd.md` / `workstream-8-observability.md` entries.
- **Configuration**: `docker compose config -q` → exit 0. Migration set is contiguous
  `V1..V14` (no gaps/duplicates) across `db/migration` + `db/postgresql`.
- **Toolchain**: JDK 21 (`JAVA_HOME=.tools\jdk-21`, `release 21`), bundled Maven, offline.

### Step 3 evidence

- **Focused Step 3 tests** (JDK 21, offline): `LlmCandidateDecoderTest` **9/9**,
  `GeminiLlmProviderTest` **10/10**, `SemanticActionNormalizerTest` **11/11**,
  `LlmConfigurationValidatorTest` **3/3**, `LlmProviderConfigurationTest` **4/4** — **37/37**.
  In addition `GatewayIntegrationTest` grew from 37 to **39** with the two semantic-path cases
  (`SEM-1` rawPrompt without a provider fails closed 400; `SEM-2` a structured request still bypasses
  the semantic path).
- **Full backend reactor regression** (`mvn -o -pl netherite-app -am test`, JDK 21, offline):
  **BUILD SUCCESS**. `netherite-app` **634/634** (baseline 595; +39 Step 3 tests); the upstream
  modules already built in the reactor (`netherite-ingestion`, `netherite-common`, `netherite-security`)
  remain green and unmodified by Step 3.
- **Provider behavior proven by tests**: native Gemini request shape and headers (API key header, JSON
  response type, system instruction, prompt content); candidate decoding (valid; fenced JSON; and
  rejection of unknown fields, trailing tokens, missing fields, wrong parameter shape, non-JSON);
  fail-closed on SSRF deny (provider never called), non-2xx, oversized body, transport error, malformed
  model output and missing candidates; blank API key refuses construction without echoing a value;
  context creation selects the Gemini bean only when configured with a key and fails fast otherwise;
  `provider=none`/unset yields no provider bean; the semantic normalizer fails closed with no provider,
  an unavailable provider, an empty candidate, a provider error, a promotion failure and a non-agent
  context, and returns the canonical action only on a successful promotion.
- **PostgreSQL integration**: **not executed in this session — the local PostgreSQL/Docker environment
  was unavailable** (`localhost:5433` closed; Docker daemon unreachable). This is an external
  environment limitation, not a regression: Step 3 introduces **no** migration, entity or schema
  change, so the Step 2 PG results (`V1..V14`, 18/18) remain the most recent applicable run.
- **Configuration**: `netherite.llm` binds provider/model/timeouts/bounds plus `gemini.base-url`,
  `gemini.api-key` (`${GEMINI_API_KEY:}`) and `gemini.default-model`. No API key is present in
  `application.yml`, images, logs, exception messages or test output. No new Docker service.

### Coverage matrix (Step 3)

| Requirement | Where proven |
|---|---|
| Provider contract + candidate format unchanged; one adapter (Gemini) first | `GeminiLlmProviderTest`, `LlmProviderConfigurationTest` |
| Correct native Gemini request format (endpoint, header key auth, JSON response type, prompt/system) | `GeminiLlmProviderTest` |
| Parsing of provider output; rejection of malformed/unknown/trailing/missing fields | `LlmCandidateDecoderTest` |
| Fail-closed on SSRF deny / non-2xx / timeout / oversized / malformed / empty | `GeminiLlmProviderTest` |
| Provider disabled by default; config-driven selection; missing key fails fast; unknown selector fails startup | `LlmProviderConfigurationTest`, `LlmConfigurationValidatorTest` |
| Semantic flow wired into the existing path; deterministic fallback; success only after promotion | `SemanticActionNormalizerTest`, `GatewayIntegrationTest` |
| No secret leakage (key/raw output never logged, echoed or returned) | `GeminiLlmProviderTest`, `LlmCandidateDecoderTest` |
| Existing structured behavior unchanged | `GatewayIntegrationTest` (all prior cases) |

### Coverage matrix (Step 2)

| Requirement | Where proven |
|---|---|
| Valid flat/nested/array/open schema parses; malformed + unsupported keywords rejected | `ActionParameterSchemaParserTest` |
| Draft/versioning/supersede/idempotent activate/unknown-resource/invalid-action/invalid-schema | `ActionParameterSchemaAdminServiceTest` |
| Provider serves active only; empty on malformed/ambiguous/inactive/absent; throws on untrusted stored def | `DbActionParameterSchemaProviderTest` |
| Designated-person create→activate; drafts inert; non-designated 403; anonymous 401; invalid 400; cross-org 404 | `ActionSchemaAdminSecurityTest` |
| jsonb round-trip, tenant isolation, DB-enforced version uniqueness, V14 upgrade on real PG | `PostgresActionSchemaPersistenceIT` |
| Recursive enforcement (missing required, unsupported key, wrong type, nested, arrays, depth) | `SemanticActionPromoterTest` |
| Migration count/name/checksum consistency at V14 | `PostgresApprovalPersistenceIT`, `PostgresUpgradePathIT`, `EnterpriseRuntimeIT` |

### Commands (from `backend/`)

```
$env:JAVA_HOME='<repo-checkout>\.tools\jdk-21'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
& "<repo-checkout>\.tools\maven\bin\mvn.cmd" -o -pl netherite-ingestion -am test "-Dtest=SemanticActionPromoterTest" "-Dsurefire.failIfNoSpecifiedTests=false"
& "<repo-checkout>\.tools\maven\bin\mvn.cmd" -o -pl netherite-app -am test "-Dtest=ActionParameterSchemaParserTest,ActionParameterSchemaAdminServiceTest,DbActionParameterSchemaProviderTest,ActionSchemaAdminSecurityTest" "-Dsurefire.failIfNoSpecifiedTests=false"
& "<repo-checkout>\.tools\maven\bin\mvn.cmd" -o -pl netherite-app -am test "-Dtest=PostgresApprovalPersistenceIT,PostgresUpgradePathIT,EnterpriseRuntimeIT,PostgresActionSchemaPersistenceIT" "-Dsurefire.failIfNoSpecifiedTests=false"
```

Step 3 (focused + full regression):

```
$env:JAVA_HOME='<repo-checkout>\.tools\jdk-21'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
& "<repo-checkout>\.tools\maven\bin\mvn.cmd" -o -pl netherite-app -am test "-Dtest=LlmCandidateDecoderTest,GeminiLlmProviderTest,SemanticActionNormalizerTest,LlmConfigurationValidatorTest,LlmProviderConfigurationTest,GatewayIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false"
& "<repo-checkout>\.tools\maven\bin\mvn.cmd" -o -pl netherite-app -am test
```

Running one real Gemini API call (never prints the key):

```
$env:JAVA_HOME='<repo-checkout>\.tools\jdk-21'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
$env:LLM_PROVIDER='gemini'
$env:GEMINI_API_KEY='<your-key-from-the-environment>'   # already exported; never commit it
& "<repo-checkout>\.tools\maven\bin\mvn.cmd" -o -pl netherite-app -am spring-boot:run
# then POST a rawPrompt to /v1/gateway/execute with a valid X-Netherite-Agent-Key
```

---

## 7. Known limitations / owner decisions

- **No schema authoring UI.** Schemas are administered through the REST surface only; there is no
  frontend for it.
- **`allowedActions`-only resources remain parameter-less for semantic promotion.** If a resource
  has no active schema and a candidate supplies parameters, the promoter still (correctly) rejects
  them as unverifiable. Accepting schema-less parameters is a deliberate non-goal.
- **One active version per (org, resource, action).** Superseded versions are retained for audit
  but never served; there is no scheduled cleanup of `SUPERSEDED` rows.
- **Schema grammar is intentionally narrow.** Comparisons, enums, formats, numeric ranges,
  `oneOf/anyOf`, and `$ref` are unsupported and rejected — not silently ignored.
- **Only Gemini is implemented.** OpenAI/Anthropic/Ollama are *supported by the interface* but not
  implemented; no speculative adapters were added. Selecting an unknown provider fails startup.
- **`responseMimeType` guidance, not `responseSchema`.** Gemini is asked for JSON and the output is
  decoded under a closed contract; a model that returns extra or missing fields yields a fail-closed
  rejection rather than a coerced action.
- **Semantic extraction is best-effort; authorization is not.** A semantic request that the model
  cannot map cleanly to a registered (resource, action) pair with a conforming schema fails closed.

## 8. Remaining work (not in Steps 1–3)

1. **Additional providers**: implement `LlmProvider` for OpenAI / Anthropic / a local Ollama endpoint
   behind the same config flag and deterministic gate.
2. **Owner decision on semantic surfacing**: whether to expose `rawPrompt` on additional agent
   surfaces (beyond the gateway), and whether to retain/redact `rawModelOutput`.
3. Schema-authoring surface in the admin UI, and an owner decision on superseded-row retention.
