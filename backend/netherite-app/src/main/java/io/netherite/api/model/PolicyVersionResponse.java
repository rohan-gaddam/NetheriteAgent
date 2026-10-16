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
public class PolicyVersionResponse {
    private String id;
    private String policyId;
    private int versionNumber;
    private String naturalLanguagePrompt;
    private String regoCode;
    private String status;
    private Instant createdAt;
    private Instant activatedAt;
}
