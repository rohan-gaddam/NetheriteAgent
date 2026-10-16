CREATE TABLE resource_health (
    id              UUID PRIMARY KEY,
    resource_id     UUID NOT NULL,
    organization_id UUID NOT NULL,
    resource_name   VARCHAR(255) NOT NULL,
    status          VARCHAR(50) NOT NULL DEFAULT 'UNKNOWN',
    failure_reason  TEXT,
    last_checked_at TIMESTAMP,
    last_healthy_at TIMESTAMP,
    last_failure_at TIMESTAMP,
    check_source    VARCHAR(50) DEFAULT 'SCHEDULED',
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX idx_resource_health_resource_id ON resource_health(resource_id);
CREATE INDEX idx_resource_health_org_id ON resource_health(organization_id);
