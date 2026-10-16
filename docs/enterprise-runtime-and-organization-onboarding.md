# Enterprise Runtime & Organization Onboarding

Date: 2026-09-24
Workstream 3: make the **default** Spring profile the real enterprise runtime and add
control-plane organization onboarding so SSO can be enabled per-organization without the
test profile or a development seeder.

---

## 1. Objective

- The default profile (`no profiles` → `default`) must boot only against **PostgreSQL** with
  Flyway migrations **validated** against `ddl-auto: validate` — never H2, never a seeder.
- SSO must be routeable only through **verified** organization domains to an
  organization-specific IdP configuration persisted in Postgres.
- An organization Onboarding control-plane API manages organizations, verified domains, and
  IdP configurations (the organization's designated person; owner-only for cross-tenant
  mutations).
- Proven end-to-end on local infrastructure with a **real** Keycloak SSO flow
  (`employee@netherite.local` → Keycloak → callback → HttpOnly session cookie), with **no
  test profile** in use anywhere.

## 2. Wrong things found (and fixed)

| Problem | Fix |
|---|---|
| Non-test profiles could boot on H2 or with blank owner config; nothing failed fast. | New `RuntimeConfigurationValidator` (`@Profile("!test")`): aborts startup on H2 outside tests, blank/malformed owner email or BCrypt hash, production without `DB_PASSWORD`/HTTPS, OIDC enabled without a callback URL, mismatched callback/issuer origins. |
| `pgverify` is a non-test profile but `application-pgverify.yml` lacked owner credentials → PG boot context broke once the validator existed. | Added `netherite.auth.owner.*` (email + `$2b$10$...` hash) and session cookie properties to `application-pgverify.yml`. |
| Domain discovery could route SSO through **unverified** domains. | `OrganizationDomainRepository.findByDomainAndVerifiedTrue` + `OrganizationDiscoveryService` only resolves verified domains (javadoc documents the security rationale). |
| OIDC `/discover`, `/start`, `/callback` processed requests even when SSO was administratively disabled. | Fail-closed `oidc.enabled` gate → 200 `{available:false, errorCode:"sso_disabled"}`, login redirect `login?error=sso_disabled`, callback rejects outright. Frontend maps `sso_disabled`. |
| No runtime control-plane API to create orgs/domains/IdP config — SSO enablement was only possible via the test seeder. | `ControlPlaneController` (`/v1/control`) + `OrganizationOnboardingService` + DTOs (see §4). |
| No authorization model for onboarding. | WS6: no roles at all. Only active `USER` principals that are the organization's **designated authorized person** may read or mutate their own org; owner-only for creating orgs, listing all orgs, and mutating any org other than the caller's own; an undesignated organization fails closed (403); agents 403; unauthenticated → 401. |
| IdP client secret would round-trip in responses and be unobtainable after save. | Secret is **write-only**: responses expose `clientSecretConfigured`; blank `clientSecret` on update preserves the stored value. |
| `@PathVariable` resolved from parameter names, which fails when compiled **without** `-parameters` (not set in this build) → 400 at runtime. | Explicit `@PathVariable("orgId")` / `@PathVariable("domainId")` in all control-plane endpoints. |
| Admin authorization via IdP claim was untestable against a stubbed IdP. | Real Keycloak realm `netherite` (client `netherite-agent`) with a realm role `netherite-admin` mapped into the ID token as `realm_access`; the org IdP config uses `adminClaimName: realm_access`, `adminClaimValue: netherite-admin`. |

## 3. Runtime configuration guard

`RuntimeConfigurationValidator` blocks startup (exit before serving) when:

- profile is not `test` and the datasource is H2;
- owner email is blank/invalid or password hash isn't a valid BCrypt hash;
- `netherite.environment=production` without `DB_PASSWORD`, non-none OPA URL, notification channels, or HTTPS binding;
- `oidc.enabled=true` with blank callback URL, or callback/issuer mixed-host URLs.

Observed at default-profile boot:

```
RuntimeConfigurationValidator : Runtime configuration validation passed (profile=default,
  netherite.environment=development, oidcEnabled=true, datasource=jdbc:postgresql://localhost:5433/netherite)
```

## 4. Organization onboarding (control plane)

