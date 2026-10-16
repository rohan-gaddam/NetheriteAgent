package io.netherite.api.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentResponse {
    private String id;
    private String organizationId;
    private String name;
    private String description;
    private String trustTier;
    private String status;
    private String keyIdentifier;
    private String secretToken;
    private String fullApiKey;
    private Instant createdAt;
}
