package io.netherite.domain.repository;

import io.netherite.domain.entity.OrganizationDomainEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrganizationDomainRepository extends JpaRepository<OrganizationDomainEntity, UUID> {

    Optional<OrganizationDomainEntity> findByDomain(String domain);

    Optional<OrganizationDomainEntity> findByDomainAndVerifiedTrue(String domain);

    List<OrganizationDomainEntity> findByOrganizationId(UUID organizationId);

    boolean existsByDomain(String domain);
}
