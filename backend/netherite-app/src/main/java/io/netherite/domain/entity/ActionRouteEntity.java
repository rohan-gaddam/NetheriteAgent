package io.netherite.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
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
 * Action-specific routing configuration for a registered enterprise resource.
 *
 * <p>Enables individual registered actions to define their exact HTTP method,
 * path template, and connector configuration rather than sharing an unparameterized
 * resource base URL. Enforces tenant isolation via composite foreign key.</p>
 */
@Entity
@Table(
        name = "action_routes",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_action_routes_org_res_action",
                        columnNames = {"organization_id", "resource_id", "action_type"})
        },
        indexes = {
                @Index(
                        name = "idx_action_routes_lookup",
                        columnList = "organization_id, resource_id, action_type, is_active")
        })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActionRouteEntity {

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

    @Column(name = "http_method", nullable = false, length = 16)
    private String httpMethod;

    @Column(name = "path_template", nullable = false, length = 512)
    private String pathTemplate;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "connector_config")
    private Map<String, Object> connectorConfig;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
