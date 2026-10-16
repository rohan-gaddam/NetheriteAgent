package io.netherite.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

/**
 * Per-organization audit hash-chain head.
 *
 * <p>One row per organization anchors the tail of the append-only audit chain. Writers
 * serialize on this row (pessimistic lock), so a read-compute-write of the previous hash is
 * race-free across replicas; a cluster cannot fork the chain. The row is inserted lazily with
 * the genesis hash and moved forward on every committed audit record.</p>
 */
@Entity
@Table(name = "audit_chain_heads")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditChainHeadEntity {

    public static final String GENESIS_HASH = "0000000000000000000000000000000000000000000000000000000000000000";

    @Id
    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "last_hash", nullable = false, length = 64)
    private String lastHash;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        if (this.lastHash == null) {
            this.lastHash = GENESIS_HASH;
        }
        if (this.updatedAt == null) {
            this.updatedAt = Instant.now();
        }
    }
}