# Local Development Guide

## Prerequisites
- **Java**: Java 21 (Eclipse Temurin JDK 21 located in `.tools/jdk-21`)
- **Maven**: Apache Maven 3.9.9 (located in `.tools/maven`)
- **Node.js**: 22 LTS is the CI-pinned toolchain (`.github/workflows/ci.yml`); a newer local
  Node (e.g. 25.9.0) also builds.
- **Docker** (Optional for containerized infrastructure): Docker & Docker Compose

---

## 1. Starting Infrastructure Services (Docker Compose)
When Docker is available, start PostgreSQL 16, Redis 7, and OPA:
```bash
cd infrastructure/docker
docker compose up -d
```

Service endpoints:
- **PostgreSQL 16**: `localhost:5432` (Database: `netherite`, User: `netherite_admin`; password is a provisioned file secret — see `infrastructure/docker/secrets.example/` and `scripts/generate-secrets.ps1`, never committed)
- **Redis 7**: `localhost:6379`
- **OPA**: `http://localhost:8181`

### 1.1 Backup, restore & operational checks (WS9)

Local backup (requires the compose stack up):
```powershell
& .\infrastructure\docker\scripts\netherite-backup.ps1
# -> infrastructure\docker\backups\netherite-pg-<ts>.dump + .json manifest
```

Validate / restore into a disposable container (never touches your local DB):
```powershell
& .\infrastructure\docker\scripts\netherite-restore.ps1 -BackupFile .\backups\netherite-pg-*.dump
& .\infrastructure\docker\scripts\netherite-restore.ps1 -BackupFile .\backups\netherite-pg-*.dump -PerformRestore
```

Read-only health of stack + backups:
```powershell
& .\infrastructure\docker\scripts\netherite-ops-checks.ps1
```

Regression for the tooling (disposable resources only):
```powershell
& .\infrastructure\docker\scripts\netherite-ops-tests.ps1
```

Full details and recovery procedures: `docs/operations-and-recovery.md`.

---

## 2. Building and Running the Backend
Build using project-local JDK 21 and Maven:
```powershell
# From a checkout of this repository:
$projectDir = (Get-Location).Path  # or the directory containing this checkout
$env:JAVA_HOME = "$projectDir\.tools\jdk-21"
$env:PATH = "$projectDir\.tools\jdk-21\bin;$projectDir\.tools\maven\bin;$env:PATH"

cd backend
mvn clean install
```

Run the Spring Boot application:
```powershell
mvn spring-boot:run -pl netherite-app
```
Backend API will start on: `http://localhost:8080`
- Swagger UI: `http://localhost:8080/swagger-ui/index.html`
- Health Endpoint: `http://localhost:8080/actuator/health`

### Logging profiles (WS8)
- Default (shell) run: plain logback text logs.
- Structured JSON logs (the production/compose shape): run with
  `SPRING_PROFILES_ACTIVE=structured` (or set `SPRING_PROFILES_ACTIVE=structured` on
  `mvn spring-boot:run`). Each line is one JSON object (`ts, level, logger, thread, service,
  environment, requestId, message`, plus event keys).
- Every HTTP response carries `X-Correlation-Id` (validated inbound value or a fresh UUID)
  so you can join a request to its `requestId` in the logs.

---

## 3. Building and Running the Frontend
```bash
cd frontend
npm install
npm run dev
```
Frontend development server will start on: `http://localhost:3000`. Its proxy routes `/v1` and `/actuator` to the deployed Caddy edge at `https://localhost` (the self-signed local certificate is accepted with `secure: false`); the `Secure` attribute on backend cookies is stripped dev-only so the http dev browser can hold the session, matching the app's scheme-aware cookie behavior on the production https path.

---

## 4. Designated Authorized Person bootstrap (WS6)

There are no roles. Each organization has at most one designated authorized person (see
`docs/architecture.md`). After a fresh database, the owner's organization is designated
automatically at first owner login.

To designate the first person of another organization (deployment owner only):

```powershell
$login = Invoke-RestMethod -Uri http://localhost:8080/v1/auth/login -Method Post -ContentType 'application/json' -Body '{"email":"<owner>","password":"<pw>"}' -SessionVariable s
Invoke-RestMethod -Uri "http://localhost:8080/v1/control/organizations/<orgId>/authorized-person" -Method Post -WebSession $s -ContentType 'application/json' -Body '{"userId":"<memberUserId>"}'
```

The frontend exposes the designation and transfer on **Authorized Person** (sidebar). A member
who is not designated sees a fail-closed notice; the designated person can transfer with the
member picker.

---

## 5. CI/CD (WS7)

The pushing hooks that would normally run here run in GitHub Actions instead (no local Git
remote), see `docs/ci-cd.md`. The same commands are available locally:

- Backend regression: `mvn -o -pl netherite-app -am test` (offline, from `backend/`);
  PostgreSQL integration suite additionally requires the `pgverify` PG on `localhost:5433`
  (`mvn -o -pl netherite-app -am test "-Dtest=PostgresApprovalPersistenceIT,PostgresUpgradePathIT,EnterpriseRuntimeIT" "-Dsurefire.failIfNoSpecifiedTests=false"`).
- Frontend: `npm ci && npm run build`.
- Secret scan: `pwsh infrastructure/docker/scripts/secret-scan.ps1`.
- Compose config: `docker compose config` (needs `infrastructure/docker/secrets/` provisioned).
