# NetheriteAgent Benchmarks

This document records the automated test verification, end-to-end gateway experiments, HTTP connector measurements, and reliability fault-injection tests performed for NetheriteAgent.

The results are intentionally separated by test layer. **Gateway, connector, and reliability workloads are different experiments; their throughput figures must not be combined.** All performance figures below were measured on a local Windows workstation using local services or loopback/stubbed downstreams. They are engineering observations, not production-capacity or SLA claims.

## Executive summary

| Test area                                    | Workload                                                              | Observed result                                                                                  | Assessment                   |
| -------------------------------------------- | --------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------ | ---------------------------- |
| Backend automated suite                      | Maven test suite, all backend modules                                 | 695 tests; 0 failures, 0 errors, 0 skipped                                                       | Pass                         |
| Targeted regression suite                    | Approval, canonical-action, auditing, and idempotency regressions     | 27 tests; 0 failures, 0 errors, 0 skipped                                                        | Pass                         |
| Frontend automated suite                     | Vitest                                                                | 7 tests passed                                                                                   | Pass                         |
| Frontend production build                    | TypeScript + Vite build                                               | Build completed successfully                                                                     | Pass                         |
| Gateway sequential / concurrency 5 / 10 / 20 | Full application path                                                 | 10/10, 5/5, 10/10, and 20/20 in the listed successful runs                                       | Pass for those runs          |
| Gateway concurrency 30, single agent         | Full application path                                                 | 25/30; 2 HTTP 502 and 3 HTTP 503                                                                 | Partial; failures observed   |
| Gateway multi-agent                          | 10 agents × 3 requests                                                | 27/30; 3 HTTP 502                                                                                | Partial; failures observed   |
| Actual connector stress, final repeat        | 500 client threads, 1,500 measured requests, 2 ms/client arrival ramp | 1,500/1,500; zero server write errors; zero duplicate execution IDs                              | Pass for this configured run |
| Idempotency reliability                      | 4,000 requests across 20 keys                                         | Exactly 20 downstream calls; zero failed keys or unexpected responses                            | Pass                         |
| OPA outage and recovery                      | 22 pre-outage, 11 outage, 25 recovery requests                        | 11/11 outage requests returned 503 with zero downstream calls; 25/25 recovery requests succeeded | Pass                         |

## 1. Test environment and interpretation

### 1.1 Environments used

- **Automated backend/frontend verification:** Java 21, Maven 3.9.9, the NetheriteAgent backend modules, and the frontend test/build toolchain.
- **Performance experiments:** a local Windows workstation running Java 21. Lower-layer and actual-connector tests used loopback mock HTTP servers. The actual compiled `HttpRestResourceConnector` was exercised with `GET_SCHEMA` actions against a local Java HTTP server.
- **November 5 reliability campaign:** a dedicated benchmark snapshot with separate PostgreSQL, Redis, OPA, and proxy containers, plus a local mock gateway connector. The benchmark snapshot was kept separate from the main repository.

### 1.2 Important measurement distinctions

- The **4,000-request idempotency test** was a gateway-level concurrent workload: 20 distinct keys, with 200 requests per key. It does not mean 4,000 requests were all in flight at the exact same instant.
- The **500-client connector test** used 500 client threads. The final fully successful repeat used a controlled 2 ms per-client start interval. That is a ramped arrival pattern, not proof of 500 simultaneous in-flight HTTP requests.
- The **connector stress test** used a local loopback mock and a `GET_SCHEMA` action. It did not exercise LLM inference, OPA policy evaluation, database transactions, human approval, real enterprise APIs, or external network conditions.
- Gateway end-to-end latency includes application and downstream waiting. Connector-only latency measures a narrower path. Do not compare these as though they were the same measurement.
- The workstation hardware model, CPU/RAM saturation, and OS socket/network counters were not preserved in the summarized performance report.

## 2. Automated test verification

### 2.1 Backend suite

