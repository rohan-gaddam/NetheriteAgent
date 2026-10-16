# Workstream 7 — Deployment, Secrets & CI/CD (evidence ledger)

Status: verified 2026-10-06 — see §5. Companion docs:
`docs/production-deployment.md` (deployment), `docs/ci-cd.md` (pipeline).

---

## 1. Objective & acceptance

Complete the deployment/secrets/CI-CD story so the repository is:
(1) production-deployable with a coherent compose topology, HTTPS edge, and hardened
images; (2) secrets-secure end to end (host-file secrets, no committed/rendered/baked
values, build contexts that cannot leak material); (3) validated by a real CI pipeline plus
local equivalents for all environment-gated steps; (4) documented, with the deployment
procedure and known limitations explicit.

Acceptance specifics targeted by WS7:

- base/deploy images digest-pinned (no floating tags, no `latest`);
- root `.dockerignore` excludes `**/secrets/`, `**/.env`, and the OPA policies-data dir,
  with the exclusion **tested** by a build-context leak gate;
- CI workflow exists and is syntactically valid: backend regression (Java 21/Maven),
  frontend locked build (Node 22), full secret scan (self-test + scan), compose config
  validation, image builds + context-leak gate, OSV dependency scan (non-gating, WS5
  baseline);
- CD deployment template exists, environment-gated, secret-safe (env secrets → umask 0177
  files, never echo/build-arg/artifact), **documented as not executed** in this repository;
- docs refreshed: production-deployment.md (V1..V13 migrations, digest pinning, WS7 CI/CD
  section), new ci-cd.md and this ledger;
- local verification green: backend regression, pgverify ITs (PG on localhost:5433),
  frontend build, secret scan (clean + SelfTest), compose config, both image builds,
  compose stack up + health + P1/P2 leak checks, workflow YAML parse validation.

## 2. Inventory before WS7 (baseline)

| Area | State at start of WS7 |
|---|---|
| Compose stack | `infrastructure/docker/docker-compose.yml`: app/migrate/frontend/postgres/redis/opa + caddy-baked frontend; file secrets block; named volumes; healthchecks; resource limits; fail-closed OPA; single-migrator |
| Secrets | Real values already provisioned in `secrets/` (db_password, db_app_password, redis_password, opa_policy_token — 64 B each; owner_password_hash — 60 B). Generator scripts + secrets.example exist |
| Builds | backend multi-stage (maven → temurin-jre, non-root 10001); frontend multi-stage (node → caddy); root + backend `.dockerignore` present |
| CI/CD | **None** (`.github/` absent). Documented as a gap in WS4/WS5 follow-ups |
| Images | Floating tags everywhere (postgres:16-alpine, redis:7.4-alpine, opa:0.68.0-debug, caddy:2.8-alpine, node:22-alpine, maven:3.9.9-eclipse-temurin-21, eclipse-temurin:21-jre-jammy) |
| Content leak risk | Frontend build context = repo root (`../..`); root `.dockerignore` did **not** exclude `secrets/` or `.env` |

## 3. Changes implemented (WS7)

### 3.1 Supply-chain hardening

- **Digest pinning** — replaced floating tags with exact `@sha256:` digests resolved from the
  locally-pulled manifests on 2026-10-06 (linux/amd64):
  - `postgres:16-alpine@sha256:721873c34ceb9f8d8fc265984940dc982404c105f19ad51be9fdc5970a6080ea`
  - `redis:7.4-alpine@sha256:858f009f9709ce576febc734aa78b8f6d624b82571f9ddb6bda4377c833b3499`
  - `openpolicyagent/opa:0.68.0-debug@sha256:3b451aebbbd3bfd9833517d50e12b35f4244a5e45ea5b0ce0515ca309a910216`
  - `caddy:2.8-alpine@sha256:af32e97399febea808609119bb21544d0265c58a02836576e32a2d082c262c17`
  - `node:22-alpine@sha256:0a7108bf6c7bf5de370ffb1a3ed6be93d405b43ff159f681a8d18c0e2bc2e402`
  - `maven:3.9.9-eclipse-temurin-21@sha256:3a4ab3276a087bf276f79cae96b1af04f53731bec53fb2e651aca79e4b10211e`
  - `eclipse-temurin:21-jre-jammy@sha256:f04fb34e053148344e83317976114ec3f37e4b830ec8bdab5a2fe3cecd7d010b`
  Each was captured with `docker image inspect --format '{{index .RepoDigests 0}}' <tag>`
  after a pull, i.e. the digest the daemon actually recorded. File edits: `docker-compose.yml`
  (postgres, redis, opa), `frontend/Dockerfile` (node build stage + caddy runtime),
  `backend/netherite-app/Dockerfile` (maven build stage + temurin runtime).
