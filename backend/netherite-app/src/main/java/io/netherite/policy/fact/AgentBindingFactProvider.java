package io.netherite.policy.fact;

import io.netherite.common.model.CanonicalAction;
import io.netherite.context.spi.FactProvider;
import io.netherite.domain.entity.AgentEntity;
import io.netherite.domain.entity.AgentResourceBindingEntity;
import io.netherite.domain.entity.ResourceEntity;
import io.netherite.domain.repository.AgentRepository;
import io.netherite.domain.repository.AgentResourceBindingRepository;
import io.netherite.domain.repository.ResourceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Fact provider that resolves agent binding and trust tier facts from the control plane database.
 * These are trusted facts originating from the system's own state, not from client input.
 */
@Component
@RequiredArgsConstructor
public class AgentBindingFactProvider implements FactProvider {

    private final AgentRepository agentRepository;
    private final AgentResourceBindingRepository bindingRepository;
    private final ResourceRepository resourceRepository;

    @Override
    public String getProviderId() {
        return "agent-binding-state";
    }

    @Override
    public Set<String> getSupportedFactKeys() {
        return Set.of("agentTrustTier", "agentStatus", "isBound", "resourceExists",
                "allowedActions", "bindingActive", "resourceSensitivity", "riskScore");
    }

    @Override
    public CompletableFuture<Map<String, Object>> resolveFacts(CanonicalAction action, Set<String> requestedKeys) {
        Map<String, Object> facts = new HashMap<>();

        UUID orgId = UUID.fromString(action.getOrganizationId());
        UUID agentId = UUID.fromString(action.getAgentId());

        SensorFacts sensor = new SensorFacts();

        if (requestedKeys.contains("agentTrustTier") || requestedKeys.contains("agentStatus")) {
            agentRepository.findByIdAndOrganizationId(agentId, orgId).ifPresent(agent -> {
                facts.put("agentTrustTier", agent.getTrustTier());
                facts.put("agentStatus", agent.getStatus());
                sensor.trustTier = agent.getTrustTier();
                sensor.status = agent.getStatus();
            });
        }

        if (requestedKeys.contains("resourceExists")) {
            boolean exists = resourceRepository.findAllByOrganizationId(orgId).stream()
                    .anyMatch(r -> r.getName().equalsIgnoreCase(action.getTargetResource()));
            facts.put("resourceExists", exists);
        }

        if (requestedKeys.contains("isBound") || requestedKeys.contains("allowedActions")
                || requestedKeys.contains("bindingActive")) {
            bindingRepository.findAllByOrganizationId(orgId).stream()
                    .filter(b -> b.getAgentId().equals(agentId))
                    .findFirst()
                    .ifPresent(binding -> {
                        facts.put("isBound", true);
                        facts.put("bindingActive", binding.isActive());
                        facts.put("allowedActions", binding.getAllowedActions());
                    });
        }

        if (requestedKeys.contains("resourceSensitivity") || requestedKeys.contains("riskScore")) {
            resourceRepository.findAllByOrganizationId(orgId).stream()
                    .filter(r -> r.getName().equalsIgnoreCase(action.getTargetResource()))
                    .findFirst()
                    .ifPresent(r -> {
                        facts.put("resourceSensitivity", r.getSensitivityLevel().name());
                        sensor.sensitivity = r.getSensitivityLevel().name();
                    });
        }

        if (requestedKeys.contains("riskScore")) {
            // Deterministic, server-side risk score derived solely from trusted control-plane
            // state (resource sensitivity + agent trust tier + agent status). Client input is
            // never consulted. The value feeds the Rego risk gates (<50 ALLOW, >=50 approval).
            facts.put("riskScore", computeRiskScore(sensor.sensitivity, sensor.trustTier, sensor.status));
        }

        return CompletableFuture.completedFuture(facts);
    }

    /**
     * Deterministic risk score in [0..100] from trusted control-plane state.
     * Never throws; when a dimension is unknown it contributes its conservative default.
     */
    static int computeRiskScore(String sensitivity, String trustTier, String status) {
        int sensitivityBase;
        switch (sensitivity == null ? "" : sensitivity.toUpperCase()) {
            case "LOW" -> sensitivityBase = 10;
            case "MEDIUM" -> sensitivityBase = 25;
            case "HIGH" -> sensitivityBase = 45;
            case "CRITICAL" -> sensitivityBase = 60;
            default -> sensitivityBase = 20;
        }

        int tierPenalty;
        switch (trustTier == null ? "" : trustTier.toUpperCase()) {
            case "TIER_1" -> tierPenalty = 0;
            case "TIER_2" -> tierPenalty = 10;
            case "TIER_3" -> tierPenalty = 20;
            default -> tierPenalty = 15;
        }

        int statusPenalty;
        switch (status == null ? "" : status.toUpperCase()) {
            case "ACTIVE" -> statusPenalty = 0;
            case "SUSPENDED", "REVOKED" -> statusPenalty = 50;
            default -> statusPenalty = 15;
        }

        return Math.max(0, Math.min(100, sensitivityBase + tierPenalty + statusPenalty));
    }

    private static final class SensorFacts {
        private String sensitivity;
        private String trustTier;
        private String status;
        private SensorFacts() {
        }
    }
}
