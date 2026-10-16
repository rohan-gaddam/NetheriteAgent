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
public class UserResponse {
    private String id;
    private String organizationId;
    private String email;
    private String fullName;

    /**
     * WS6: whether this user is its organization's single designated authorized
     * person. Replaces the removed {@code role} field — authority is an
     * organization-level fact, not a per-user attribute.
     */
    private boolean authorizedPerson;

    private String status;
    private Instant createdAt;
}
