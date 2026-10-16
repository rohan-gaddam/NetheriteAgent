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
public class PolicyResponse {
    private String id;
    private String organizationId;
    private String name;
    private String description;
    private String status;
    private String activeVersionId;
    private List<PolicyVersionResponse> versions;
    private Instant createdAt;
    private Instant updatedAt;
}
