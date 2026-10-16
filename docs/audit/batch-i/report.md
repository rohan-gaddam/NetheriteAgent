# WS5 Batch I — Dependency & Secret Audit Report

- **Batch:** WS5 — Batch I (Dependency & Secret Audit)
- **Status:** COMPLETE AND VERIFIED
- **Report date:** 2026-09-28
- **Evidence root:** `docs/audit/batch-i/`
- **Scope:** Maven JVM runtime graph (`backend`, 12-module reactor, shipped surface = `netherite-app`), frontend (npm), Docker supply chain, and repository-wide secret hygiene.

---

## 1. Scope and objective

Batch I audits the dependency and secret posture of NetheriteAgent:

1. Produce a reproducible inventory of the shipped JVM runtime dependency graph and the
   frontend dependency graph.
2. Run ecosystem vulnerability scans per exact resolved version (OSV for Maven, `npm audit`
   for frontend/e2e).
3. Remediate only safe, same-line/same-major patch or line-level findings within Batch I's
   scope, with full regression gates.
4. Re-scan to demonstrate a measured reduction in known vulnerabilities and classify all
   residual findings by runtime reachability.
5. Re-run the repository-wide secret audit (Batch G tooling) and re-verify the static
   secret-handling contract.
6. Assess supply-chain posture (image tags, toolchain pinning, dependency-update automation).
7. Leave Batch J (docs + ledger finalization) and WS5 open.

## 2. Audit methodology and tools

| Step | Tool / command | Scope | Output |
|---|---|---|---|
| JVM inventory | `mvn -pl netherite-app dependency:list` (reactor build) | shipped runtime deps | `maven-dependency-list-app.txt` |
| JVM tree | `mvn -pl netherite-app dependency:tree` | transitive structure | `maven-dependency-tree-app.txt` |
| OSV scan | `scan-osv.ps1` (google/osv-scanner via OSV batch API) | 157 unique artifacts | `osv-raw-0-*.json` + `osv-findings.csv` |
| OSV enrich | `enrich-osv.ps1` (per-advisory `GET /vulns/{id}`) | severity/summary/fixed | `osv-enriched-*.csv` |
| Frontend | `npm audit --json` + `npm ls` | frontend/e2e | `npm-audit-*.json`, `npm-ls-*.txt`, `npm-build-frontend.log` |
| Secrets | `infrastructure/docker/scripts/secret-scan.ps1` (`-SelfTest`, full scan) | authored repo surface | `secret-scan-report.txt` |
| Regression | `mvn test` + `npm run build` | JVM + frontend | `maven-test-run.log`, `npm-build-frontend.log` |

Scanner provenance note: the OSV scanner script regex isolates Maven coordinate lines
`[INFO] groupId:artifactId:jar:version:scope` from the `spring-boot-maven-plugin` run and queries
the OSV `querybatch` endpoint per artifact. Enrichment adds CVE ID, severity, summary, and
fixed-version when the advisory records it. All inputs/outputs are ASCII to avoid PowerShell
UTF-16 artifacts in evidence files.

## 3. Dependency inventory and coverage

- **JVM:** 157 unique artifacts in `netherite-app`'s resolved graph (compile + runtime scopes).
  Baseline (2026-09-28): Spring Boot 3.3.3 / Spring Framework 6.1.12, Tomcat 10.1.28, Jackson
  2.17.2, netty 4.1.115, logback 1.5.15, json-smart 2.5.0, BouncyCastle 1.78.1, PostgreSQL JDBC
  42.7.7, micrometer 1.13.x, spring-data 3.3.x, Redis (Redisson 3.34.1).
- **Frontend:** `npm ls` (root manifest + lockfile) and `npm audit`; production + dev dependency
  sets. e2e workspace audited separately and left untouched.
- **Coverage disclosure:** JVM scan covers the shipped `netherite-app` graph (the runnable jar
  surface). Infrequently-executed development branches (`dependency:tree` for reactor modules)
  are not separately scanned. The OSV feed covers Maven Central-published advisories the OSV
  database indexes; it is not a substitute for CISA/CNCERT/private-feed review. No daemon or
  runtime-app behavior was exercised during the static scan (reachability is assessed by code
  inspection, below).

## 4. Vulnerability findings

### Baseline (107 findings on 157 artifacts)

