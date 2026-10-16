package io.netherite.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
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
import java.util.Map;
import java.util.UUID;

/**
 * Operator-authored, versioned parameter schema for a registered (resource, action) pair.
 *
 * <p>A schema row is immutable configuration: drafts are created and then explicitly
 * activated; at most one version per (organization, resource, action) may be
 * {@code ACTIVE} at a time. The definition is stored as a strict, validated subset of
 * JSON Schema (see {@code ActionParameterSchemaParser}) and re-validated on read, so a
 * corrupted or hand-edited row never silently becomes a permissive default.</p>
 */
@Entity
@Table(
        name = "action_parameter_schemas",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_action_parameter_schema_version",
                        columnNames = {"organization_id", "resource_id", "action_type", "version_number"})
        },
        indexes = {
                @Index(
                        name = "idx_action_parameter_schemas_active",
                        columnList = "organization_id, resource_id, action_type, status")
        })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActionParameterSchemaEntity {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_SUPERSEDED = "SUPERSEDED";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "resource_id", nullable = false)
    private UUID resourceId;

    @Column(name = "action_type", nullable = false, length = 128)
    private String actionType;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "schema_definition", nullable = false)
    private Map<String, Object> schemaDefinition;

    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = STATUS_DRAFT;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
        if (this.status == null) {
            this.status = STATUS_DRAFT;
        }
    }
}
