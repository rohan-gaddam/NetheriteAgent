package io.netherite.api.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BindingCreateRequest {
    @NotNull(message = "Agent ID is required")
    private String agentId;

    @NotNull(message = "Resource ID is required")
    private String resourceId;

    private List<String> allowedActions;
}
