package io.netherite.policy.push;

import io.netherite.common.exception.NetheriteException;
import io.netherite.common.exception.SystemErrorCode;
import io.netherite.common.model.CanonicalAction;
import io.netherite.context.model.DecisionContext;
import io.netherite.domain.entity.PolicyEntity;
import io.netherite.domain.entity.PolicyVersionEntity;
import io.netherite.domain.repository.PolicyRepository;
import io.netherite.domain.repository.PolicyVersionRepository;
import io.netherite.policy.model.OpaPolicyManageResult;
import io.netherite.policy.model.PolicyEvaluationResult;
import io.netherite.policy.spi.OpaPolicyEngineClient;
import io.netherite.policy.spi.OpaPolicyManageClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Security-relevant synchronization of Rego policy content into the policy
 * engine.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Activation-time push of tenant policy versions, with strict package
 *       pinning to {@code netherite.authz.tenants.<org>}. The engine's
 *       {@code PUT /v1/policies/<id>} is the authoritative validator (it compiles
 *       and installs atomically); the pre-flight compile is a fast-fail for
 *       reachable-rule type errors. A rejected module never becomes active.</li>
 *   <li>Startup reconciliation of the canonical base module when the engine has
 *       none (mounted modules satisfy the presence check).</li>
 *   <li>Periodic reconciliation (default 60 s) that re-installs the base and any
 *       ACTIVE tenant module that vanished from the engine — OPA is in-memory, so
 *       a daemon restart would otherwise silently de-enforce the audited policy
 *       state.</li>
 * </ul>
 *
 * <p>Fail-closed: any engine 401 (bad/missing credential), transport failure or
 * compile reject aborts the operation with {@link NetheriteException}; policy
 * state is never half-applied and activation is never granted on an uncertain
 * push.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "netherite.opa.policy-path")
public class PolicyPushService {

    private static final Pattern PACKAGE_LINE = Pattern.compile(
            "(?m)^\\s*package\\s+[A-Za-z_][A-Za-z0-9_.]*\\s*(#.*)?$");
    private static final Pattern SYSTEM_PACKAGE = Pattern.compile(
            "(?m)^\\s*package\\s+system\\b");

    /**
     * Module ids under which the canonical base can already be present in the
     * engine: the id used by this control plane when it installs the base itself,
     * the on-disk id produced by the composed deployment (OPA loads the
     * {@code /policies} directory, so the module is registered as
     * {@code policies/base.rego}), and the flat {@code base.rego} id used by the
     * bare test harness. A repository that already carries the base under any of
     * these IDs must not be reconciled again — re-adding it would duplicate the
     * default rules and the engine would reject the module (which fails closed,
     * but the false alarm must not recur on every boot).
     */
    private static final String[] KNOWN_BASE_MODULE_IDS = {
            "netherite-authz-base",
            "policies/base.rego",
            "base.rego"
    };

    private final OpaPolicyManageClient opaPolicyManageClient;
    private final OpaPolicyEngineClient opaPolicyEngineClient;
    private final PolicyPushProperties properties;
    private final PolicySyncStatus syncStatus;
    private final PolicyRepository policyRepository;
    private final PolicyVersionRepository policyVersionRepository;

    /**
     * Prepares and pushes the Rego of a policy version for a tenant organization.
     * Returns when the module is installed and compiles in the engine; throws if
     * the push is disabled-safe path is skipped or the engine rejects it.
     *
     * @param orgId    tenant organization id (used for the pinned package name)
     * @param regoCode author-authored Rego source of the version being activated
     * @return {@code true} if the module was pushed; {@code false} when the
     *         feature is disabled (caller proceeds with activation unchanged)
     */
    public boolean pushTenantPolicy(UUID orgId, String regoCode) {
        if (!properties.isEnabled()) {
            return false;
        }
        if (!isEndpointSane()) {
            throw new NetheriteException(SystemErrorCode.ERR_POLICY_PUSH_FAILED,
                    "Cannot push tenant policy: policy engine is unreachable");
        }

        String pinned = pinTenantModule(orgId, regoCode);

        // Pre-flight compile against the real engine: never install something OPA refuses.
        OpaPolicyManageResult compile = opaPolicyManageClient.compileModule(
                "tenant-" + orgId + ".rego", pinned);
        if (!compile.successful()) {
            throw new NetheriteException(SystemErrorCode.ERR_POLICY_PUSH_REJECTED,
                    "Policy rejected by the policy engine: " + compile.message());
        }

        String moduleId = tenantModuleId(orgId);
        OpaPolicyManageResult pushed = opaPolicyManageClient.putPolicy(moduleId, pinned);
        if (!pushed.successful()) {
            if (pushed.statusCode() > 0 && pushed.statusCode() < 600) {
                // The engine heard us and rejected the module: the update is invalid.
                // OPA installs modules atomically, so the previously-active module is
                // still in force and the version is not activated.
                throw new NetheriteException(SystemErrorCode.ERR_POLICY_PUSH_REJECTED,
                        "Policy engine rejected installation: " + pushed.message());
            }
            throw new NetheriteException(SystemErrorCode.ERR_POLICY_PUSH_FAILED,
                    "Policy engine unavailable during installation: " + pushed.message());
        }

        log.info("Pushed tenant policy module {} (org {})", moduleId, orgId);
        return true;
    }

