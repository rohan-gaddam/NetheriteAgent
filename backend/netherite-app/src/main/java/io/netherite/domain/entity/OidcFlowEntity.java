package io.netherite.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "oidc_flows")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OidcFlowEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "state", nullable = false, unique = true, length = 255)
    private String state;

    @Column(name = "nonce", nullable = false, length = 255)
    private String nonce;

    @Column(name = "code_verifier", nullable = false, length = 255)
    private String codeVerifier;

    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(name = "idp_config_id")
    private UUID idpConfigId;

    @Column(name = "redirect_uri", length = 500)
    private String redirectUri;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }

    public boolean isExpired() {
        return Instant.now().isAfter(this.expiresAt);
    }
}