- **Root `.dockerignore` hardening** — added `**/secrets/`, `**/.env`, `**/policies-data/` and
  `infrastructure/docker/.p3tmp` so the whole-repo frontend build context cannot carry secret
  material into image layers. (Proved by the context-leak gate, §5.)

### 3.2 CI pipeline — `.github/workflows/ci.yml`

Jobs (each a hard gate; `permissions: contents: read`; no repo/env secrets configured):

1. `backend` — Java 21 (Temurin, Maven cache) → `mvn -B -ntp -pl netherite-app -am test`
   (whole-reactor regression). Surefire reports uploaded on failure.
2. `frontend` — Node 22 (npm cache, lockfile path) → `npm ci` → `npm run build`.
3. `secret-scan` — scanner `-SelfTest` then full repo scan (`-ReportFile`), report uploaded.
4. `compose-validate` — creates git-ignored placeholder secret files (CI-only, synthetic,
   incl. `gemini_api_key` and `openrouter_api_key`), asserts every compose-declared `secrets.file:` source is
   provisioned (since `docker compose config` alone does not fail on a missing file), then
   `docker compose config --quiet`, then the entrypoint LLM-gating test
   (`scripts/verify-llm-provider-gating.sh`, covering none/gemini/openrouter).
5. `docker-build` — plants a unique probe in `secrets/probe.txt`, builds the
   whole-repo-context frontend image, `grep`s the container FS for the probe (leak gate),
   then builds the app image with pinned base images.
6. `osv-scan` — `google/osv-scanner` (non-gating via `continue-on-error`, SARIF uploaded).
   Policy: WS5 Batch I accepted a 77-finding baseline with fixed-version tracking
   (`docs/audit/batch-i/report.md`); the scan still runs on every PR so the baseline cannot
   silently regress.

### 3.3 CD template — `.github/workflows/cd-deploy.yml`

- `workflow_dispatch` only; `[self-hosted, production]` runner; `environment: production`
  (deploy secrets + required reviewers). Marked template — never triggered by push/PR and
  **not executed in this repository** (no remote, no host).
- Secret handling: env secrets (`DB_PASSWORD`, `DB_APP_PASSWORD`, `REDIS_PASSWORD`,
  `OPA_POLICY_TOKEN`, `OWNER_PASSWORD_HASH`, and optional `GEMINI_API_KEY` /
  `OPENROUTER_API_KEY`) written to git-ignored `secrets/*` with `umask 0177` +
  `chmod 600`; no `set -x`, no secret echoes, no `--build-arg` secrets, no secret
  values in artifacts. `gemini_api_key` / `openrouter_api_key` are always materialized
  (empty when no key is supplied — valid unless that provider is selected); a selected
  provider with a missing/empty key fails the deploy before any image is built, and the app
  entrypoint additionally fails closed at boot. Non-secret `.env` must be pre-provisioned by the
  operator (validate-before-deploy).
- Steps: build → `docker compose run --rm migrate` → `up -d --scale app=N` →
  edge readiness curl.

## 4. Security analysis (WS7 additions)

- **Digest pinning** removes tag-mutation and `latest` drift at the boundary: the exact
  manifest is what runs. Re-resolution procedure documented for intentional upgrades.