Base path `/v1/control` — authorization: active `USER` principals that are their organization's designated
authorized person, plus deployment-owner-only operations where stated (see
`OrganizationOwnershipService` for the locking lifecycle; no roles).

| Endpoint | Purpose | Authz |
|---|---|---|
| `POST /organizations` | Create organization (owner-only) | owner |
| `GET /organizations` | List own orgs (owner sees all) | owner / designated person |
| `PUT /organizations/{orgId}/domains` | Add domain (`domain`, normalized lowercase) | own org: designated person, other org: owner |
| `POST /organizations/{orgId}/domains/{domainId}/verify` | Mark domain verified (`{"verified":true}`) | as above |
| `DELETE /organizations/{orgId}/domains/{domainId}` | Remove domain | as above |
| `PUT /organizations/{orgId}/idp` | Upsert IdP config (`adminClaimName` required) | as above |
| `DELETE /organizations/{orgId}/idp` | Remove IdP config | as above |
| `GET /organizations/{orgId}/authorized-person` | Read the designation (`userId` null = fail-closed) | designated person or owner |
| `POST /organizations/{orgId}/authorized-person` | Bootstrap the **first** person (refuses to displace one) | owner |
| `PUT /organizations/{orgId}/authorized-person` | Transfer (`userId` + `expectedCurrentUserId`) | current designated person |
| `DELETE /organizations/{orgId}/authorized-person` | Clear → fail-closed state | owner |

Security properties:

- DOMAIN IS NOT VERIFIED UNLESS THE API EXPLICITLY SETS `verified:true`; routing is verified-only.
- `adminClaimName` is mandatory at IdP upsert — SSO authorization fails closed otherwise
  (`isControlPlaneAdmin` denies when the claim is missing).
- client secret is write-only; blank secret on upsert preserves the existing value.
- SSRF guard still applies to all outbound control-plane requests.

## 5. Enterprise runtime topology (default profile only)

```
Browser (Vite :3000) ──proxy/v1──▶ backend (:8080, NetheriteAgent, no profile)
                                     ├─ PostgreSQL :5433 (netherite DB, Flyway V1–V7 validated)
                                     ├─ Keycloak :8180 realm 'netherite' (OIDC IdP)
                                     └─ IdP config + verified domain from DB (per-org)
```

- Flyway validates all 7 migrations; `ddl-auto: validate`; Hikari pool to Postgres.
- No `DevelopmentDataSeeder` in the default runtime (it is test-profile-only).
- The owner org (`netherite`, slug `netherite`) is auto-provisioned at first owner login.
- SSO: authorization-code + PKCE (S256) → Keycloak → callback validates issuer/audience/
  exp/nbf/nonce/signature → verified-domain org routing → `realm_access` admin claim check →
  server-side session delivered **only** as `Set-Cookie: neth_sid` (HttpOnly, SameSite=Lax),
  then 302 to `http://localhost:3000/auth/callback`.

## 6. Local environment configuration

Tools (under `.tools/`): JDK 21, Maven, PostgreSQL 16.4 (data `pgdata/netherite`), Keycloak 26.7.4,
Edge (playwright `msedge` channel) for E2E.

| Service | Port | Credentials / Notes |
|---|---|---|
| PostgreSQL | 5433 | `netherite_admin`, password empty (trust); databases `netherite`, `netherite_verify`, `netherite_upgrade` |
| Keycloak | 8180 | master `admin`/`admin123`; realm `netherite` |
| Backend (default profile) | 8080 | owner `owner@example.com` / disposable test-only password (see `e2e/ws2-human-session.e2e.mjs` `OWNER_PASSWORD`; never production) |
| Frontend (Vite) | 3000 | dev server proxying `/v1` → :8080 |

Backend boot env (default profile):

```
JAVA_HOME=...\.tools\jdk-21
SERVER_PORT=8080
DB_HOST=localhost  DB_PORT=5433  DB_NAME=netherite  DB_USER=netherite_admin  DB_PASSWORD=   (trust)
OIDC_ENABLED=true
OIDC_EXTERNAL_BASE_URL=http://localhost:8080
OIDC_CALLBACK_BASE_URL=http://localhost:3000
```

Keycloak provisioning (executed; reproducible). The client secret below is a
**local-Dev harness-only fixture** (realm `netherite` on :8180). Production
client secrets are operator-issued per organization through the control-plane
IdP configuration and are never recorded here:

```
kcadm.bat create realms            -s realm=netherite -s enabled=true
kcadm.bat create clients -r netherite   clientId=netherite-agent, confidential,
                                        secret=netherite-agent-secret,
                                        redirectUris=["http://localhost:8080/v1/auth/oidc/callback"],
                                        webOrigins=["http://localhost:3000","http://localhost:8080"]
kcadm.bat create roles  -r netherite           -s name=netherite-admin
kcadm.bat create users  -r netherite           username=employee@netherite.local, email=..., enabled=tru
e, emailVerified=true
kcadm.bat set-password -r netherite --userid <uid> --new-password "Workforce@2026"
kcadm.bat add-roles    -r netherite --uusername employee@netherite.local --rolename netherite-admin
mapped realm roles into the ID token: client mapper 'realm-roles-for-id-token'
  (oidc-...-role-mapper, claim.name=realm_access, multivalued=true, id.token.claim=true)
```

Onboarding executed via the control plane (owner session cookie):

1. `POST /v1/auth/login` owner → `Set-Cookie: neth_sid`.
2. `PUT /v1/control/organizations/{ownerOrg}/domains` `{"domain":"netherite.local"}`.
3. `POST .../domains/{domainId}/verify` `{"verified":true}`.
4. `PUT /v1/control/organizations/{ownerOrg}/idp` with issuer `http://localhost:8180/realms/netherite`,
   client `netherite-agent`, secret, endpoints (auth/token/userinfo/jwks), scopes
   `openid email profile`, `adminClaimName=realm_access`, `adminClaimValue=netherite-admin`,
   redirectUri `http://localhost:8080/v1/auth/oidc/callback`, status `ACTIVE`.

## 7. Verification results

- Backend full suite: **373 tests / 35 suites — 0 failures, 0 errors** (unit + H2 + legacy +
  Phase6/7 + WS2 security + WS3 onboarding/validator/gate tests).
- Real-PG ITs (profile `pgverify`): `EnterpriseRuntimeIT` (6) — default-profile-shaped runtime on
  `netherite_verify`, validated Flyway V1–V7, no seeder artifacts (no `netherite-dev` slug, no KB-issued
  domain/issuer pre-seeded), onboarding round-trip, secret-preservation, two-org verified-only routing;
  `PostgresApprovalPersistenceIT` (6), `PostgresUpgradePathIT` (2).
- Browser E2E `e2e/ws3-real-sso.e2e.mjs` — full real SSO against Keycloak: **ALL CHECKS PASSED**
  (redirect to real IdP, no credential in auth URL, HttpOnly+Lax+Path=/+localhost
  `neth_sid`, no credential in JS-visible storage, `/v1/auth/me` → `employee@netherite.local`,
  and no `role` field anywhere (WS6) — the `realm_access` claim only gates SSO login, no
  credential in bodies).
- Browser E2E `e2e/ws2-human-session.e2e.mjs` (re-run on default profile): **ALL CHECKS PASSED**
  (owner login, logout invalidation, SSO redirect with PKCE `S256`/state/nonce).
- Frontend: `tsc && vite build` clean (1593 modules, ~304 kB JS / 87 kB gzip).

## 8. Key files

- `backend/netherite-app/src/main/java/io/netherite/config/RuntimeConfigurationValidator.java`
- `backend/netherite-app/src/main/java/io/netherite/api/ControlPlaneController.java`
- `backend/netherite-app/src/main/java/io/netherite/api/model/control/*.java`
- `backend/netherite-app/src/main/java/io/netherite/domain/service/OrganizationOnboardingService.java`
- `backend/netherite-app/src/main/java/io/netherite/domain/repository/OrganizationDomainRepository.java`
- `backend/netherite-app/src/main/java/io/netherite/auth/service/OrganizationDiscoveryService.java`
- `backend/netherite-app/src/main/java/io/netherite/auth/service/OidcService.java` (claims/admin)
- `backend/netherite-app/src/main/java/io/netherite/auth/controller/OidcController.java`
- `backend/netherite-app/src/test/resources/application-pgverify.yml`
- `e2e/ws3-real-sso.e2e.mjs`, `e2e/ws2-human-session.e2e.mjs`
- `backend/netherite-app/src/test/java/io/netherite/onboarding/*`, `io/netherite/pgverify/EnterpriseRuntimeIT.java`