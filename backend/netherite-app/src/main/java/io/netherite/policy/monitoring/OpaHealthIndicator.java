package io.netherite.policy.monitoring;

import io.netherite.policy.push.PolicySyncStatus;
import io.netherite.policy.spi.OpaPolicyEngineClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Reports reachability of the Open Policy Agent engine to the actuator health
 * endpoint. OPA is a fail-closed dependency: when it is unreachable every policy
 * evaluation returns ERR_SYSTEM_UNAVAILABLE (503), never ALLOW — so the control
 * plane keeps serving while this indicator lets operators watch recovery.
 *
 * <p>When the policy-push mechanism is active, its last reconciliation outcome
 * (base module presence, sync status) is appended as additional detail.
 *
 * Note: OPA deliberately participates in the overall {@code /actuator/health}
 * but is EXCLUDED from the liveness/readiness probes (see application.yml health
 * groups) so an OPA outage can never crash-loop a replica. Readiness reflects
 * whether the durable control plane (database) can be reached; a replica whose
 * OPA is down still answers every request quickly with fail-closed 503s.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "netherite.opa.policy-path")
public class OpaHealthIndicator implements HealthIndicator {

    private final OpaPolicyEngineClient opaPolicyEngineClient;
    private final PolicySyncStatus policySyncStatus;

    /** Test-friendly constructor without policy-sync tracking. */
    public OpaHealthIndicator(OpaPolicyEngineClient opaPolicyEngineClient) {
        this(opaPolicyEngineClient, null);
    }

    @Autowired
    public OpaHealthIndicator(
            OpaPolicyEngineClient opaPolicyEngineClient,
            ObjectProvider<PolicySyncStatus> policySyncStatusProvider) {
        this.opaPolicyEngineClient = opaPolicyEngineClient;
        this.policySyncStatus = policySyncStatusProvider != null ? policySyncStatusProvider.getIfAvailable() : null;
    }

    @Override
    public Health health() {
        Health.Builder builder = opaPolicyEngineClient.isHealthy()
                ? Health.up().withDetail("dependency", "opa")
                : Health.down()
                        .withDetail("dependency", "opa")
                        .withDetail("failClosed", true)
                        .withDetail("effect", "policy evaluation returns ERR_SYSTEM_UNAVAILABLE (503), never ALLOW");

        if (policySyncStatus != null && policySyncStatus.isReconciled()) {
            builder.withDetail("policySync", java.util.Map.of(
                    "basePolicyPresent", policySyncStatus.isBasePolicyPresent(),
                    "lastOutcome", policySyncStatus.getLastOutcome(),
                    "lastAttemptAt", String.valueOf(policySyncStatus.getLastAttemptAt())));
        }
        return builder.build();
    }
}