- **Context-leak elimination** closes the one build path that could bake `secrets/`/`.env`
  into an image; a CI gate enforces it per run (defense against regression).
- **CI/CD secret model**: the CI pipeline carries zero secrets; the CD path moves values
  directly from a protected Environment to host files (mode 600) that compose already consumes
  at `/run/secrets`. No secret transits an image layer, a build arg, a log line, or an artifact
  (P1/P2-style checks re-run locally in §5).

## 5. Verification

### 5.1 Local-equivalent gates (environment-gated steps replayed locally)

| Check | Command | Result |
|---|---|---|
| Backend regression | `mvn -o -pl netherite-app -am test` | **526/526 green** (0 failures/errors) |
| pgverify ITs | `mvn -o -pl netherite-app -am test "-Dtest=PostgresApprovalPersistenceIT,PostgresUpgradePathIT,EnterpriseRuntimeIT" "-Dsurefire.failIfNoSpecifiedTests=false"` (PG `localhost:5433`) | **15/15 green** (6+6+3) |
| Frontend build | `npm run build` (frontend/) | EXIT 0 (tsc + vite, dist emitted) |
| Secret scan | `powershell -File infrastructure/docker/scripts/secret-scan.ps1` (+ `-SelfTest`) | **PASS** — SelfTest PASS, repo scan **0 findings** (404 files) |
| Compose config | `docker compose config --quiet`; rendered output searched for every real secret value | **PASS** — valid; P1: no value appears in rendered config |
| Image builds | `docker compose build app frontend` (pinned digests) | **PASS** — both images built; all 7 `@sha256:` refs resolve locally |
| Context-leak gate | probe marker in `secrets/probe.txt` → full root-context `COPY . /ctx` into alpine → grep `/ctx` | **PASS** — marker absent from the copied context (root `.dockerignore` works) |
| Stack health | `docker compose up -d --scale app=2` + `docker compose ps` + edge readiness | **PASS** — app×2, frontend, opa, postgres, redis all healthy; migrate exited 0; edge `{"status":"UP"}` |
| Runtime auth | redis AUTH ping; OPA bearer `/health`; PG `pg_isready` | **PASS** — PONG; `{}`; accepting connections |
| Leak checks | compose config, image `docker inspect` (config/env), `docker history --no-trunc` (both images), running `Config.Env` (app×2, frontend), bounded `docker logs` tail ×6 containers — all searched for every real secret value | **PASS** — no secret value on any rendered/inspectable/log surface |
| Workflow YAML | `yaml.safe_load` on `ci.yml`, `cd-deploy.yml` | OK 2026-10-06 (parse-verified; GitHub-native parser target) |

### 5.2 Explicitly environment-gated (NOT executed; documented, not claimed)

| Check | Why gated |
|---|---|
| Remote CI execution (github runner, real actions) | no Git remote / repository hosted on GitHub |
| OSV on public runners | same CI gate |
| CD activation / production deployment | no production host attached; template only |
| Owner-login through the edge (P5) | owner password not available outside the secret generator; password never stored |

## 6. Documentation

- `docs/production-deployment.md` — refreshed to V1..V13 migrations (incl. `db/postgresql`
  V10/V11 + pgverify 15/15), digest-pinning text, repository layout (`.github/`, new docs),
  and new §26 CI/CD.
- `docs/ci-cd.md` — new canonical pipeline doc.
- `docs/local-development.md` — Node 22 (CI) note + §5 CI/CD local equivalents.
- `docs/architecture.md` — no changes needed (no deployment/CI content to correct).

## 7. Status

**COMPLETE AND VERIFIED** (2026-10-06).

Outstanding:

- Blocking: none identified.
- Non-blocking / future (WS8+): remote CI activation, hosted PostgreSQL integration suite in
  CI, OSV-finding remediation cadence, production host deployment rehearsal, scheduled
  dependency-update automation (Renovate/Dependabot) to pair with digest pinning.