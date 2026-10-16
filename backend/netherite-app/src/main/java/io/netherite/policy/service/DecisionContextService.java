package io.netherite.policy.service;

import io.netherite.common.model.CanonicalAction;
import io.netherite.context.model.DecisionContext;
import io.netherite.context.spi.FactProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Builds a trusted DecisionContext from a CanonicalAction by collecting facts from registered FactProviders.
 * Client-provided values are never trusted as facts — only facts from registered providers are included.
 */
@Service
@RequiredArgsConstructor
public class DecisionContextService {

    private final List<FactProvider> factProviders;

    public DecisionContext buildDecisionContext(CanonicalAction action) {
        Map<String, Object> stateFacts = new HashMap<>();
        Map<String, Object> businessFacts = new HashMap<>();
        Map<String, Object> securitySignals = new HashMap<>();

        for (FactProvider provider : factProviders) {
            Set<String> supportedKeys = provider.getSupportedFactKeys();
            if (!supportedKeys.isEmpty()) {
                Map<String, Object> resolved = provider.resolveFacts(action, supportedKeys)
                        .join();
                resolved.forEach((key, value) -> {
                    if (provider.getProviderId().startsWith("agent-binding")) {
                        stateFacts.put(key, value);
                    } else if (provider.getProviderId().startsWith("business")) {
                        businessFacts.put(key, value);
                    } else {
                        securitySignals.put(key, value);
                    }
                });
            }
        }

        String contextId = java.util.UUID.randomUUID().toString();
        Instant assembledAt = Instant.now();

        String checksum = computeChecksum(action, stateFacts, businessFacts, securitySignals, assembledAt);

        return DecisionContext.builder()
                .contextId(contextId)
                .action(action)
                .businessFacts(businessFacts)
                .stateFacts(stateFacts)
                .securitySignals(securitySignals)
                .agentMetrics(Map.of())
                .assembledAt(assembledAt)
                .contextChecksum(checksum)
                .build();
    }

    private String computeChecksum(CanonicalAction action, Map<String, Object> state,
                                   Map<String, Object> business, Map<String, Object> security,
                                   Instant timestamp) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String data = action.getActionId() + action.getActionType() + action.getTargetResource()
                    + action.getOrganizationId() + action.getAgentId()
                    + state.hashCode() + business.hashCode() + security.hashCode()
                    + timestamp.toEpochMilli();
            byte[] hash = digest.digest(data.getBytes());
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            return "checksum-unavailable";
        }
    }
}
