package io.netherite.api.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BindingResponse {
    private String id;
    private String organizationId;
    private String agentId;
    private String resourceId;
    private List<String> allowedActions;
    private boolean active;
    private Instant createdAt;
    private Instant updatedAt;
}
