package io.netherite.policy;

import io.netherite.common.model.CanonicalAction;
import io.netherite.common.model.DecisionType;
import io.netherite.context.model.DecisionContext;
import io.netherite.policy.client.OpaPolicyEngineClientImpl;
import io.netherite.policy.client.OpaPolicyManageClientImpl;
import io.netherite.policy.model.OpaPolicyManageResult;
import io.netherite.policy.model.PolicyEvaluationResult;
import io.netherite.policy.model.PolicyRuleResult;
import io.netherite.policy.spi.OpaPolicyManageClient;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test against a REAL enforcement-mode Open Policy Agent: one started
 * with {@code --authentication=token --authorization=basic} plus the
 * {@code system.authz} policy that pins admins to a shared bearer token. This is
 * the exact machine-language of batch E: unaudited callers are rejected 401, the
 * authenticated control plane can evaluate AND push tenant policy, and
 * engine-lever errors fail closed.
 *
 * <p>Needs a second, authenticated OPA besides the plain 8181 harness:
 * <pre>
 *   REALOPA_AUTH_URL=http://localhost:8282 REALOPA_TOKEN=secret \
 *       mvn -o test -pl netherite-app -Dnetherite.realopa-it=true
 * </pre>
 * The auth IT is skipped when the credential URL is not provided.
 */
@EnabledIfSystemProperty(named = "netherite.realopa-it", matches = "true")
class RealOpaAuthAndPushIT {

    private static String authUrl;
    private static String token;

    @BeforeAll
    static void resolveTarget() {
        authUrl = System.getenv("REALOPA_AUTH_URL");
        token = System.getenv("REALOPA_TOKEN");
        Assumptions.assumeTrue(authUrl != null && !authUrl.isBlank(),
                "REALOPA_AUTH_URL not set; skipping authenticated-OPA contract");
    }

    private OpaPolicyEngineClientImpl engine(String url, String token) {
        return new OpaPolicyEngineClientImpl(url, "/v1/data/netherite/authz/evaluation_output", 5000, token);
    }

    private OpaPolicyManageClient manage(String token) {
        return new OpaPolicyManageClientImpl(authUrl, 5000, null, token);
    }

    private static DecisionContext context(String org, int riskScore) {
        return DecisionContext.builder()
                .contextId("ctx-" + org)
                .action(CanonicalAction.builder()
                        .actionId("act-" + org)
                        .actionType("GENERIC")
                        .targetResource("invoice:123")
                        .parameters(Map.of())
                        .userId("user-1")
                        .agentId("agent-1")
                        .organizationId(org)
                        .timestamp(Instant.now())
                        .build())
                .businessFacts(Map.of())
                .stateFacts(Map.of("riskScore", riskScore))
                .securitySignals(Map.of())
                .agentMetrics(Map.of())
                .assembledAt(Instant.now())
                .build();
    }

    private static DecisionType resolve(List<PolicyRuleResult> rules) {
        boolean deny = rules.stream().anyMatch(r -> r.getDecision() == DecisionType.DENY);
        boolean approval = rules.stream().anyMatch(r -> r.getDecision() == DecisionType.HUMAN_APPROVAL);
        boolean allow = rules.stream().anyMatch(r -> r.getDecision() == DecisionType.ALLOW);
        if (deny) return DecisionType.DENY;
        if (approval) return DecisionType.HUMAN_APPROVAL;
        if (allow) return DecisionType.ALLOW;
        return DecisionType.DENY;
    }

    @Test
    void unauthenticatedEvaluateFailsClosed() {
        PolicyEvaluationResult result = engine(authUrl, null).evaluate(context("org-auth", 10));
        assertThat(result.isSuccessful()).isFalse();
    }

    @Test
    void wrongTokenEvaluateFailsClosed() {
        PolicyEvaluationResult result = engine(authUrl, "not-the-token").evaluate(context("org-auth", 10));
        assertThat(result.isSuccessful()).isFalse();
    }

    @Test
    void authenticatedEvaluateDecidesLowRiskAllow() {
        PolicyEvaluationResult result = engine(authUrl, token).evaluate(context("org-auth", 10));
        assertThat(result.isSuccessful()).isTrue();
        assertThat(resolve(result.getRuleResults())).isEqualTo(DecisionType.ALLOW);
    }

    @Test
    void unauthenticatedPushRejected() {
        OpaPolicyManageResult result = manage(null).putPolicy("netherite-tenant-org-nope",
                "package netherite.authz.tenants.org_nope\n\ndefault decision := \"DENY\"");
        assertThat(result.successful()).isFalse();
    }

    @Test
    void authenticatedPushAndTenantIsolation() {
        OpaPolicyManageClient authed = manage(token);
        String org = UUID.randomUUID().toString();
        String moduleId = "netherite-tenant-" + org;
        String pkg = "netherite.authz.tenants.org_" + org.replace("-", "_");

        OpaPolicyManageResult pushed = authed.putPolicy(moduleId,
                "package " + pkg + "\n\n"
                        + "decision := \"DENY\" if {\n"
                        + "    input.action.resource == \"blocked:resource\"\n"
                        + "}\n");
        assertThat(pushed.successful()).as("push accepted: " + pushed.message()).isTrue();

        // The tenant module now restricts this org only.
        DecisionType blocked = resolve(engine(authUrl, token)
                .evaluate(DecisionContext.builder()
                        .contextId("c1").action(CanonicalAction.builder()
                                .actionId("a1").actionType("GENERIC")
                                .targetResource("blocked:resource")
                                .parameters(Map.of()).userId("u").agentId("ag").organizationId(org)
                                .timestamp(Instant.now()).build())
                        .businessFacts(Map.of()).stateFacts(Map.of("riskScore", 10))
                        .securitySignals(Map.of()).agentMetrics(Map.of())
                        .assembledAt(Instant.now()).build())
                .getRuleResults());
        assertThat(blocked).isEqualTo(DecisionType.DENY);

        // A different org is unaffected (isolation).
        DecisionType other = resolve(engine(authUrl, token)
                .evaluate(context("org-unrelated", 10)).getRuleResults());
        assertThat(other).isEqualTo(DecisionType.ALLOW);

        authed.deletePolicy(moduleId);
    }

    @Test
    void malformedModuleRejectedByEngineInstallAutoritatively() {
        // OPA /v1/compile answers 200 even for a parse-broken module as long as a
        // query is supplied (verified live). The authoritative validator is
        // PUT /v1/policies: it parses and installs atomically, so a parse-broken
        // module must be refused there — that is the guarantee the control plane
        // (and the 422 ERR_POLICY_PUSH_REJECTED path) relies on.
        OpaPolicyManageClient authed = manage(token);
        String broken = "package netherite.authz.tenants.org_broken\n\nth[i]s is not rego";

        OpaPolicyManageResult install = authed.putPolicy("netherite-tenant-broken", broken);
        assertThat(install.successful()).isFalse();
    }

    @Test
    void wrongPackageModuleRejectedAsConflict() {
        // A module claiming the shared base package must not install (it would collide
        // with the mounted base.rego and OPA must refuse the PUT).
        OpaPolicyManageResult result = manage(token)
                .putPolicy("netherite-attempt-base",
                        "package netherite.authz\n\ndefault decision := \"ALLOW\"\n");
        assertThat(result.successful()).isFalse();
    }
}