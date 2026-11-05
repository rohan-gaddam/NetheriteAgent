# NetheriteAgent — Enterprise AI Agent Control Plane

NetheriteAgent is an enterprise AI-agent control plane designed to secure, authorize, govern, and enforce autonomous AI-agent actions against protected enterprise resources.

It acts as a centralized governance layer between AI agents and enterprise APIs. Protected actions are routed through NetheriteAgent for authentication, context preparation, deterministic policy evaluation, and human approval when required.

<p align="center">
  <a href="https://github.com/rohan-gaddam/NetheriteAgent">
    <strong>GitHub Repository</strong>
  </a>
  |
  <a href="https://github.com/rohan-gaddam/NetheriteAgent/blob/main/BENCHMARKS.md">
    <strong>Benchmark Results</strong>
  </a>
</p>

---

## Problem Statement

AI agents can interact with enterprise systems through APIs and perform actions such as retrieving data, modifying records, and triggering workflows.

Without a centralized governance layer, organizations may face:

- Unauthorized or unintended agent actions
- Inconsistent authorization across enterprise integrations
- Insufficient context when evaluating sensitive requests
- Limited visibility into agent activity
- Difficulty enforcing human approval requirements
- Risk of policy enforcement being bypassed by individual integrations

NetheriteAgent addresses these challenges by providing a centralized control point that evaluates protected actions before they reach enterprise resources.

## Project Goals

- Provide a centralized authorization gateway for AI-agent actions.
- Enforce deterministic, policy-based access control.
- Gather trusted context before evaluating an action.
- Support explicit allow, deny, and human-approval outcomes.
- Protect enterprise resources from unauthorized agent operations.
- Maintain an auditable record of authorization decisions.
- Separate policy denials from infrastructure failures.
- Support extensible authentication and enterprise integrations.

## Key Features

### Deterministic Policy Enforcement

NetheriteAgent uses Open Policy Agent (OPA) and Rego policies to evaluate protected actions.

Authorization decisions are made by the policy engine, not by an LLM.

### Canonical Action Processing

Incoming agent requests are normalized into a canonical action representation, allowing the policy engine to evaluate actions consistently across integrations.

### Trusted Context Preparation

Before policy evaluation, NetheriteAgent prepares the request context using available trusted information, such as:

- Agent identity and authentication information
- Organization and tenant context
- Requested action and target resource
- Relevant resource state
- Applicable policy information
- Available security signals and action history

### Human Approval Workflow

Actions requiring human review can be routed through an approval workflow.

The system supports the following authorization outcomes:

| Outcome | Meaning |
|---|---|
| `ALLOW` | The action is permitted by policy. |
| `DENY` | The action is rejected. |
| `HUMAN_APPROVAL` | The action requires human review. |

The defined conflict-resolution order is:

`DENY > HUMAN_APPROVAL > ALLOW`

### Fail-Closed Behavior

NetheriteAgent distinguishes policy denials from infrastructure failures.

- `403` — policy-based denial.
- `503` — infrastructure or policy-engine availability failure.

If the policy engine is unavailable, the system fails closed rather than allowing an unverified action to proceed.

### Audit Trail

NetheriteAgent uses an append-only audit trail with SHA-256 hash chaining to support tamper-evidence.

The audit design also minimizes the inclusion of sensitive information such as personally identifiable information and secrets.

---

## System Architecture

```text
                  ┌──────────────────────┐
                  │       AI Agent       │
                  └──────────┬───────────┘
                             │
                             │ Protected action request
                             ▼
                  ┌──────────────────────┐
                  │   NetheriteAgent     │
                  │    HTTPS Gateway     │
                  └──────────┬───────────┘
                             │
                             ▼
                  ┌──────────────────────┐
                  │ Authentication and   │
                  │ Identity Validation  │
                  └──────────┬───────────┘
                             │
                             ▼
                  ┌──────────────────────┐
                  │ Canonical Action     │
                  │ Normalization        │
                  └──────────┬───────────┘
                             │
                             ▼
                  ┌──────────────────────┐
                  │ Trusted Context      │
                  │ Preparation          │
                  └──────────┬───────────┘
                             │
                             ▼
                  ┌──────────────────────┐
                  │ OPA Policy Engine    │
                  │ Rego v1              │
                  └──────────┬───────────┘
                             │
                 ┌───────────┼────────────┐
                 ▼           ▼            ▼
              ALLOW         DENY    HUMAN_APPROVAL
                 │           │            │
                 ▼           ▼            ▼
          Protected API   Reject       Approval
          / Resource      Request      Workflow
                 │
                 ▼
             Audit Trail
```

