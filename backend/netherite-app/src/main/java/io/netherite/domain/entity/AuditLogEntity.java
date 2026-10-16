package io.netherite.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Append-only, tamper-evident audit record.
 *
 * <p>{@code @Immutable} prevents ORM-mediated UPDATE/DELETE of managed entities;
 * PostgreSQL additionally enforces append-only with a rejecting trigger (V9). The unique
 * key is tenant-scoped so one tenant can never suppress another tenant's audit rows by
 * pre-claiming a shared request id.</p>
 */
@Entity
@Immutable
@Table(name = "audit_logs", uniqueConstraints = {
        @UniqueConstraint(name = "uk_audit_request_event", columnNames = {"organization_id", "request_id", "event_type"})
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "request_id", nullable = false)
    private String requestId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "agent_id")
    private UUID agentId;

    @Column(name = "user_id")
    private String userId;

    @Column(name = "action_type", nullable = false)
    private String actionType;

    @Column(name = "target_resource", nullable = false)
    private String targetResource;

    @Column(name = "decision", nullable = false, length = 50)
    private String decision;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "policy_evaluations")
    private Map<String, Object> policyEvaluations;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "redacted_parameters")
    private Map<String, Object> redactedParameters;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "redacted_execution_result")
    private Map<String, Object> redactedExecutionResult;

    @Column(name = "execution_duration_ms", nullable = false)
    @Builder.Default
    private long executionDurationMs = 0;

    @Column(name = "prev_record_hash", nullable = false, length = 64)
    private String prevRecordHash;

    @Column(name = "current_record_hash", nullable = false, length = 64)
    private String currentRecordHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }
}