Headline classes at baseline:
- **CRITICAL:** BouncyCastle BC 1.78.1 (2 CRITICAL: CVE-2025-14813, CVE-2026-8763), plus the
  Tomcat/Spring 2024–2026 set on the Boot 3.3.3 line (incl. CVE-2025-24813).
- **HIGH:** Spring Framework 6.1.x 2024/2025 set (CVE-2024-38816/38819/38820, CVE-2025-22233,
  CVE-2025-41234), Spring Security crypto (CVE-2025-22228), logback (CVE-2024-12798/12801,
  CVE-2025-11226), Tomcat 2024 line (CVE-2024-50379/52317/56337), json-smart (CVE-2024-57699),
  netty 2024/2025 set, jackson-databind 2026 set (2.17.2 line), micrometer (CVE-2026-40984),
  PostgreSQL JDBC 42.7.7 (CVE-2026-42198, CVE-2026-54291 — both HIGH), assertj XXE (test scope).
- **Frontend:** 2 findings — vite HIGH (`server.fs.deny` bypass + `.map` traversal /
  `launch-editor` NTLM) and esbuild MODERATE (dev-server fetch DoS); both dev-time only.

### Final state (77 findings on the same 157 artifacts)

Reduction **107 → 77** (−28 via Boot/BouncyCastle bump, −2 via PostgreSQL 42.7.13). Intermediates
archived: `osv-findings-r2-boot-bump.csv` / `osv-enriched-r2-boot-bump.csv` (79 findings after the
Boot/BouncyCastle bump, before the PostgreSQL override).