Recorded command: `mvn test`

- Runtime: JDK 21; Maven 3.9.9.
- Scope: all backend modules.
- Result: **695 tests run, 0 failures, 0 errors, 0 skipped; `BUILD SUCCESS`.**

### 2.2 Targeted regression suite

Recorded command:

```text
mvn test -pl netherite-app -Dtest="ConnectorExecutionFailClosedIdempotencyTest,ConnectorExecutionApprovedExecutionTest,ApprovalExecutionRunnerTest,JpaApprovalServiceCanonicalActionTest,CanonicalActionPersistenceTest,JpaApprovalDecisionEventTest"
```

- Result: **27 tests run, 0 failures, 0 errors, 0 skipped; `BUILD SUCCESS`.**
- The targeted tests cover fail-closed execution/auditing and agent-scoped idempotency, approved-action execution and continuation, canonical-action persistence, and approval decision events.
- These 27 tests were a separate targeted run. They are not added to the 695-test count as though they were new unique tests.

### 2.3 Frontend verification

- `npm test` (Vitest): **1 test file passed; 7 tests passed**.
- `npm run build` (`tsc && vite build`): production build completed successfully.

### 2.4 Test inventory and feature coverage

A source audit identified 176 NetheriteAgent test files spanning unit, integration, and security tests. The recorded test inventory includes coverage for:

- OPA policy evaluation and fail-closed behavior (`OpaFailClosedSecurityTest`, `RealOpaContractIT`).
- Authorized-person lifecycle and concurrency (`AuthorizedPersonConcurrencyTest`).
- Distributed locking and idempotency (`DistributedLockIT`, `DistributedIdempotencyIT`).
- Connector transport and HTTP PATCH support (`AiErpResourceConnectorTransportTest`).
- SSRF validation (`SsrfGuardValidatorTest`).
- Audit logging and hash chaining (`AuditServiceTest`).
- Semantic action normalization (`SemanticActionNormalizerTest`).
- Approval flow and continuation, including `ApprovalEndToEndTest` and targeted approval-execution regression tests.

The test inventory describes tests present in the codebase; it should not be interpreted as proof that every possible external integration or production infrastructure configuration was exercised in every test run. In particular, local verification does not establish production TLS, external identity-provider federation, disaster recovery, or production monitoring/alerting.

## 3. End-to-end gateway performance experiments

These experiments exercised the application gateway path. Their end-to-end timings include more than the connector alone.

| Workload                            | Successful requests |   Wall time |       Attempted / successful throughput | Mean end-to-end latency | Notes                                                                                                        |
| ----------------------------------- | ------------------: | ----------: | --------------------------------------: | ----------------------: | ------------------------------------------------------------------------------------------------------------ |
| Sequential, 10 requests             |               10/10 | 6,504.03 ms |                              1.54 req/s |               648.98 ms | Baseline sequential run                                                                                      |
| Concurrency 5, single agent         |                 5/5 | 1,930.46 ms |                              2.59 req/s |             1,265.63 ms | Same agent                                                                                                   |
| Concurrency 10, older serial mock   |                9/10 | 3,458.30 ms |                    2.89 attempted req/s |             2,048.22 ms | One HTTP 502; report classifies this as an older mock/harness limitation                                     |
| Concurrency 10, concurrent mock     |               10/10 | 3,670.59 ms |                              2.72 req/s |             2,302.90 ms | Successful concurrent-mock run                                                                               |
| Concurrency 20, single agent        |               20/20 | 5,238.15 ms |                              3.82 req/s |             3,054.54 ms | Successful run                                                                                               |
| Concurrency 30, single agent        |               25/30 | 6,989.08 ms |  4.29 attempted / 3.58 successful req/s |             4,037.97 ms | 2 HTTP 502 and 3 HTTP 503; correlated lock/connector errors were observed                                    |
| Multi-agent, 10 agents × 3 requests |               27/30 | 2,830.52 ms | 10.60 attempted / 9.54 successful req/s |             1,890.07 ms | 3 HTTP 502 responses; fan-out increased aggregate throughput in this workload but did not eliminate failures |

