package io.netherite.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "email", nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "full_name")
    private String fullName;

    /**
     * Deliberately NO role field (WS6).
     *
     * <p>This entity used to carry a {@code Role} enum mapped to
     * {@code users.role}. RBAC is removed: an organization's authority is the
     * single designated person recorded on {@link OrganizationEntity}, and there
     * is no per-user role, hierarchy or permission set anywhere in the schema.
     * See {@code V13__remove_role_based_authorization.sql}.</p>
     */
    @Column(name = "status", nullable = false, length = 50)
    @Builder.Default
    private String status = "ACTIVE";

    @Column(name = "oidc_subject")
    private String oidcSubject;

    @Column(name = "oidc_issuer")
    private String oidcIssuer;

    @Column(name = "idp_provider")
    private String idpProvider;

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
            this.status = "ACTIVE";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
