-- ============================================================================
-- V14: Registered action parameter schemas (LLM normalization Step 2)
--
-- A registered (resource, action) pair may declare an operator-authored,
-- versioned parameter schema. The semantic action promoter consumes the ACTIVE
-- version as the only deterministic source of parameter truth; when no active
-- schema exists, supplied parameters stay unverifiable and are rejected
-- (fail-closed) rather than assumed valid.
--
-- Design notes:
--   1. Versioned and explicit: rows are drafts until activated, and at most one
--      version per (organization, resource, action) is ACTIVE at a time. The
--      unique key makes a duplicate version number unrepresentable; the partial
--      status index makes the active lookup a single, cheap probe.
--   2. Tenant-isolated: the composite foreign key (resource_id, organization_id)
--      -> resources(id, organization_id) is the database-level guarantee that a
--      schema belongs to a resource of the SAME organization. Without it, only
--      application code would prevent a cross-tenant schema. This mirrors the
--      V12 authorized-person composite-FK pattern, and requires the referenced
--      (id, organization_id) pair to have its own unique index.
--   3. Non-permissive by construction: the schema_definition column stores a
--      strict, validated JSON-Schema subset and is re-validated on read, so a
--      corrupted or hand-edited definition never becomes a weaker rule.
--
-- Forward-only, additive, non-destructive. V1..V13 are untouched.
-- ============================================================================

-- 1. Referenced-column support for the composite foreign key below.
CREATE UNIQUE INDEX IF NOT EXISTS uq_resources_id_organization_id
    ON resources (id, organization_id);

-- 2. The schema table itself.
CREATE TABLE IF NOT EXISTS action_parameter_schemas (
    id                UUID         NOT NULL,
    organization_id   UUID         NOT NULL,
    resource_id       UUID         NOT NULL,
    action_type       VARCHAR(128) NOT NULL,
    version_number    INTEGER      NOT NULL,
    schema_definition JSONB        NOT NULL,
    status            VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    created_by        UUID,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    activated_at      TIMESTAMPTZ,
    CONSTRAINT pk_action_parameter_schemas PRIMARY KEY (id),
    CONSTRAINT uq_action_parameter_schema_version
        UNIQUE (organization_id, resource_id, action_type, version_number),
    CONSTRAINT ck_action_parameter_schema_status
        CHECK (status IN ('DRAFT', 'ACTIVE', 'SUPERSEDED')),
    CONSTRAINT fk_action_parameter_schemas_resource
        FOREIGN KEY (resource_id, organization_id)
        REFERENCES resources (id, organization_id)
        ON DELETE CASCADE
);

-- 3. Active-schema lookup: (organization, resource, action, status).
CREATE INDEX IF NOT EXISTS idx_action_parameter_schemas_active
    ON action_parameter_schemas (organization_id, resource_id, action_type, status);
