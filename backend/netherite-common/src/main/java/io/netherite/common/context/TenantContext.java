package io.netherite.common.context;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Encapsulates the resolved multi-tenant security context for the current request thread.
 *
 * <p><b>WS6: there is deliberately no {@code roles} field.</b> This context used to
 * carry a role set that {@code AuthorizationFilter} consulted as a permission
 * matrix. Role-based access control is removed. Authorization is now decided by
 * comparing {@link #userId} against the organization's single designated
 * authorized person, which lives on the {@code organizations} row. Removing the
 * field structurally prevents a role from silently re-entering the decision.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantContext {
    private String organizationId;
    private String organizationSlug;
    private String agentId;
    private String userId;
    private String principalType; // AGENT, USER, SYSTEM
}