    /**
     * Startup reconciliation of the canonical base module. Runs asynchronously so a
     * slow or absent engine never blocks boot; the outcome is recorded for health.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        reconcileBasePolicyAsync();
    }

    public void reconcileBasePolicyAsync() {
        if (!properties.isEnabled()) {
            return;
        }
        new Thread(() -> reconcileBasePolicy(), "opa-policy-reconcile").start();
    }

    /**
     * Periodic reconciliation of the dynamic policy surface. OPA keeps modules
     * in memory, so a daemon restart silently drops every pushed module while
     * the database still lists those versions as ACTIVE — the audited state and
     * the enforced state would drift. This pass re-installs the base if it
     * vanished and re-pushes every ACTIVE tenant version whose module is
     * missing, so recovery happens automatically without a control-plane or
     * application restart.
     */
    @Scheduled(
            initialDelayString = "${netherite.opa.policy-push.reconcile-interval-ms:60000}",
            fixedDelayString = "${netherite.opa.policy-push.reconcile-interval-ms:60000}")
    public void scheduledPolicyReconciliation() {
        if (!properties.isEnabled()) {
            return;
        }
        reconcilePolicyState();
    }

    void reconcilePolicyState() {
        try {
            String base = canonicalBaseRego();

            boolean present = false;
            for (String candidate : KNOWN_BASE_MODULE_IDS) {
                if (opaPolicyManageClient.policyExists(candidate)) {
                    present = true;
                    break;
                }
            }
            if (!present && properties.isLoadBaseIfAbsent()) {
                OpaPolicyManageResult pushed = opaPolicyManageClient.putPolicy(properties.getBasePolicyId(), base);
                present = pushed.successful();
            }

            List<PolicyVersionEntity> active = policyVersionRepository.findAllByStatus("ACTIVE");
            int synced = 0;
            StringBuilder failures = new StringBuilder();
            for (PolicyVersionEntity version : active) {
                UUID orgId = organizationIdOf(version);
                if (orgId == null) {
                    continue;
                }
                String moduleId = tenantModuleId(orgId);
                if (opaPolicyManageClient.policyExists(moduleId)) {
                    synced++;
                    continue;
                }
                try {
                    String pinned = pinTenantModule(orgId, version.getRegoCode());
                    OpaPolicyManageResult pushed = opaPolicyManageClient.putPolicy(moduleId, pinned);
                    if (pushed.successful()) {
                        synced++;
                    } else {
                        failures.append("rejected ").append(moduleId).append(' ');
                        log.warn("Active-version reconciliation could not re-install {}: {}",
                                moduleId, pushed.message());
                    }
                } catch (NetheriteException e) {
                    failures.append("skipped ").append(moduleId).append(' ');
                    log.warn("Active-version reconciliation skipped {}: {}", moduleId, e.getMessage());
                }
            }

            boolean allOk = present && failures.length() == 0;
            String outcome = "basePresent=" + present + "; activeVersions=" + active.size()
                    + ",synced=" + synced
                    + (failures.length() > 0 ? "; failures=" + failures.toString().trim() : "");
            syncStatus.record(present, allOk, outcome);
            if (!allOk) {
                log.warn("Policy reconciliation degraded: {}", outcome);
            }
        } catch (Exception e) {
            syncStatus.record(false, false, "reconciliation failed: " + e.getMessage());
            log.warn("Policy reconciliation failed: {}", e.getMessage());
        }
    }

    /**
     * Resolves the owning organization for an ACTIVE version. The version row is
     * keyed by policy, so the policy row is read for the org id; a defunct row is
     * logged and skipped rather than failing the whole pass.
     */
    private UUID organizationIdOf(PolicyVersionEntity version) {
        try {
            PolicyEntity policy = policyRepository.findById(version.getPolicyId()).orElse(null);
            return policy == null ? null : policy.getOrganizationId();
        } catch (Exception e) {
            log.warn("Could not resolve organization for policy version {}: {}", version.getId(), e.getMessage());
            return null;
        }
    }

