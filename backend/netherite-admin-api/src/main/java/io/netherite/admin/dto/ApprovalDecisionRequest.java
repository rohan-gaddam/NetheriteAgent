package io.netherite.admin.dto;

import io.netherite.approval.model.ApprovalStatus;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Human approver decision payload.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApprovalDecisionRequest {

    @NotNull(message = "Decision (APPROVED/REJECTED) is required")
    private ApprovalStatus decision;

    private String notes;
}
