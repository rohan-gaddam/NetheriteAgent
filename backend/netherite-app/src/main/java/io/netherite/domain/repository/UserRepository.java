package io.netherite.domain.repository;

import io.netherite.domain.entity.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<UserEntity, UUID> {

    List<UserEntity> findAllByOrganizationId(UUID organizationId);

    Optional<UserEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);

    Optional<UserEntity> findByEmail(String email);

    boolean existsByEmail(String email);

    Optional<UserEntity> findByOidcSubjectAndOidcIssuer(String oidcSubject, String oidcIssuer);

    Optional<UserEntity> findByEmailAndOidcIssuerIsNull(String email);
}