**Interpretation:** the 20-request single-agent run completed successfully. Higher-load gateway runs were not uniformly successful: the single-agent concurrency-30 run completed 25/30, and the 10-agent multi-agent run completed 27/30. These failures remain part of the benchmark record and are not hidden by the separate connector-only results.

## 4. Lower-layer HTTP and connector observations

These tests isolate portions of the HTTP path. Each row is its own workload and should not be treated as a single head-to-head benchmark unless the setup and timing definition match.

| Test                                                 |  Result |                      Wall time |                        Recorded throughput | Latency observations                                                                                                      |
| ---------------------------------------------------- | ------: | -----------------------------: | -----------------------------------------: | ------------------------------------------------------------------------------------------------------------------------- |
| Direct mock, 10 requests                             |   10/10 |                      159.87 ms |                                62.55 req/s | Individual: 4.80–138.25 ms                                                                                                |
| Direct mock, 30 requests                             |   30/30 |                      151.53 ms |                               197.97 req/s | Individual: 3.78–30.74 ms                                                                                                 |
| Java 21 `HttpClient`, 30 requests                    |   30/30 |                      286.64 ms |                               104.66 req/s | Mean 248.69 ms; p95 260.57 ms                                                                                             |
| Spring `RestTemplate`, 30 sequential                 |   30/30 |                      987.29 ms |                                30.39 req/s | Mean 955.51 ms; p95 961.67 ms                                                                                             |
| Spring `RestTemplate`, 5 × 30                        | 150/150 | Batch times 867.88–1,367.24 ms |                       Combined 28.86 req/s | Five batch rates: 34.57, 21.94, 30.43, 27.08, 34.50 req/s                                                                 |
| `RestTemplate`, 30 concurrent unique-header requests |   30/30 |                      932.36 ms |                                32.18 req/s | Mean 918.77 ms; p95 919.53 ms                                                                                             |
| Actual connector, cold, 30 requests                  |   30/30 |                    1,764.82 ms |                                17.00 req/s | Mean 1,744.01 ms                                                                                                          |
| Actual connector, warmed, 3 × 30 plus 5 warm-ups     |   90/90 |            321.51 ms aggregate |                               279.93 req/s | Mean 56.93 ms; median 49.21 ms; p95 106.22 ms                                                                             |
| Actual connector, cold then warm, 3 × 30             |   90/90 |          1,532.28 ms aggregate |                                58.74 req/s | Cold first batch 1,409.44 ms; warm batches 63.40 and 59.44 ms                                                             |
| Actual connector, 50 concurrency baseline            | 149/150 |            356.97 ms aggregate | 420.21 attempted / 417.41 successful req/s | Mean 64.11 ms; median 47.10 ms; p95 130.43 ms                                                                             |
| Actual connector vs instrumented raw TCP mock        | 150/150 |            693.89 ms aggregate |                               216.17 req/s | Mean 141.52 ms; median 145.22 ms; p95 199.62 ms; mock recorded two duplicate execution IDs in this comparison run         |
| Actual connector, retry-disabled                     | 148/150 |          1,025.18 ms aggregate | 146.32 attempted / 144.36 successful req/s | Mean 188.86 ms; median 183.93 ms; p95 299.30 ms; two failures remained, with no duplicate/write errors in the mock record |

**Cold-start observation:** the first connector batch was substantially slower than warmed batches. Cold and steady-state numbers are therefore reported separately rather than averaged into one performance claim.

## 5. Actual compiled connector concurrency series

The principal connector stress series invoked the compiled `HttpRestResourceConnector` against a loopback Java HTTP server. The server configurations varied by worker count and accept backlog. Unless noted, the high-concurrency sequence used five warm-up requests followed by three measured batches of 500 requests (1,500 measured requests total).

