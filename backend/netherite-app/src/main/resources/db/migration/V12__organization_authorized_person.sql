-- ============================================================================
-- V12: Organization authorized person (WS6)
--
-- NetheriteAgent uses a deliberately simple organization authorization model:
-- each organization has AT MOST ONE currently designated authorized person who
-- may administer that organization's configuration (its agents, resources,
-- agent-resource bindings and policies).
--
-- This is NOT role-based access control. There is no role hierarchy, no
-- permission matrix and no per-role grant tables. A single nullable column on
-- `organizations` is the whole model.
--
-- Design notes:
--   1. The single-column representation makes "at most one" a structural property
--      of the schema rather than an application convention: a second concurrent
--      writer simply overwrites the same column, so two simultaneous authorized
--      people for one organization are unrepresentable at the storage layer.
--   2. The column is NULLABLE so that this migration never has to invent
--      ownership. Existing organizations are left WITHOUT a designated person
--      and therefore fail closed on configuration administration until an
--      authorized owner is designated through the trusted control plane.
--      Auto-assigning the first user of an organization would be exactly the
--      insecure automatic ownership claim WS6 forbids.
--   3. The composite foreign key (authorized_person_user_id, id) ->
--      users(id, organization_id) is the database-level guarantee that the
--      designated person is a member of THAT organization. Without it,
--      application logic alone would be the only thing preventing a cross-tenant
--      designation. SET NULL is restricted to authorized_person_user_id so that
--      removing a user record clears the designation (leaving the organization
--      without an authorized person, which fails closed) instead of attempting
--      to null the organization's own primary key.
--      Requires PostgreSQL 15+ for the column-list SET NULL form; the project
--      targets PostgreSQL 16 (docker-compose postgres:16-alpine).
--
-- V1..V8 are frozen. This migration is strictly additive (one new column, one
-- new index, one new constraint) and preserves all existing records.
-- ============================================================================

-- 1. Referenced-column support for the composite foreign key below. The primary
--    key on users(id) alone does not satisfy a composite foreign key, so the
--    referenced (id, organization_id) pair needs its own unique index.
CREATE UNIQUE INDEX IF NOT EXISTS uq_users_id_organization_id
    ON users (id, organization_id);

-- 2. The model itself: at most one designated authorized person per organization.
ALTER TABLE organizations
    ADD COLUMN IF NOT EXISTS authorized_person_user_id UUID;

-- 3. The designated person MUST belong to the organization they administer.
ALTER TABLE organizations
    ADD CONSTRAINT fk_organizations_authorized_person
    FOREIGN KEY (authorized_person_user_id, id)
    REFERENCES users (id, organization_id)
    ON DELETE SET NULL (authorized_person_user_id);
