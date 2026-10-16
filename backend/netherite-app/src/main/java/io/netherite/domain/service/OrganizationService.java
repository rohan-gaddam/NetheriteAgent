package io.netherite.domain.service;

import io.netherite.common.exception.NetheriteException;
import io.netherite.common.exception.SystemErrorCode;
import io.netherite.domain.entity.OrganizationEntity;
import io.netherite.domain.repository.OrganizationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrganizationService {

    private final OrganizationRepository organizationRepository;

    @Transactional
    public OrganizationEntity createOrganization(String name, String slug) {
        if (name == null || name.isBlank()) {
            throw new NetheriteException(SystemErrorCode.ERR_INVALID_PARAMETERS, "Organization name cannot be blank");
        }
        if (slug == null || slug.isBlank()) {
            throw new NetheriteException(SystemErrorCode.ERR_INVALID_PARAMETERS, "Organization slug cannot be blank");
        }

        String normalizedSlug = slug.trim().toLowerCase();
        if (organizationRepository.existsBySlug(normalizedSlug)) {
            throw new NetheriteException(SystemErrorCode.ERR_INVALID_PARAMETERS, "Organization slug already exists: " + normalizedSlug);
        }

        OrganizationEntity entity = OrganizationEntity.builder()
                .name(name.trim())
                .slug(normalizedSlug)
                .status("ACTIVE")
                .build();

        return organizationRepository.save(entity);
    }

    @Transactional(readOnly = true)
    public OrganizationEntity getRequiredOrganization(UUID id) {
        return organizationRepository.findById(id)
                .orElseThrow(() -> new NetheriteException(SystemErrorCode.ERR_RESOURCE_NOT_FOUND, "Organization not found: " + id));
    }

    @Transactional(readOnly = true)
    public Optional<OrganizationEntity> findBySlug(String slug) {
        return organizationRepository.findBySlug(slug.trim().toLowerCase());
    }
}
