package io.netherite.audit.spi;

import io.netherite.audit.model.AuditRecord;

import java.util.List;
import java.util.Optional;

/**
 * SPI for recording tamper-evident audit records and verifying chain continuity.
 */
public interface AuditService {

    /**
     * Appends an audit record to the organization's tamper-evident cryptographic hash chain.
     */
    AuditRecord record(AuditRecord record);

    /**
     * Retrieves an audit record by ID.
     */
    Optional<AuditRecord> findById(String organizationId, String auditId);

    /**
     * Verifies the SHA-256 hash continuity of the audit trail for the organization.
     */
    boolean verifyChainContinuity(String organizationId, List<AuditRecord> records);
}