| Step | Client threads | Mock workers / backlog | Measured result | Recorded combined rate | Arrival / run notes                                                 |
| ---: | -------------: | ---------------------: | --------------: | ---------------------: | ------------------------------------------------------------------- |
|  147 |             50 |                100 / 0 |         150/150 |           479.04 req/s | Passed                                                              |
|  148 |            100 |                100 / 0 |         300/300 |           474.86 req/s | Passed                                                              |
|  149 |            150 |                100 / 0 |         450/450 |           591.12 req/s | Passed                                                              |
|  150 |            200 |                100 / 0 |         600/600 |           532.63 req/s | Passed                                                              |
|  151 |            250 |                100 / 0 |         694/750 | 654.36 attempted req/s | 56 failures, all in batch 1                                         |
|  152 |            250 |            300 / 1,024 |         750/750 |           591.00 req/s | Passed                                                              |
|  153 |            250 |            100 / 1,024 |         750/750 |           618.93 req/s | Passed                                                              |
|  154 |            300 |            100 / 1,024 |         900/900 |           508.85 req/s | Passed                                                              |
|  155 |            350 |            100 / 1,024 |     1,050/1,050 |           547.11 req/s | Passed                                                              |
|  156 |            400 |            100 / 1,024 |     1,200/1,200 |           565.16 req/s | Passed                                                              |
|  157 |            450 |            100 / 1,024 |     1,350/1,350 |           621.33 req/s | Batch 2 summary unavailable; not reconstructed                      |
|  158 |            500 |            100 / 1,024 |     1,377/1,500 | 670.08 attempted req/s | 123 failures, all in batch 1                                        |
|  159 |            500 |            200 / 1,024 |     1,500/1,500 |           717.15 req/s | Clean simultaneous-release run                                      |
|  160 |            500 |            300 / 1,024 |     1,454/1,500 | 662.53 attempted req/s | 46 failures, all in batch 1                                         |
|  161 |            500 |            200 / 1,024 |     1,458/1,500 |           542.76 req/s | 42 failures, all in batch 1                                         |
|  162 |            500 |            200 / 1,024 |     1,410/1,500 |           439.46 req/s | 90 failures, all in batch 1; diagnostic cause-chain logging enabled |
|  163 |            500 |            200 / 1,024 |     1,500/1,500 |           493.13 req/s | 2 ms per-client ramp                                                |
|  164 |            500 |            200 / 1,024 |     1,500/1,500 |           795.03 req/s | 1 ms per-client ramp                                                |
|  165 |            500 |            200 / 1,024 |     1,500/1,500 |           836.94 req/s | Repeat 1 ms per-client ramp                                         |
|  166 |            500 |            200 / 1,024 |     1,484/1,500 | 780.41 attempted req/s | 16 failures, all in batch 1; simultaneous release                   |
|  167 |            500 |            200 / 1,024 |     1,422/1,500 |           829.21 req/s | 78 failures, all in batch 1; five waves of 100, 10 ms apart         |
|  168 |            500 |            200 / 1,024 |     1,356/1,500 |           815.39 req/s | 144 failures, all in batch 1; ten waves of 50, 10 ms apart          |
|  169 |            500 |            200 / 1,024 | **1,500/1,500** |       **495.81 req/s** | Final repeat of the 2 ms per-client ramp                            |

The rate column is the benchmark harness's combined-batch rate. For ramped tests, the wall time includes the intentional per-client release delay. Attempted rates in failed tests are not successful-throughput figures.

### 5.1 Final connector run: Step 169