Residual 77 classified by reachability (full classified listing in `osv-enriched.csv`):
- **NOT APPLICABLE (technology absent / not wired):** Tomcat auth realms / DIGEST / FORM /
  LockOutRealm (the app uses custom filters and `spring-security-crypto` only — no
  `spring-boot-starter-security`), WebDAV/WebSocket/SSE/JSP/script-template, HTTP/2 (plain HTTP
  behind the Caddy edge), netty server-side SNI/TLS-trust/DNS-resolver/decoder CVEs (netty is
  Redisson's deep client dep; `DnsNameResolver` never used; servlet server), jackson
  default-typing/subtyped-array/`InetSocketAddress` SSRF classes (no
  `enableDefaultTyping`/`activateDefaultTyping`, no such intake types), actuator CloudFoundry
  endpoint (not exposed), Artemis temp-dir (no Artemis), spring static-resource handler CVEs (no
  `addResourceHandlers`/static resource serving), log4j-api MapMessage JSON (logback is the
  active logger), commons-lang3 recursion DoS (LOW utility posture), assertj XXE (HIGH,
  **test-scope, not shipped**), xmlunit (LOW, test-scope).
- **NOT WIRED (present but never invoked):** httpclient5 5.3.1 / httpcore5 5.2.5 / httpcore5-h2
  CVEs — every outbound REST client is `new RestTemplate(new
  NoRedirectSimpleClientHttpRequestFactory())` (JDK `HttpURLConnection`); OIDC uses the JDK
  `HttpClient`; Boot's RestTemplate/HttpComponents auto-config is never selected. Not overridden
  (no runtime benefit, adds BOM divergence); documented with a recommendation to prune the unused
  stack or move to Boot 3.5.
- **GENUINE LIMITED / only clearable on the 3.5.x line (Boot 3.3.x is EOL):** Spring FW 6.1.21
  residual 2026 CVEs — the reachable subset is small (MVC path traversal MODERATE, AntPathMatcher
  LOW; the heavy static-resource/JSP/SSE/SpEL classes are N/A); micrometer 1.13.15 metrics DoS
  (actuator/prometheus exposed but auth-gated); Boot 3.3.13 predictable-temp-dir (containers run
  a fresh `tmpfs /tmp` + non-root); 2026 Tomcat CRITICALs that only patch on 10.1.45+/Boot 3.5
  (plain-HTTP JSON API, no WebDAV/WebSocket/realms surface).
- **Mitigation recommendation:** schedule Boot 3.5.x (Spring 6.2.x) as the follow-up; it resolves
  the whole 2026 wave (Tomcat, micrometer 1.15.x, Spring FW 6.2.x, httpclient 5.6.x). The scan
  pipeline is committed and rerunnable after that upgrade.

## 5. PostgreSQL JDBC remediation and verification

- **Intent:** the runtime-active PostgreSQL driver (Hikari → PostgreSQL JDBC) carried two HIGH
  advisories at 42.7.7: CVE-2026-42198 (SCRAM PBKDF2 DoS) and CVE-2026-54291 (channel-binding
  downgrade). 42.7.13 is the current stable same-line patch.
- **Why an explicit entry:** a `<postgresql.version>` Maven property override was first attempted
  and proven **inert** — Maven interpolates an imported BOM against the BOM's own properties. The
  working mechanism is an explicit `dependencyManagement` entry placed **after** the Spring Boot
  BOM import (`backend/pom.xml:57–64`), so it wins.
- **Verification (current):** effective resolved version confirmed two ways from committed
  evidence on the post-fix inventory:
  - `docs/audit/batch-i/maven-dependency-list-app.txt` →
    `org.postgresql:postgresql:jar:42.7.13:runtime`
  - `docs/audit/batch-i/maven-dependency-tree-app.txt` →
    `org.postgresql:postgresql:jar:42.7.13:runtime`
- **No collateral:** the override is scoped to `org.postgresql:postgresql` only; no other
  BOM-managed dependency is overridden. The finalized OSV scan (`osv-findings.csv`, 77 rows)
  contains **zero** `org.postgresql` rows, proving the advisory pair is cleared on the resolved
  version.
- Both cleared advisories are HIGH severity and were on the runtime-active driver; this was the
  single most runtime-relevant patch in Batch I after the Boot bump.

## 6. Secret scanning scope and results

- **Scanner:** `infrastructure/docker/scripts/secret-scan.ps1` (authored in Batch G, hardened in
  Batch H). Rules: PRIVATE-KEY, AWS-ACCESS-KEY, GITHUB-TOKEN, SLACK-TOKEN, OPENAI-KEY, AGENT-KEY,
  JWT, BCRYPT-HASH, ASSIGNED-SECRET — all with value-sanitization heuristics that suppress
  `${ENV}` / `<placeholder>` / path / method-chain false positives. **Values are never printed;
  findings are reported as `path:line:rule:col:mask` (first 3 + last 2 chars).**
- **Scope (392 files):** authored surface only — `backend`, `docs` (incl. this report), `e2e`,
  `frontend`, `infrastructure`, `build_backend.ps1`, `README.md`. Narrow, documented exclusions:
  `node_modules`, build outputs (`target/build/dist`), pinned local toolchains (`.tools`), runtime
  secret artifacts (`secrets/`, `policies-data/`), npm lockfiles, binary/media/map/sum/sig/asc
  files.
- **Result (re-verified 2026-09-28):** **392 files scanned, 0 findings, PASS.** The report file
  is empty (0 bytes) by design when clean; a non-empty report lists masked findings, one per line.

### Batch H canary handling

The Batch H security suite includes a synthetic canary agent-key literal in
`ErrorHygieneSecurityTest.java:94` (a deliberate, non-secret fixture used to assert hygienic
rejection of unknown agent keys, HYG-1). It is allowlisted **narrowly** — path + rule + exact
literal must all match — with justification in the scanner's approved list. The scanner's own
self-test re-uses the same corpus. The repository must NOT reproduce canary literals verbatim in
documentation, or the doc itself is flagged (as observed during Batch I; corrected).
`SecretHygieneRegressionTest` (5 tests) and the Batch H suite (35 tests) both pass in the final
regression.

## 7. Scanner integrity and limitations

- **Self-test:** `secret-scan.ps1 -SelfTest` → creates controlled synthetic secrets in a temp
  tree, asserts all genuine categories are detected (7+ findings expected) while placeholder
  forms and the approved repo-path fixture are NOT flagged, then cleans up. **Result: PASS, exit 0.**
- **Approved list discipline:** every allowlisted finding is path + rule + (where applicable)
  exact-literal scoped with an explicit reason. No broad ignores, no blanket directory rules.
- **Limitations disclosed:**
  - Static regex scanning cannot detect secrets generated/rotated at runtime, secrets held only
    outside the repo (Vault/KMS), or high-entropy values under a rule that has no matching
    pattern.
  - The `ASSIGNED-SECRET` rule only fires on `key: value` / `key = value` lines with literal
    look-alike values; it is heuristic by design.
  - `secrets/` (runtime file secrets, git-ignored on purpose) and `policies-data/` are excluded;
    they are validated live by the Batch G P1–P13 panel instead.
  - Absence of detected secrets is **not** proof that no real secret ever existed; it is evidence
    of a clean static scan of the audited surface.
  - The live P1–P13 container panel was intentionally **not re-run** in Batch I (requires an
    operator-supplied `OWNER_PASSWORD` and full stack rebuild; Docker engine 29.8.0 is available
    if an operator runs it). The static counterpart (entrypoint `require_secret` set == compose
    mounts == 5-file inventory) was re-verified and is intact.

## 8. Security test and regression evidence

- **Standalone Batch H suite (committed command):**
  `& $mvn '-o' 'test' '-pl' 'netherite-app' '-Dtest=io.netherite.securitysuite.*Test' '-Djacoco.skip=true'`
  — six classes / 35 tests (OpaFailClosedSecurityTest 13, FilterBoundarySecurityTest 4,
  AuthorizationMatrixSecurityTest 6, ApprovalSecurityBoundaryTest 5, DenialSideEffectSecurityTest
  2, ErrorHygieneSecurityTest 5). Suites still present on disk; their results are validated as
  part of the full final regression below.
- **Full JVM regression (final, run after the PostgreSQL 42.7.13 override was applied):**
  recorded in `docs/audit/batch-i/maven-test-run.log`:
  **Tests run: 492, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS**
  (message `Finished at: 2026-09-28T21:15:20+05:30`), and it runs the security suite alongside
  the payload suites (`io.netherite.securitysuite.*` at 13/13 for OpaFailClosedSecurityTest in the
  captured tail). This is the regression for the exact Boot 3.3.13 + bcprov 1.86 + pg 42.7.13
  configuration that shipped in Batch I.
- **Frontend:** `npm audit` **0 vulnerabilities** (npm-audit-frontend-after.json, totals all
  zero) after the vite 6.4.3 bump; `npm run build` (`tsc && vite build`) clean with vite 6.4.3.
  e2e `npm audit` exit 0, e2e workspace untouched.
- **Provenance discipline:** the 492/492 result is verified for the current POM + resolved
  dependency set (post-42.7.13 dependency list/tree evidence has a later timestamp than the POM
  change, and the test log is the run on that exact configuration).

## 9. Remediation summary

| Item | From | To | Cleared | Gate |
|---|---|---|---|---|
| Spring Boot BOM | 3.3.3 | **3.3.13** (final 3.3.x / EOL) | 2024–2025 Spring FW/logback/Tomcat/security set incl. CVE-2025-24813, CVE-2025-22228, CVE-2024-38816/19/20, CVE-2024-50379/56337, json-smart CVE-2024-57699, netty set | OSV 107→79 (with below) |
| BouncyCastle | 1.78.1 | **1.86** | All 4 BC advisories incl. 2 CRITICAL (CVE-2025-14813, CVE-2026-8763) | same |
| `flyway.version` property | 10.17.1 (inert) | **removed** | none (Boot BOM already manages 10.10.0; graph unchanged) | dependency tree |
| PostgreSQL JDBC | 42.7.7 | **42.7.13** (explicit dep-mgmt after BOM) | CVE-2026-42198 + CVE-2026-54291 (both HIGH) | OSV 79→77; dep-list/tree show 42.7.13 |
| Frontend vite | ^5.4.3 | **^6.4.2** (resolved 6.4.3, esbuild 0.25.12) | 2 frontend findings (vite fs.deny + esbuild fetch DoS) | `npm audit` 0; build clean |
| Secret scan | — | full-scan PASS | help find canary literal in docs during Batch I (fixed: docs must not embed canary literals) | 392 files / 0 findings, self-test PASS |

Full regression after all of the above: **492/492** JVM + frontend build + `npm audit` 0.

## 10. Outstanding findings and accepted limitations

1. **77 residual OSV advisory references remain** on the resolved graph. 69 are classified NOT
   APPLICABLE or NOT WIRED by reachability analysis; the remaining limited-reachability items are
   **accepted** for the Boot 3.3.x (EOL) line and are the documented case for scheduling Boot 3.5.x.
   A subset of these advisories may be retired by the OSV DB over time as ranges are corrected, but
   no such retirement is claimed here.
2. **Spring FW 6.1.21 + Boot 3.3.13 residual CVEs (2026 wave)** — fix exists only on the 3.5.x /
   Spring 6.2.x line. Not fixable in-line without a major Boot upgrade.
3. **Unused Apache HTTP stack (httpclient5/httpcore5/httpcore5-h2 at BOM-pinned versions)** is
   retained transitively; its advisories are not runtime-reachable. Recommended action (not taken,
   Batch I scope): prune the unused stack or let Boot 3.5 resolve newer versions.
4. **Floating Docker base-image tags** (runtime temurin minor, node minor, caddy/postgres/redis
   patch): residual drift risk. Documented; digest pinning left for Batch J recommendation.
5. **Live P1–P13 secret panel not re-run in Batch I** — operator-gated (OWNER_PASSWORD + stack
   rebuild). Static contract re-verified instead; Batch G's earlier live panel (13/13) remains the
   last executed live evidence.
6. **No CI / automated dependency update flow exists.** Scheduling renovate/dependabot or a cron
   scan is a Batch J recommendation, not a Batch I execution.
7. **OSV DB coverage limitation** (Maven ecosystem coverage = what OSV indexes; no private-vendor
   feed). Stated for transparency.

## 11. Evidence references

All under `docs/audit/batch-i/` unless noted:

- `scan-osv.ps1`, `enrich-osv.ps1` — reproducible scanners (committed).
- `maven-dependency-list-app.txt`, `maven-dependency-tree-app.txt` — final inventory/tree
  (PostgreSQL JDBC **42.7.13** resolved in both).
- `osv-raw-0-baseline.json`, `osv-findings-baseline.csv`, `osv-enriched-baseline.csv` — baseline
  **107 findings**.
- `osv-findings-r2-boot-bump.csv`, `osv-enriched-r2-boot-bump.csv` — intermediate **79 findings**
  (Boot + BouncyCastle bump).
- `osv-raw-0.json`, `osv-findings.csv`, `osv-enriched.csv` — final **77 findings**, zero
  PostgreSQL rows.
- `npm-audit-frontend.json` / `npm-audit-frontend-after.json` — 2 before → **0 after**.
- `npm-audit-e2e.json`, `npm-ls-e2e.txt` — e2e untouched, exit 0.
- `npm-build-frontend.log` — vite 6.4.3 production build clean.
- `maven-test-run.log` — **492/492, 0 failures, BUILD SUCCESS**.
- `secret-scan-report.txt` — empty (0 bytes) when clean; non-empty = masked findings.
- `../workstream-5-enterprise-security-hardening.md` — WS5 ledger (Batch I entry, section below).
- `backend/pom.xml` — Boot 3.3.13, bcprov 1.86, removed flyway property, postgresql 42.7.13
  explicit entry after BOM import (`backend/pom.xml:57–64`).
- `infrastructure/docker/scripts/secret-scan.ps1` — scanner + narrow allowlist.
- `infrastructure/docker/scripts/verify-secret-handling.ps1` — operator-gated live panel (not
  re-run in Batch I).

## 12. Final Batch I outcome

Batch I — Dependency & Secret Audit — is **COMPLETE AND VERIFIED**:

- Dependency inventory (157 JVM artifacts) reproduced and committed; OSV baseline 107 findings
  reduced to **77** via Boot 3.3.13, BouncyCastle 1.86, and PostgreSQL JDBC 42.7.13 (runtime-active
  driver; both HIGH Postgres advisories cleared and proven absent from the final scan).
- Frontend `npm audit` 0, production build clean on vite 6.4.3.
- Repository-wide secret scan **392 files / 0 findings / PASS** with self-test PASS; canary
  literals are narrowly allowlisted and documentation now avoids embedding them.
- Full JVM regression **492/492** on the exact shipped configuration.
- Residual findings are classified by reachability (N/A / not-wired / limited) and the
  limited-reachability remainder is explicitly accepted pending the recommended Boot 3.5.x
  follow-up, with the committed pipeline available to re-scan then.
- Batch J remains PENDING; WS5 remains open. No WS5 final verdict is issued here.

> **Timeline note (2026-09-29):** Batch J subsequently completed the docs/ledger finalization and
> issued the WS5 final verdict — **COMPLETE WITH DOCUMENTED LIMITATIONS** — in
> `docs/workstream-5-enterprise-security-hardening.md` (Batch J section). The line above was
> written when Batch I completed and is preserved as the state at that time.