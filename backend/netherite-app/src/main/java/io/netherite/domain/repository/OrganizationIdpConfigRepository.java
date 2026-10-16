package io.netherite.domain.repository;

import io.netherite.domain.entity.OrganizationIdpConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrganizationIdpConfigRepository extends JpaRepository<OrganizationIdpConfigEntity, UUID> {

    Optional<OrganizationIdpConfigEntity> findByOrganizationId(UUID organizationId);

    Optional<OrganizationIdpConfigEntity> findByOrganizationIdAndStatus(UUID organizationId, String status);

    Optional<OrganizationIdpConfigEntity> findByIssuerUrlAndStatus(String issuerUrl, String status);
}
