package io.netherite.domain.repository;

import io.netherite.domain.entity.ActionRouteEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ActionRouteRepository extends JpaRepository<ActionRouteEntity, UUID> {

    Optional<ActionRouteEntity> findByOrganizationIdAndResourceIdAndActionTypeAndActiveTrue(
            UUID organizationId, UUID resourceId, String actionType);

    Optional<ActionRouteEntity> findByOrganizationIdAndResourceIdAndActionType(
            UUID organizationId, UUID resourceId, String actionType);

    List<ActionRouteEntity> findAllByOrganizationIdAndResourceId(
            UUID organizationId, UUID resourceId);

    List<ActionRouteEntity> findAllByOrganizationIdAndResourceIdAndActiveTrue(
            UUID organizationId, UUID resourceId);
}
