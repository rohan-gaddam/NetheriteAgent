package io.netherite.domain.repository;

import io.netherite.domain.entity.PolicyVersionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PolicyVersionRepository extends JpaRepository<PolicyVersionEntity, UUID> {

    List<PolicyVersionEntity> findAllByPolicyId(UUID policyId);

    List<PolicyVersionEntity> findAllByStatus(String status);

    Optional<PolicyVersionEntity> findByIdAndPolicyId(UUID id, UUID policyId);

    Optional<PolicyVersionEntity> findByPolicyIdAndStatus(UUID policyId, String status);
}
