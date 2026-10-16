package io.netherite.policy.push;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for the OPA policy-push mechanism.
 *
 * <p>Push is disabled by default so a bare policy engine with a mounted base
 * module (the docker-compose topology) keeps working without a management
 * credential. When enabled, the control plane becomes responsible for keeping
 * dynamic policy content (tenant policy packages and base-policy reconciliation)
 * synchronized with the engine.
 */
@Data
@Component
@ConfigurationProperties(prefix = "netherite.opa.policy-push")
public class PolicyPushProperties {

    /** Master switch. When false nothing is pushed and activation skips the engine. */
    private boolean enabled = false;

    /**
     * On startup, load the canonical base module ({@code netherite.authz}) into the
     * engine if no module is already present. A mounted base module satisfies the
     * presence check (GET /v1/policies/{id} returns 200), so no redundant push occurs.
     */
    private boolean loadBaseIfAbsent = true;

    /** Id of the base module in the engine's policy store. */
    private String basePolicyId = "netherite-authz-base";

    /** Canonical base module, embedded as a classpath resource. */
    private String basePolicyResource = "classpath:opa/base.rego";

    /** Rego source size cap (chars) beyond which a tenant policy is rejected. */
    private int maxRegoBytes = 100_000;

    /** Canary: maximum engine acceptance time before startup reconciliation fails closed. */
    private int startupTimeoutMillis = 10_000;

    /**
     * Periodic reconciliation interval in ms. While the push is enabled the control
     * plane re-verifies that every ACTIVE policy version is present in the engine
     * (OPA keeps modules in memory, so a daemon restart silently drops them) and
     * re-installs anything missing. Default 60 s; the first run also fires shortly
     * after boot so recovery does not require an application restart.
     */
    private long reconcileIntervalMs = 60_000;
}