    void reconcileBasePolicy() {
        try {
            String base = canonicalBaseRego();

            boolean present = false;
            for (String candidate : KNOWN_BASE_MODULE_IDS) {
                if (opaPolicyManageClient.policyExists(candidate)) {
                    present = true;
                    break;
                }
            }
            if (present) {
                syncStatus.record(true, true, "base module already present");
                return;
            }

            if (properties.isLoadBaseIfAbsent()) {
                OpaPolicyManageResult pushed = opaPolicyManageClient.putPolicy(properties.getBasePolicyId(), base);
                if (!pushed.successful()) {
                    syncStatus.record(false, false, "base module push rejected: " + pushed.message());
                    log.warn("Base policy reconciliation failed: {}", pushed.message());
                    return;
                }
                syncStatus.record(true, true, "base module installed from embedded copy");
                return;
            }

            syncStatus.record(false, false, "base module absent and load-base-if-absent disabled");
        } catch (Exception e) {
            syncStatus.record(false, false, "reconciliation failed: " + e.getMessage());
            log.warn("Base policy reconciliation failed: {}", e.getMessage());
        }
    }

    /**
     * Rewrites a tenant module to the pinned package {@code netherite.authz.tenants.<org>}.
     * An existing {@code package system.*} declaration is rejected outright and any
     * original package line is overwritten — tenants can never extend the engine's
     * administrative authorization policy or any other non-tenant package.
     */
    String pinTenantModule(UUID orgId, String regoCode) {
        if (regoCode == null || regoCode.isBlank()) {
            throw new NetheriteException(SystemErrorCode.ERR_POLICY_PUSH_REJECTED,
                    "Policy source is empty");
        }
        if (regoCode.length() > properties.getMaxRegoBytes()) {
            throw new NetheriteException(SystemErrorCode.ERR_POLICY_PUSH_REJECTED,
                    "Policy source exceeds the " + properties.getMaxRegoBytes() + " byte limit");
        }
        if (SYSTEM_PACKAGE.matcher(regoCode).find()) {
            throw new NetheriteException(SystemErrorCode.ERR_POLICY_PUSH_REJECTED,
                    "Policy declares a reserved system.* package");
        }

        String pinnedPackage = "package netherite.authz.tenants." + tenantPackageSegment(orgId);
        Matcher matcher = PACKAGE_LINE.matcher(regoCode);
        if (matcher.find()) {
            return matcher.replaceFirst(pinnedPackage);
        }
        return pinnedPackage + "\n\n" + regoCode;
    }

    String canonicalBaseRego() {
        try {
            return new ClassPathResource("opa/base.rego").getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new NetheriteException(SystemErrorCode.ERR_POLICY_PUSH_REJECTED,
                    "Embedded canonical base policy missing: " + e.getMessage());
        }
    }

    /**
     * Org-derived identifiers — UUIDs are envelope-safe (lowercase hex + dashes),
     * so no further escaping is required. Non-UUID conventions are normalized to
     * a bounded, safe charset.
     */
    String tenantPackageSegment(UUID orgId) {
        return "org_" + orgId.toString().replace("-", "_").toLowerCase();
    }

    String tenantModuleId(UUID orgId) {
        return "netherite-tenant-" + orgId;
    }

    private boolean isEndpointSane() {
        try {
            return opaPolicyEngineClient.isHealthy() || opaPolicyManageClient.policyExists("netherite-authz-base");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Minimal canary evaluation used by tests/verification to prove the pushed base
     * module actually decides (any decision) rather than erroring.
     */
    PolicyEvaluationResult canaryEvaluate() {
        DecisionContext ctx = DecisionContext.builder()
                .contextId("canary-" + UUID.randomUUID())
                .action(CanonicalAction.builder()
                        .actionId("canary")
                        .actionType("GENERIC")
                        .targetResource("canary:probe")
                        .parameters(java.util.Map.of())
                        .userId("canary")
                        .agentId("canary")
                        .organizationId("canary")
                        .timestamp(Instant.now())
                        .build())
                .businessFacts(java.util.Map.of())
                .stateFacts(java.util.Map.of("riskScore", 5))
                .securitySignals(java.util.Map.of())
                .agentMetrics(java.util.Map.of())
                .assembledAt(Instant.now())
                .build();
        return opaPolicyEngineClient.evaluate(ctx);
    }
}