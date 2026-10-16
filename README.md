# NetheriteAgent — Enterprise AI Agent Control Plane

NetheriteAgent is an enterprise-grade control plane for security, authorization, governance, and enforcement of autonomous AI agent actions against protected enterprise resources.

---

## Key Capabilities & Design
- **Deterministic Enforcement Plane**: Mediates all agent actions with Open Policy Agent (Rego v1).
- **Locked Conflict Resolution**: `DENY > HUMAN_APPROVAL > ALLOW`.
- **Infrastructure Safety**: Strict separation between policy denials (403) and infrastructure errors (503).
- **Tamper-Evident Audit Trail**: SHA-256 hash-chained append-only log with PII/secret data minimization.
- **Asynchronous Human Approvals**: Fast `202 Accepted` decoupling with real-time approver inbox.
- **SSRF Defense**: Outbound IP blocklist for loopback, RFC1918, and AWS metadata `169.254.169.254`.

---

## Project Structure
```
NetheriteAgent/
├── backend/                     # Java 21 / Spring Boot 3.3 Modular Monolith
│   ├── netherite-common/        # Canonical models & exceptions
│   ├── netherite-security/      # Multi-tenant context & Argon2id key hashing
│   ├── netherite-ingestion/     # Action ingestion & normalizer SPI
│   ├── netherite-context/       # Fact providers & context assembler
│   ├── netherite-policy-opa/    # OPA evaluation client & Rego models
│   ├── netherite-routing/       # Resource routing & binding matrix
│   ├── netherite-connectors/    # Pluggable connectors (REST, GraphQL, MCP)
│   ├── netherite-state/         # Distributed locks & idempotency store
│   ├── netherite-approval/      # Human approval tickets & workflow
│   ├── netherite-audit/         # Redaction & SHA-256 hash-chained audit
│   ├── netherite-llm/           # LLM semantic extraction helper SPI
│   ├── netherite-admin-api/     # Admin DTOs & API models
│   └── netherite-app/           # Bootstrap, Flyway migrations, Actuator health
├── frontend/                    # React 18 + TypeScript + Vite + Tailwind CSS
├── infrastructure/              # Docker Compose (PostgreSQL 16, Redis 7, OPA)
└── docs/                        # Architecture & local development documentation
```

---

## Quick Start

### Backend Build (Using project-local JDK 21 and Maven)
```powershell
# From a checkout of this repository (script also available as build_backend.ps1):
$projectDir = (Get-Location).Path  # or the directory containing this checkout
$env:JAVA_HOME = "$projectDir\.tools\jdk-21"
$env:PATH = "$projectDir\.tools\jdk-21\bin;$projectDir\.tools\maven\bin;$env:PATH"

cd backend
mvn clean install
```

### Frontend Build
```bash
cd frontend
npm install
npm run build
```
