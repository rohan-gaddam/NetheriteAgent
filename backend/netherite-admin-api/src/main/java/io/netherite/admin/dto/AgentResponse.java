package io.netherite.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Response returned after agent registration displaying the endpoint and initial key.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentResponse {
    private String id;
    private String organizationId;
    private String name;
    private String description;
    private String endpointUrl;
    private String status;
    private String keyIdentifier;
    private String secretToken; // Returned ONCE during creation
    private Instant createdAt;
}
