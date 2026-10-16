package io.netherite.api.model.control;

import java.time.Instant;

/**
 * WS6 — an organization's authorized person.
 *
 * <p>Exactly one organization has at most one of these. {@code userId} is null
 * when the organization has never had a person designated, which is the
 * fail-closed state: that organization admits no configuration administration
 * until the trusted control plane bootstraps a person.</p>
 */
public record AuthorizedPersonView(
        String organizationId,
        String userId,
        String email,
        String fullName,
        boolean active,
        Instant designatedAt) {
}
