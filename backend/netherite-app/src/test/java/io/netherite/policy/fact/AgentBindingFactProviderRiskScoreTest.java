package io.netherite.policy.fact;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static io.netherite.policy.fact.AgentBindingFactProvider.computeRiskScore;

/**
 * Determinism of the server-side risk score: derived exclusively from trusted
 * control-plane state (resource sensitivity + agent trust tier + agent status),
 * clamped to [0..100], never null-throwing.
 */
class AgentBindingFactProviderRiskScoreTest {

    @Test
    void lowSensitivityTrustedActiveAgentIsLowRisk() {
        assertThat(computeRiskScore("LOW", "TIER_1", "ACTIVE")).isEqualTo(10);
    }

    @Test
    void mediumSensitivityTier2ActiveAgentIsModerateRisk() {
        assertThat(computeRiskScore("MEDIUM", "TIER_2", "ACTIVE")).isEqualTo(35);
    }

    @Test
    void highSensitivitySuspendsApprovalRisk() {
        // HIGH(45) + TIER_3(20) + SUSPENDED(50) = 115 → clamped to 100 (never below the human gate).
        assertThat(computeRiskScore("HIGH", "TIER_3", "SUSPENDED")).isEqualTo(100);
    }

    @Test
    void criticalSensitivityEvenTier1ActiveCrossesGlobalGate() {
        // CRITICAL(60) + TIER_1(0) + ACTIVE(0) = 60 → requires human approval.
        assertThat(computeRiskScore("CRITICAL", "TIER_1", "ACTIVE")).isEqualTo(60);
    }

    @Test
    void revokedStatusIsRiskInflating() {
        assertThat(computeRiskScore("LOW", "TIER_1", "REVOKED")).isEqualTo(60);
    }

    @Test
    void unknownDimensionsFallBackToConservativeDefaults() {
        assertThat(computeRiskScore(null, null, null)).isEqualTo(50);
    }

    @Test
    void caseInsensitiveAndLenientInputs() {
        assertThat(computeRiskScore("low", "tier_1", "active")).isEqualTo(10);
        assertThat(computeRiskScore("UNKNOWN_SENSITIVITY", "bogus", "WEIRD")).isEqualTo(50);
    }
}