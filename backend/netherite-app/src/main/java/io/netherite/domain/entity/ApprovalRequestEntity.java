package io.netherite.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "approval_requests")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApprovalRequestEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(name = "user_id")
    private String userId;

    @Column(name = "action_type", nullable = false)
    private String actionType;

    @Column(name = "target_resource", nullable = false)
    private String targetResource;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "canonical_action", nullable = false)
    private Map<String, Object> canonicalAction;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "decision_context", nullable = false)
    private Map<String, Object> decisionContext;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "triggered_policies", nullable = false)
    private java.util.List<String> triggeredPolicies;

    @Column(name = "status", nullable = false, length = 50)
    @Builder.Default
    private String status = "PENDING";

    @Column(name = "approver_decision", length = 50)
    private String approverDecision;

    @Column(name = "approver_notes", columnDefinition = "TEXT")
    private String approverNotes;

    @Column(name = "approved_by_user_id")
    private UUID approvedByUserId;

    @Column(name = "approver_user_identifier")
    private String approverUserIdentifier;

    @Column(name = "execution_result", columnDefinition = "TEXT")
    private String executionResult;

    @Column(name = "execution_error", columnDefinition = "TEXT")
    private String executionError;

    @Column(name = "execution_in_progress", nullable = false)
    @Builder.Default
    private Boolean executionInProgress = false;

    @Column(name = "execution_claim_started_at")
    private Instant executionClaimStartedAt;

    @Column(name = "execution_lease_expires_at")
    private Instant executionLeaseExpiresAt;

    @Column(name = "execution_attempts", nullable = false)
    @Builder.Default
    private int executionAttempts = 0;

    @Column(name = "execution_claimed_by", length = 64)
    private String executionClaimedBy;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    @Column(name = "version", nullable = false)
    @Builder.Default
    private Long version = 0L;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        if (this.status == null) {
            this.status = "PENDING";
        }
    }
}