## Authorization Flow

1. An AI agent sends a protected action request to the NetheriteAgent HTTPS endpoint.
2. NetheriteAgent validates the request and its authentication context.
3. The incoming request is normalized into a canonical action.
4. NetheriteAgent gathers the trusted context required for policy evaluation.
5. OPA evaluates the action using the configured Rego policies.
6. The system returns an authorization outcome.
7. An allowed action can proceed to the protected enterprise resource.
8. A denied action is rejected.
9. An action requiring human approval is routed through the approval workflow.
10. Authorization activity is recorded in the audit trail.

## Role of the LLM

The LLM is used only for constrained parsing tasks, such as helping interpret natural-language input into a structured representation.

The LLM does not make authorization decisions.

The final authorization decision is made by the deterministic OPA policy engine using the prepared request context and configured policies.

---

## Technology Stack

| Component | Technology |
|---|---|
| Backend | Java, Spring Boot |
| Policy Engine | Open Policy Agent (OPA) |
| Policy Language | Rego v1 |
| Database | PostgreSQL |
| Frontend | React, TypeScript |
| Build Tool | Maven |
| Audit Integrity | SHA-256 hash chaining |
| Authorization | Deterministic policy-based evaluation |

## Security Design

NetheriteAgent is designed around the following security principles:

- **Deterministic authorization:** Decisions are made by the policy engine, not by an LLM.
- **Fail-closed behavior:** Policy-engine outages do not result in automatic authorization.
- **Separation of concerns:** Authentication, context preparation, policy evaluation, approval, and execution have separate responsibilities.
- **Explicit outcomes:** Allow, deny, and human approval are represented as distinct results.
- **Auditability:** Authorization activity is recorded in an append-only, hash-chained audit trail.
- **Sensitive-data minimization:** Audit records are designed to minimize exposure of secrets and personally identifiable information.

---

## Benchmark Results

The repository includes benchmark documentation describing selected local test runs and their test conditions.

| Test | Reported Result |
|---|---|
| Idempotency test | 4,000 requests using 20 keys |
| Downstream executions | Exactly 20 |
| Duplicate executions | 0 |
| Failed keys | 0 |
| Unexpected responses | 0 |
| OPA outage behavior | 11/11 requests returned HTTP 503 |
| Downstream calls during OPA outage | 0 |
| Recovery after OPA restart | 25/25 requests succeeded |
| Connector stress test | 1,500 measured requests; 100% successful in the documented configuration |

The tests used local infrastructure and a stubbed or loopback downstream service. These results describe the documented test configurations and are not production-scale performance guarantees.

For full methodology, conditions, and results, see:

**[BENCHMARKS.md — Benchmark Methodology and Results](https://github.com/rohan-gaddam/NetheriteAgent/blob/main/BENCHMARKS.md)**

---

## Project Status

NetheriteAgent is under active development.

Development includes:

- Core control-plane and policy-enforcement functionality
- Agent authentication and key-security improvements
- Tenant-scoped administration and control-plane APIs
- Frontend user interface
- Organization onboarding and integration workflows

The availability of individual capabilities depends on the current development phase.

## Repository Structure

```text
NetheriteAgent/
├── backend/           # Backend services and API implementation
├── frontend/          # Frontend application
├── infrastructure/    # Infrastructure and deployment resources
├── e2e/               # End-to-end tests
├── docs/              # Project documentation
├── .github/           # GitHub configuration and workflows
├── BENCHMARKS.md      # Benchmark methodology and results
├── README.md          # Project overview
└── .gitignore
```

## Intended Use

NetheriteAgent is intended to serve as a centralized governance and authorization layer for AI agents interacting with protected enterprise resources.

It helps organizations enforce consistent authorization policies, control sensitive actions, introduce human review where required, and maintain visibility into agent activity.

## Disclaimer

NetheriteAgent is a software project under active development.

Its documented architecture and benchmark results do not constitute a claim of production readiness, independent security certification, or guaranteed performance under all workloads.

Organizations should conduct their own security reviews, integration testing, and operational validation before using the system to protect production resources.

## Author

**Rohan Gaddam**

- GitHub: [rohan-gaddam](https://github.com/rohan-gaddam)
- Project Repository: [NetheriteAgent](https://github.com/rohan-gaddam/NetheriteAgent)

---

If you find this project interesting, feel free to explore the repository and its documentation.
