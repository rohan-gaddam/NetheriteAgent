package io.netherite.common.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Metadata capturing the origin of the requested agent action.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SourceMetadata {
    private String clientIp;
    private String userAgent;
    private String traceId;
    private String spanId;
    private String framework;
}
