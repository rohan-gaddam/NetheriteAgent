package io.netherite.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * WS8 — bounded application metrics.
 *
 * <p>Every metric uses a fixed name and fixed tag <em>values</em> (enum names, fixed
 * mechanism strings, route names from configuration). No raw identifiers ever become
 * tags, so registry cardinality is constant. {@link NetheriteMetrics#NOOP} is usable in
 * classes that are also constructed directly in unit tests.</p>
 */
class NetheriteMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final NetheriteMetrics metrics = new NetheriteMetrics(registry);

    @Test
    void countsAuthenticationFailuresByMechanism() {
        metrics.authFailure("user_session");
        metrics.authFailure("user_session");
        metrics.authFailure("agent_api_key");

        assertThat(registry.find("netherite.security.auth_failures_total")
                .tag("mechanism", "user_session").counter()).isNotNull();
        assertThat(registry.find("netherite.security.auth_failures_total")
                .tag("mechanism", "user_session").counter().count()).isEqualTo(2);
        assertThat(registry.find("netherite.security.auth_failures_total")
                .tag("mechanism", "agent_api_key").counter().count()).isEqualTo(1);
    }

    @Test
    void countsAuthorizationDenialsByPrincipalAndSurface() {
        metrics.authorizationDenied("USER", "actions");

        assertThat(registry.get("netherite.security.authorization_denials_total")
                .tag("principalType", "USER")
                .tag("surface", "actions").counter().count()).isEqualTo(1);
    }

    @Test
    void countsRateLimitRejectionsByTierAndRoute() {
        metrics.rateLimitRejected("identity", "payment");

        assertThat(registry.get("netherite.security.rate_limit_rejections_total")
                .tag("tier", "identity")
                .tag("route", "payment").counter().count()).isEqualTo(1);
    }

    @Test
    void recordsOpaOutcomeCounterAndDurationTimer() {
        metrics.opaEvaluation("allow", 1_000_000L);
        metrics.opaEvaluation("allow", 2_000_000L);
        metrics.opaEvaluation("deny", 500_000L);

        assertThat(registry.get("netherite.opa.evaluations_total")
                .tag("outcome", "allow").counter().count()).isEqualTo(2);
        assertThat(registry.get("netherite.opa.evaluations_total")
                .tag("outcome", "deny").counter().count()).isEqualTo(1);
        assertThat(registry.get("netherite.opa.evaluation_duration_seconds")
                .tag("outcome", "allow").timer().count()).isEqualTo(2);
    }

    @Test
    void countsDependencyFailuresDimensionlessTags() {
        metrics.redisFailure("lock");
        metrics.redisFailure("rate_limit");
        metrics.auditWriteFailure();
        metrics.appError("ERR_FORBIDDEN");

        assertThat(registry.get("netherite.redis.operations_failed_total")
                .tag("type", "lock").counter().count()).isEqualTo(1);
        assertThat(registry.get("netherite.redis.operations_failed_total")
                .tag("type", "rate_limit").counter().count()).isEqualTo(1);
        assertThat(registry.get("netherite.audit.write_failures_total").counter().count()).isEqualTo(1);
        assertThat(registry.get("netherite.app.errors_total")
                .tag("code", "ERR_FORBIDDEN").counter().count()).isEqualTo(1);
    }

    @Test
    void noopInstanceIsUsableWithoutARealRegistry() {
        NetheriteMetrics noop = NetheriteMetrics.NOOP;
        assertThatCode(() -> {
            noop.authFailure("owner_login");
            noop.authorizationDenied("AGENT", "agents");
            noop.rateLimitRejected("pre_auth", "login");
            noop.opaEvaluation("error", 1);
            noop.redisFailure("lock");
            noop.auditWriteFailure();
            noop.appError("ERR_INTERNAL_SERVER_ERROR");
        }).doesNotThrowAnyException();
    }

    @Test
    void tagSetIsStableAndBounded() {
        SimpleMeterRegistry isolated = new SimpleMeterRegistry();
        NetheriteMetrics isolatedMetrics = new NetheriteMetrics(isolated);
        isolatedMetrics.authFailure("user_session");
        isolatedMetrics.authFailure("agent_api_key");
        isolatedMetrics.authFailure("oidc");
        isolatedMetrics.authFailure("owner_login");

        // Only the four documented mechanism values may ever appear.
        assertThat(isolated.find("netherite.security.auth_failures_total")
                .meters().stream().flatMap(m -> m.getId().getTags().stream())
                .filter(t -> "mechanism".equals(t.getKey())).map(t -> t.getValue()))
                .containsExactlyInAnyOrder("user_session", "agent_api_key", "oidc", "owner_login");
    }
}