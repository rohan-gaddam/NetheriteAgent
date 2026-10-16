package io.netherite.domain.repository;

import io.netherite.domain.entity.ActionParameterSchemaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ActionParameterSchemaRepository extends JpaRepository<ActionParameterSchemaEntity, UUID> {

    List<ActionParameterSchemaEntity> findAllByOrganizationIdAndResourceIdOrderByActionTypeAscVersionNumberDesc(
            UUID organizationId, UUID resourceId);

    List<ActionParameterSchemaEntity> findAllByOrganizationIdAndResourceIdAndActionTypeOrderByVersionNumberDesc(
            UUID organizationId, UUID resourceId, String actionType);

    Optional<ActionParameterSchemaEntity> findByOrganizationIdAndResourceIdAndActionTypeAndStatus(
            UUID organizationId, UUID resourceId, String actionType, String status);

    Optional<ActionParameterSchemaEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);
}
