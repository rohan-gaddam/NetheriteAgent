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
@Table(name = "organization_idp_config")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrganizationIdpConfigEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "organization_id", nullable = false, unique = true)
    private UUID organizationId;

    @Column(name = "issuer_url", nullable = false, length = 500)
    private String issuerUrl;

    @Column(name = "client_id", nullable = false, length = 255)
    private String clientId;

    @Column(name = "client_secret", length = 500)
    private String clientSecret;

    @Column(name = "discovery_url", length = 500)
    private String discoveryUrl;

    @Column(name = "authorization_endpoint", length = 500)
    private String authorizationEndpoint;

    @Column(name = "token_endpoint", length = 500)
    private String tokenEndpoint;

    @Column(name = "userinfo_endpoint", length = 500)
    private String userinfoEndpoint;

    @Column(name = "jwks_uri", length = 500)
    private String jwksUri;

    @Column(name = "scopes")
    @Builder.Default
    private String scopes = "openid email profile";

    @Column(name = "claim_email")
    @Builder.Default
    private String claimEmail = "email";

    @Column(name = "claim_name")
    @Builder.Default
    private String claimName = "name";

    @Column(name = "claim_roles")
    private String claimRoles;

    @Column(name = "claim_groups")
    private String claimGroups;

    @Column(name = "admin_claim_name")
    private String adminClaimName;

    @Column(name = "admin_claim_value")
    private String adminClaimValue;

    @Column(name = "redirect_uri", length = 500)
    private String redirectUri;

    @Column(name = "status", nullable = false, length = 50)
    @Builder.Default
    private String status = "ACTIVE";

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
