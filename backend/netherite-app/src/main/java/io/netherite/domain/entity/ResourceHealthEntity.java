package io.netherite.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "resource_health", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"resource_id"})
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResourceHealthEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "resource_id", nullable = false)
    private UUID resourceId;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "resource_name", nullable = false)
    private String resourceName;

    @Column(name = "status", nullable = false, length = 50)
    @Builder.Default
    private String status = "UNKNOWN";

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    @Column(name = "last_checked_at")
    private Instant lastCheckedAt;

    @Column(name = "last_healthy_at")
    private Instant lastHealthyAt;

    @Column(name = "last_failure_at")
    private Instant lastFailureAt;

    @Column(name = "check_source", length = 50)
    @Builder.Default
    private String checkSource = "SCHEDULED";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.status == null) {
            this.status = "UNKNOWN";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
