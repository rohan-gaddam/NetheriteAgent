package io.netherite.policy.push;

import lombok.Getter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Immutable snapshot of the last policy-push reconciliation outcome, surfaced
 * through the OPA health indicator so operators can see synchronization state at
 * a glance. Never holds policy content — only status metadata.
 */
@Getter
@Component
@ConditionalOnProperty(name = "netherite.opa.policy-path")
public class PolicySyncStatus {

    private volatile boolean reconciled;
    private volatile boolean basePolicyPresent;
    private volatile Instant lastAttemptAt;
    private volatile String lastOutcome;

    public void record(boolean basePolicyPresent, boolean success, String outcome) {
        this.basePolicyPresent = basePolicyPresent;
        this.reconciled = success;
        this.lastAttemptAt = Instant.now();
        this.lastOutcome = outcome;
    }
}