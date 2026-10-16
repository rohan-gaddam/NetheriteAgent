package io.netherite.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Registry-backed application metrics with deliberately bounded dimensions (WS8).
 *
 * <p>Every metric uses a fixed name and a fixed set of tag <em>values</em> that is either
 * an enum name ({@code AGENT}, {@code USER}), a mechanism string from a small constant
 * set, or a route/error-code value originating from configuration or an enum. Raw user,
 * organization, agent or action identifiers are never used as tags; identifiers are
 * reduced to bounded enumerations or omitted entirely. This keeps the Prometheus
 * cardinality constant no matter how many tenants or agents exist.</p>
 *
 * <p>Instantiate via Spring with the application {@link MeterRegistry}; classes that are
 * also constructed in direct unit tests (where no registry exists) receive
 * {@link #NOOP} through their test-friendly constructor overload.</p>
 */
@Component
public class NetheriteMetrics {

    /** No-op instance used when a bean that also gets unit-constructed has no registry. */
    public static final NetheriteMetrics NOOP = new NetheriteMetrics(new SimpleMeterRegistry());

    private final MeterRegistry registry;

    public NetheriteMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * Authentication failure on a protected surface.
     *
     * @param mechanism member of {@code user_session}, {@code agent_api_key},
     *                  {@code oidc}, {@code owner_login}
     */
    public void authFailure(String mechanism) {
        counter("netherite.security.auth_failures_total", "mechanism", mechanism);
    }

    /**
     * Authorization denial after a successfully established principal (WS6 single
     * designated authorized person model).
     *
     * @param principalType {@code USER} or {@code AGENT}
     * @param surface       first path segment of the denied surface (bounded path set)
     */
    public void authorizationDenied(String principalType, String surface) {
        counter("netherite.security.authorization_denials_total", "principalType", principalType, "surface", surface);
    }

    /**
     * Rate-limit bucket rejection.
     *
     * @param tier  {@code pre_auth} or {@code identity}
     * @param route configured route name (bounded set from application.yml)
     */
    public void rateLimitRejected(String tier, String route) {
        counter("netherite.security.rate_limit_rejections_total", "tier", tier, "route", route);
    }

    /**
     * Policy evaluation completed with the final decision.
     *
     * @param outcome       {@code allow}, {@code deny}, {@code approval} or {@code error}
     * @param durationNanos evaluation wall time
     */
    public void opaEvaluation(String outcome, long durationNanos) {
        counter("netherite.opa.evaluations_total", "outcome", outcome);
        Timer timer = Timer.builder("netherite.opa.evaluation_duration_seconds")
                .tags("outcome", outcome)
                .register(registry);
        timer.record(durationNanos, TimeUnit.NANOSECONDS);
    }

    /**
     * A Redis coordination operation failed (lock, rate-limit store, idempotency, health).
     *
     * @param type member of {@code lock}, {@code rate_limit}
     */
    public void redisFailure(String type) {
        counter("netherite.redis.operations_failed_total", "type", type);
    }

    /**
     * An audit record write failed; the audit chain stays authoritative and tamper-evident.
     */
    public void auditWriteFailure() {
        counter("netherite.audit.write_failures_total");
    }

    /**
     * A standardized application/API error was returned.
     *
     * @param code member of the {@code SystemErrorCode} enum names plus the handler-specific
     *             codes ({@code VALIDATION_FAILED}, {@code ERR_INTERNAL_SERVER_ERROR}) — a
     *             bounded set of stable values
     */
    public void appError(String code) {
        counter("netherite.app.errors_total", "code", code);
    }

    private void counter(String name, String... tags) {
        Counter.builder(name).tags(tags).register(registry).increment();
    }
}