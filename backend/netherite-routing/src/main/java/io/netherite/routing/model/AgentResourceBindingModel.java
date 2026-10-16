package io.netherite.routing.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

/**
 * Explicit authorization binding linking an Agent to a Resource for specific Actions.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentResourceBindingModel {
    private String bindingId;
    private String organizationId;
    private String agentId;
    private String resourceId;
    private Set<String> allowedActionTypes;
    private boolean active;
}
