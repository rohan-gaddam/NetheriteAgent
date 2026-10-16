package io.netherite.context.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Declares the facts required to build trusted decision context for a particular action.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FactDescriptor {
    private String actionType;
    private String resourceType;
    private List<String> requiredFactKeys;
    private long timeoutMillis;
}
