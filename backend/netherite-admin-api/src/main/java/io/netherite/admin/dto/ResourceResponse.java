package io.netherite.admin.dto;

import io.netherite.common.model.ConnectorType;
import io.netherite.common.model.SensitivityLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * Enterprise resource representation for dashboard.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResourceResponse {
    private String id;
    private String organizationId;
    private String name;
    private ConnectorType connectorType;
    private String baseUrl;
    private String description;
    private SensitivityLevel sensitivityLevel;
    private String status;
    private List<String> boundAgentIds;
    private Instant createdAt;
}
