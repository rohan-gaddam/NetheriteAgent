package io.netherite.admin.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Minimal 2-step onboarding request for registering an AI Agent.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentRegistrationRequest {

    @NotBlank(message = "Agent name is required")
    private String name;

    private String description;
}