| Metric                                         |                       Result |
| ---------------------------------------------- | ---------------------------: |
| Client threads                                 |                          500 |
| Measured batches                               |                      3 × 500 |
| Measured requests                              |                        1,500 |
| Per-client start interval                      |                         2 ms |
| Mock-server workers                            |                          200 |
| Accept backlog                                 |                        1,024 |
| Combined measured batch time                   |                  3,025.38 ms |
| Harness-reported combined rate                 |                 495.81 req/s |
| Mean / median / p95                            | 5.02 ms / 2.23 ms / 22.68 ms |
| Server write errors                            |                            0 |
| Duplicate execution IDs                        |                            0 |
| Server-completed requests including 5 warm-ups |                  1,505/1,505 |

This is a successful local connector run under the specified ramped arrival pattern. It is not a production-capacity claim and does not imply 500 simultaneous in-flight requests.

### 5.2 First-batch connection failures

Several 500-client experiments (Steps 158, 160, 161, 162, 166, 167, and 168) recorded failures concentrated in batch 1. For example:

- Step 162: 90 failures; the mock completed 1,415 of 1,505 expected requests, matching the 90 requests that did not reach the HTTP handler.
- Step 166: 16 failures before server acceptance.
- Step 167: 78 failures before server acceptance.
- Step 168: 144 failures before server acceptance.

The recorded exception chains included Spring `ResourceAccessException`, `java.net.ConnectException`, and in some cases `java.nio.channels.ClosedChannelException`. The evidence narrows these to connection-establishment failures before mock-handler acceptance. It does **not** prove a specific Windows, JDK, TCP-stack, client-resource, or server-accept root cause.

The final 2 ms ramp run (Step 169) reproduced a clean 1,500/1,500 result. Other 1 ms ramp runs (Steps 164 and 165) also passed 1,500/1,500. Arrival shape affected repeatability; five-wave and ten-wave cohort patterns had first-batch failures.

## 6. Reliability benchmarks: November 5, 2026

These two tests ran against a dedicated local benchmark snapshot with dedicated PostgreSQL, Redis, OPA, and proxy containers and a local mock gateway connector. They were separate from the November 4 connector stress campaign.

### 6.1 Idempotency under concurrent load

**Objective:** verify that repeated concurrent requests using the same idempotency key do not cause repeated downstream execution, while the requested workload completes without unexpected throttling or server/transport failures.

| Metric                    | Configured / expected |              Observed |
| ------------------------- | --------------------: | --------------------: |
| Distinct idempotency keys |                    20 |          20 evaluated |
| Requests per key          |                   200 | 200/200 for every key |
| Total requests            |                 4,000 |        4,000 recorded |
| Downstream executions     |   Exactly one per key |              20 total |
| Failed keys               |                     0 |                     0 |
| Unexpected responses      |                     0 |                     0 |

Observed response distribution:

| HTTP result             | Count | Interpretation in this run                                           |
| ----------------------- | ----: | -------------------------------------------------------------------- |
| `200 - EXECUTED`        |   521 | Successful gateway executions                                        |
| `409 - EXECUTION_ERROR` | 3,479 | Duplicate/idempotency-conflict responses; expected by this benchmark |

The saved summary reports all 20 keys completing their 200 requests, exactly one downstream call per key, and no failed keys or unexpected responses. The application log review for the successful full run recorded no Redis, OPA, rate-limit, or connector errors.

**Verdict: PASS.**

### 6.2 OPA fail-closed and recovery fault injection

**Objective:** stop the dedicated OPA container while gateway traffic is being sent, confirm the application rejects requests without executing downstream actions, then restart OPA and verify recovery.

| Phase       | Requests | Observed result                                                    | Assessment |
| ----------- | -------: | ------------------------------------------------------------------ | ---------- |
| Pre-outage  |       22 | 22 returned HTTP 200 / `EXECUTED`; 0 failures                      | Pass       |
| OPA stopped |       11 | 11 returned HTTP 503; 0 downstream calls; 0 leaked requests        | Pass       |
| Recovery    |       25 | 25 returned HTTP 200 / `EXECUTED`; 25 successful downstream deltas | Pass       |

Sequence:

1. Normal gateway traffic was sent before the outage; all 22 requests completed successfully.
2. The dedicated OPA container stop was confirmed, and the downstream counter baseline was captured after the stop.
3. During the outage window, all 11 requests returned HTTP 503. Each outage request recorded a downstream delta of zero.
4. OPA was restarted and confirmed running. All 25 recovery requests succeeded. The first successful recovery was recorded at 84.23 seconds elapsed from the test sequence.

**Reporting correction:** the original summary script incorrectly reported 25 downstream calls during the outage because it recalculated the counter after recovery had completed, thereby including the 25 successful recovery calls. The saved outage-phase rows and the immediate outage-phase counter check both showed zero. A corrected summary was generated from only rows where `Phase = OUTAGE`; it reports zero downstream calls and zero leakage. The test was not rerun for this reporting correction. The corrected summary is the authoritative summary artifact.

**Verdict: PASS.**

## 7. Known limitations and non-claims

- All performance numbers in this document are local workstation measurements. They are not production capacity, SLA, or multi-region scalability claims.
- The connector stress tests used a loopback mock and `GET_SCHEMA`; they do not represent ERP write operations or the full OPA/database/approval/LLM path.
- The idempotency test used a stubbed downstream. It validates the measured gateway/idempotency behavior in that isolated configuration, not an external ERP's transaction semantics.
- The OPA outage test verifies fail-closed response behavior and absence of downstream calls in the isolated test environment. It is not a broad chaos-engineering campaign covering every infrastructure failure mode.
- The 500-client connector arrival pattern matters. The final clean 2 ms ramp should not be described as 500 simultaneous in-flight requests.
- First-batch connection-establishment failures were observed in some connector workloads. Their precise root cause remains unresolved; do not attribute them to a specific component without further evidence.
- Higher-load gateway runs also recorded failures (25/30 at single-agent concurrency 30; 27/30 in the 10-agent × 3 multi-agent run). These findings are retained rather than hidden.
- The available performance report did not preserve workstation hardware details or OS-level socket/resource telemetry.

## 8. Evidence artifacts

The following artifacts were generated during the recorded test campaigns. Paths below are relative to the isolated benchmark snapshot's `diagnostics` directory where applicable.

### November 5 reliability campaign

- `idempotency-4000-post-restart-results.csv` — request-level idempotency results.
- `idempotency-4000-post-restart-summary.csv` — idempotency summary.
- `opa-failclosed-final-results.csv` — request-level pre-outage, outage, and recovery results.
- `opa-failclosed-final-summary.csv` — original OPA summary; contains the reporting-calculation issue described above.
- `opa-failclosed-corrected-summary.csv` — authoritative corrected OPA summary.
- `NetheriteAgent_Reliability_Benchmark_Report_2026-11-05.pdf` — consolidated report for the two reliability tests.

### November 4 performance campaign

- `NetheriteAgent_Performance_Benchmark_Report_2026-11-04.pdf` — consolidated gateway, HTTP client, and actual-connector benchmark report, including the unsuccessful runs and failure analysis.
- Run-specific CSV summaries and console transcripts were retained in the local benchmark diagnostics area. The report identifies the saved measurements and notes where a summary was unavailable rather than reconstructing it.

## 9. Reproduction guidance

The measurements were produced by local diagnostic harnesses and a dedicated snapshot environment. To reproduce them responsibly:

1. Use an isolated checkout or snapshot and isolated database, Redis, OPA, proxy, and mock services. Do not run fault-injection steps against shared or production infrastructure.
2. Preserve the exact Java/Maven versions, mock-server worker count, accept backlog, request arrival pattern, warm-up count, and measured batch sizes with every run.
3. Keep gateway end-to-end tests, connector-only tests, and database-backed idempotency tests as separate result groups.
4. Save request-level CSV output and console logs. Derive summaries from phase-filtered request rows, especially when measuring downstream calls during an outage.
5. Never publish API keys, secret tokens, credentials, `.env` files, or secret-bearing logs in the repository.

---

