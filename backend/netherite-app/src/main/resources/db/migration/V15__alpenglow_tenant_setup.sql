-- ============================================================================
-- V15: AlpenGlow Enterprise tenant setup
--
-- Persists the AlpenGlow Enterprise organization (tenant) and its
-- administrator user in NetheriteAgent's control-plane database so the
-- tenant identity survives application restarts.
--
-- Design follows the existing WS6 model: one designated authorized person
-- per organization, no RBAC, no role hierarchy. The tenant identity is
-- established server-side from the database record -- never trusted from
-- client-supplied parameters.
--
-- Seeded credential: email admin@alpenglow.example.com. Password hash is
-- securely generated and stored at rest using BCrypt. Production deployments
-- must rotate or provision tenant users through the control plane with
-- hashes delivered via secrets.
-- ============================================================================

-- 1. AlpenGlow Enterprise organization (tenant). Idempotent: re-running
--    this migration leaves an existing organization untouched.
INSERT INTO organizations (id, name, slug, status, created_at, updated_at)
VALUES (uuid_generate_v4(), 'AlpenGlow Enterprise', 'alpenglow', 'ACTIVE',
        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (slug) DO NOTHING;

-- 2. AlpenGlow administrator user. Password hash is stored at rest using
--    BCrypt (same mechanism as ApiKeyHashService and the existing owner
--    login). The plaintext password is never written to this migration or
--    any log. Idempotent: re-running leaves an existing user untouched.
INSERT INTO users (id, organization_id, email, password_hash, full_name, status,
                   created_at, updated_at)
SELECT uuid_generate_v4(), o.id, 'admin@alpenglow.example.com',
       '$2a$10$SUkY0kWHsB0.kvxesIvMUO6ji.3PNKCWHheoJLQQFdyCRZ/zqXdGu',
       'AlpenGlow Administrator', 'ACTIVE',
       CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM organizations o
WHERE o.slug = 'alpenglow'
ON CONFLICT (email) DO NOTHING;

-- 3. Designate the AlpenGlow administrator as the organization's single
--    authorized representative (WS6). Nullable by design: the column only
--    changes when no designation exists yet, so a re-run never displaces an
--    existing authorized person. The composite foreign key guarantees the
--    designated person belongs to this organization.
UPDATE organizations o
SET authorized_person_user_id = (
    SELECT u.id FROM users u WHERE u.email = 'admin@alpenglow.example.com'
)
WHERE o.slug = 'alpenglow'
  AND o.authorized_person_user_id IS NULL;
