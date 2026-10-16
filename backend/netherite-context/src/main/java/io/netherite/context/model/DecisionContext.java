package io.netherite.context.model;

import io.netherite.common.model.CanonicalAction;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * Immutable decision context assembled from trusted fact providers, state, and runtime signals.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DecisionContext {
    private String contextId;
    private CanonicalAction action;
    private Map<String, Object> businessFacts;
    private Map<String, Object> stateFacts;
    private Map<String, Object> securitySignals;
    private Map<String, Object> agentMetrics;
    private Instant assembledAt;
    private String contextChecksum;
}
