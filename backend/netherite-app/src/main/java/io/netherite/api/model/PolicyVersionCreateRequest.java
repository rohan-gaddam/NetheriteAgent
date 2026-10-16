package io.netherite.api.model;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PolicyVersionCreateRequest {
    @NotBlank(message = "Rego code is required")
    private String regoCode;
    private String naturalLanguagePrompt;
}
