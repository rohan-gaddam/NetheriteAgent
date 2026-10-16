package io.netherite.policy.spi;

import io.netherite.context.model.DecisionContext;
import io.netherite.policy.model.PolicyEvaluationResult;

/**
 * SPI for evaluating decision context against Open Policy Agent Rego policies.
 */
public interface OpaPolicyEngineClient {

    /**
     * Evaluates all applicable organization policies for the provided decision context.
     */
    PolicyEvaluationResult evaluate(DecisionContext decisionContext);

    /**
     * Checks if the OPA engine is reachable and healthy.
     */
    boolean isHealthy();
}
