# NetheriteAgent — CI/CD (WS7)

This document is the canonical description of the repository's CI/CD. It accompanies the
deployment documentation (`docs/production-deployment.md`) and the WS7 evidence ledger
(`docs/workstream-7-deployment-secrets-cicd.md`).

## 1. Scope & principles

- **CI validates; CI never deploys.** Deployment from CI is a separate, explicitly triggered,
  environment-gated workflow.
- **Supply-chain hygiene**: every build-stage and deploy-time image is digest-pinned; the
  build context can never contain secret material (root `.dockerignore` excludes `**/secrets/`,
  `**/.env`, and the OPA policies-data dir; a dedicated CI job proves this per run).
- **No secrets in CI**: the CI pipeline has no repository or environment secrets configured.
  Compose validation and image builds use synthetic placeholders only. Real deployment secrets
  live in host files / GitHub Environments — never in `.env`, never in build args, never in
  image layers.
- **Honest gates**: the two largest external soundness checks in this repository — remote CI
  execution and production deployment — are environment-gated (no Git remote, no production host
  attached) and are documented as such. They are never claimed as "passed".

## 2. Pipeline (`ci.yml`)

Runs on `push` (main), `pull_request`, and `workflow_dispatch`. `permissions: contents: read`
only. Each job is an independent gate; a job failure fails the run.

| Job | Does | Local equivalent |
|---|---|---|
| `backend` | Java 21 (Temurin, Maven cache via `setup-java`) → `mvn -B -ntp -pl netherite-app -am test` (whole-reactor regression, 556 tests incl. the WS8 observability suite) | `mvn -o -pl netherite-app -am test` (offline) |
| `frontend` | Node 22, `cache: npm` → `npm ci` → `npm run build` (`tsc && vite build`) | `npm ci && npm run build` |
| `secret-scan` | PowerShell scanner self-test then full repository scan (`secret-scan.ps1 -SelfTest`, `secret-scan.ps1 -ReportFile ...`), uploads report. Scanner allow-lists synthetic **test literals only** by explicit path+rule+literal (e.g. the WS8 canary `ntk_live_ws8.canary-secret`), never real credentials | `pwsh infrastructure/docker/scripts/secret-scan.ps1` |
| `compose-validate` | creates CI-only placeholder secret files (git-ignored, incl. `gemini_api_key` and `openrouter_api_key`), asserts **every** secret the compose `secrets:` block declares has a provisioned source file (closing the gap where `docker compose config` passes with a missing file), then `docker compose config --quiet`; finally runs the entrypoint LLM-gating test (`verify-llm-provider-gating.sh`): `LLM_PROVIDER=none` boots without a provider key, `LLM_PROVIDER=gemini`/`openrouter` fail closed on missing/empty key | `docker compose config` with real `secrets/` present + `bash scripts/verify-llm-provider-gating.sh` |
| `docker-build` | plants a unique marker in `secrets/`, copies the **entire repo** into a throwaway alpine image with the same root context + `.dockerignore` the frontend uses, and fails if the marker is present anywhere (context-leak gate); then builds both production images with pinned base images | `docker build` (both images) + the repo-root probe copy |
| `osv-scan` | OSV-Scanner over lockfiles; SARIF artifact uploaded. **Non-gating** per the documented WS5 accepted baseline (77 findings, `docs/audit/batch-i/report.md`) | `osv-scanner` locally |

### Context-leak gate (why it exists)

The frontend image's build context is the **repository root** (`frontend/Dockerfile` +
context `../..`), which is the only context that could transport `infrastructure/docker/secrets/`
or `.env` into a build. The gate plants a marker file there, copies the entire repository into a
throwaway `alpine` image using the same root `.dockerignore`, and greps `/ctx` for the marker.
Any match fails the build. This makes the root `.dockerignore` a **tested invariant**, not a
hope.

## 3. CD (`cd-deploy.yml`, template)

- Trigger: `workflow_dispatch` only; runs on a `[self-hosted, production]` runner; uses a
  `production` GitHub Environment with **required reviewers** as the human decision gate.
- Reads five secrets (`DB_PASSWORD`, `DB_APP_PASSWORD`, `REDIS_PASSWORD`, `OPA_POLICY_TOKEN`,
  `OWNER_PASSWORD_HASH`) and writes them to git-ignored `secrets/*` files with
  `umask 0177` + `chmod 600`; sixth and seventh files, `gemini_api_key` and `openrouter_api_key`,
  are always materialized so `docker compose up` resolves them — each value is copied from its
  optional `*_API_KEY` environment secret only when supplied, and stays empty otherwise (valid
  unless that provider is selected). If the host `.env` selects a provider whose key is
  missing, the deployment fails before any image is built; the app entrypoint also fails
  closed at boot. Prints no `set -x`, echoes nothing, and never uses a secret value
  as a `docker build` input.
- Non-secret config is the host-prepared `infrastructure/docker/.env` (copied from
  `.env.example` by the operator); the workflow refuses to run without it.
- Sequence: build → `docker compose run --rm migrate` (single migrator) →
  `docker compose up -d --scale app=N` → edge readiness probe (curl through Caddy).
- **Not executed in this repository** (no remote, no host). Activation checklist is in
  `docs/production-deployment.md` §26.

## 4. Fragile/known boundaries (documented, not hidden)

- The CI backend job exercises the H2 test profile. The **PostgreSQL** integration suite
  (`pgverify` profile, PG on `localhost:5433`, 15 ITs) is run outside CI in this environment
  because no hosted Postgres is attached; its command is recorded here so a future runner can
  add it:
  `mvn -o -pl netherite-app -am test "-Dtest=PostgresApprovalPersistenceIT,PostgresUpgradePathIT,EnterpriseRuntimeIT" "-Dsurefire.failIfNoSpecifiedTests=false"`
- OSV findings are gated by the accepted WS5 baseline; each accepted finding is tracked with a
  fixed-version policy. The scan still uploads SARIF on every run so the baseline cannot
  silently drift.
- CD health verification assumes the readiness probe is reachable through the edge at
  `https://localhost/actuator/health/readiness` from the host.

## 5. WS9 operational gates (run on the host, not in CI)

These WS9 tool suites are host-side (they require the live Docker
engine and, for the restore drill, disposable containers) and are therefore **not**
part of `ci.yml`; they form the pre-/post-deployment operational gate instead:

- `infrastructure/docker/scripts/netherite-ops-checks.ps1` — read-only health of
  stack + backup freshness/integrity (+ disk). Run after any deployment or recovery.
- `infrastructure/docker/scripts/netherite-ops-tests.ps1` — disposable regression
  suite (T01..T12 + redaction probe). Run after any change to the four WS9 scripts.
- `infrastructure/docker/scripts/netherite-backup.ps1` — produce/validate backups
  (manual cadence until an owner-approved scheduler).
- Full runbook: `docs/operations-and-recovery.md`; ledger:
  `docs/workstream-9-disaster-recovery-retention-operations.md`.