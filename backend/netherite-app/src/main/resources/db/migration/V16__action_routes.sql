-- ============================================================================
-- V16: Action-Specific API Routing
--
-- Adds the action_routes table to support operator-configured, action-specific
-- routing rules (HTTP method, endpoint path template, connector configuration)
-- per registered enterprise resource.
--
-- Design follows the established architecture:
--   1. Tenant-isolated: composite foreign key (resource_id, organization_id)
--      references resources(id, organization_id), matching V12 and V14.
--   2. Unique constraint on (organization_id, resource_id, action_type).
--   3. Non-destructive, additive, forward-only.
-- ============================================================================

CREATE TABLE IF NOT EXISTS action_routes (
    id                UUID         NOT NULL,
    organization_id   UUID         NOT NULL,
    resource_id       UUID         NOT NULL,
    action_type       VARCHAR(128) NOT NULL,
    http_method       VARCHAR(16)  NOT NULL,
    path_template     VARCHAR(512) NOT NULL,
    connector_config  JSONB,
    is_active         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_action_routes PRIMARY KEY (id),
    CONSTRAINT uq_action_routes_org_res_action
        UNIQUE (organization_id, resource_id, action_type),
    CONSTRAINT fk_action_routes_resource
        FOREIGN KEY (resource_id, organization_id)
        REFERENCES resources (id, organization_id)
        ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_action_routes_lookup
    ON action_routes (organization_id, resource_id, action_type, is_active);
