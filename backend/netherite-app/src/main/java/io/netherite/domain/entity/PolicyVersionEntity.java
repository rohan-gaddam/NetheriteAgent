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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "policy_versions", uniqueConstraints = {
    @UniqueConstraint(name = "uq_policy_version", columnNames = {"policy_id", "version_number"})
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PolicyVersionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "policy_id", nullable = false)
    private UUID policyId;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    @Column(name = "natural_language_prompt", columnDefinition = "TEXT")
    private String naturalLanguagePrompt;

    @Column(name = "rego_code", nullable = false, columnDefinition = "TEXT")
    private String regoCode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "parsed_ast", columnDefinition = "jsonb")
    private String parsedAst;

    @Column(name = "status", nullable = false, length = 50)
    @Builder.Default
    private String status = "DRAFT";

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }
}
