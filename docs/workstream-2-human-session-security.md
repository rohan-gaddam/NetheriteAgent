# WORKSTREAM 2 — HUMAN SESSION SECURITY

**Goal.** Eliminate plaintext human-session credential leakage (URL, log) at the OIDC callback, and across the whole console surface, by moving human session credentials exclusively into a server-side session delivered via an `HttpOnly` cookie. Restore OIDC employee + owner sign-in parity on the same mechanism and prove — with security tests, a browser E2E, and a repo-wide scan — that the credential never appears in URLs, query/fragment, logs, HTML, client-visible API responses, telemetry, exception messages, or audit records.

---

## 1. Discovered (vulnerable) flow

The human login flow issued an opaque `user_sessions.session_token` (a UUID) to the client and required the browser to echo it on every request:

1. **Backend** — `OidcController.handleCallback()`:
   - logged the raw session token: `log.info("OIDC authentication successful: sessionToken={}")`;
   - returned the frontend redirect *with the token in the URL*:
     `callbackBaseUrl + "/auth/callback?sessionToken=" + session.getSessionToken()`
     (quote/history/referrer/log leakage; a session-stealing primitive if the redirect were ever fetched by middleware);
   - on token-exchange failure, the exception could embed the IdP response body (which can contain an `id_token`/`access_token`) into the log message.
2. **Backend** — `AuthController.handleOwnerLogin()` returned `sessionToken` in the JSON body.
3. **Frontend** — stored the token in `localStorage['netherite_auth']` and sent it as `X-Netherite-User-Token` on every API call (`AuthContext.tsx`, `client.ts`), placing the credential one XSS away from exfiltration and in JS-accessible storage.
4. **Backend** — `UserAuthenticationFilter` authenticated from the `X-Netherite-User-Token` header.

`X-Netherite-User-Token` was internal-only: nothing but the frontend and the app's own tests ever used it (no M2M consumer), so removing the header mechanism had no external contract.

## 2. Secure flow (implemented)

- **Server-side session remains the credential artifact** (`user_sessions`), but it is now delivered **only** inside an `HttpOnly` cookie and is never returned in bodies, never appended to URLs, and never logged.
- Enrollment points: owner `/v1/auth/login` and OIDC `/v1/auth/oidc/callback` both call `SessionCookieService.createSessionCookie(...)`, which does `response.addHeader(Set-Cookie, ...)` on the response.
- Authentication: `UserAuthenticationFilter` reads the cookie via `SessionCookieService.readSessionToken(request)` and validates via `validateAndLoad` (missing/unknown/expired → 401; expired rows are cleaned up). The tenant context is built from the **session-bound user/org** — a session can never act outside the organization it was issued for.
- `/v1/auth/me` and `/v1/auth/logout` also read only the cookie. Logout deletes the server row and clears the cookie (Max-Age=0, empty value).
- No `UserSessionEntity` schema change: revocation = row deletion on logout; expiry = `validateAndLoad` cleanup.

## 3. Cookie configuration

| Attribute | Value | Notes |
|---|---|---|
| Name | `neth_sid` | opaque UUID from `user_sessions.session_token`; non-revealing |
| HttpOnly | `true` | invisible to `document.cookie` (verified in E2E) |
| SameSite | `Lax` (default, configurable) | strictest setting that preserves the IdP→callback top-level redirect |
| Path | `/` | whole console |
| Max-Age | 86400 s (24 h, matches session TTL) | configurable |
| Domain | none | host-only for the origin host |
| Secure | auto / configurable | derived from `request.isSecure()` (scheme), unless overridden by `SESSION_COOKIE_SECURE` — keeps local HTTP dev working without weakening prod HTTPS |

Configuration block (`netherite.auth.session`, all env-overridable):

```yaml
netherite:
  auth:
    session:
      cookie-name: ${SESSION_COOKIE_NAME:neth_sid}
      cookie-secure: ${SESSION_COOKIE_SECURE:}        # blank = auto
      cookie-same-site: ${SESSION_COOKIE_SAME_SITE:Lax}
      ttl-seconds: ${SESSION_TTL_SECONDS:86400}
```

## 4. Credential-surface remediation

- **URL removal** — callback now redirects to a fixed `callbackBaseUrl + "/auth/callback"` with no query string at all. `AuthCallbackPage` completes the session by calling `/v1/auth/me` (cookie-backed). (Files: `OidcController.java`, `AuthCallbackPage.tsx`.)
- **Logging removal** — the callback success log contains only `userId`/`orgId`; the token-exchange failure message contains only the HTTP status, never the IdP response body. (Files: `OidcController.java`, `OidcService.exchangeCodeForTokens`.)
- **Body removal** — login response carries `userId`, `organizationId`, `role`, `expiresAt` only. (`AuthController.java`.)
- **Frontend** — no credential in JS storage; profile (non-credential) lives under `netherite_profile`. (`AuthContext.tsx`, `client.ts`, `AuthCallbackPage.tsx`.)
- **Audit records** — `DefaultDataRedactionService` continues to redact `sessionToken`/`session_token` (verified during Phase 1; unchanged).

## 5. Owner behavior

Unchanged UX: email + password on the Owner form. The owner row is still looked up/created from `netherite.auth.owner.*`, ACTIVE enforced, BCrypt verified, and the browser is now authenticated via the `neth_sid` cookie. Tests confirm the (pre-existing) first-login side effect: if the owner row has no password hash yet, a login auto-provisions it from the configured hash.

## 6. Changes by file

**Backend — main**
- `auth/session/SessionCookieService.java` (new) — cookie create/clear/read/validate+load; name constant `neth_sid`.
- `auth/controller/OidcController.java` — callback: cookie delivery, clean redirect, token-free logging; delegated token exchange to `OidcService.exchangeCodeForTokens`.
- `auth/service/OidcService.java` — added public `exchangeCodeForTokens(code, idpConfig, redirectUri, codeVerifier)` (never embeds IdP bodies in exceptions).
- `api/AuthController.java` — login: cookie + credential-free body; logout: delete + clear cookie; `/me`: cookie-based, no credentials exposed.
- `security/filter/UserAuthenticationFilter.java` — cookie-based authentication; session-bound tenant context.
- `security/config/AuthProperties.java` — added `SessionConfig` (cookie name/secure/same-site/TTL).
- `src/main/resources/application.yml`, `application-test.yml` — `netherite.auth.session.*` block.

**Backend — tests**
- `security/HumanSessionSecurityTest.java` (new, 10 tests A–J) — cookie flags, clean redirect, no body/no-log leaks, lifecycle (valid/absent/invalid/expired), logout invalidation, owner good/bad, OIDC employee cookie session, cross-tenant isolation.
- `auth/session/SessionCookieServiceTest.java` (new, 6 tests) — Secure auto/override, attributes, clear-cookie.
- `security/OwnerAuthenticationTest.java` — rewritten to cookie-based `loginAsOwner()` helper + cookie matchers; asserts no `sessionToken` in bodies.
- `approval/ApprovalStateSafetyTest.java`, `approval/ApprovalEndToEndTest.java`, `phase6/Phase6IntegrationTest.java` — header → `.cookie(new Cookie("neth_sid", ...))`.

**Frontend**
- `src/auth/AuthContext.tsx` — cookie-only; profile key `netherite_profile`; bootstrap validation via `/v1/auth/me`.
- `src/pages/AuthCallbackPage.tsx` — SSO completion via `/v1/auth/me`, no URL token parsing.
- `src/api/client.ts` — removed user-token header injection; 401 clears profile.
- `e2e/ws2-human-session.e2e.mjs` (new) — browser E2E harness (Edge channel + `playwright-core`).

## 7. Tests (Phase 9, A–J)

| Test | Result |
|---|---|
| A. Callback redirects to `/auth/callback`, no query, no token in Location | PASS |
| B. Cookie HttpOnly + SameSite=Lax + Path=/ + Max-Age=86400; Secure auto-off on HTTP | PASS |
| C. Login + `/me` bodies carry no credential | PASS |
| D. No session credential / `sessionToken=` / `bearer` in captured logs (login + callback + logout) | PASS |
| E. Issued credentials exist only in Cookie headers, never JSON | PASS |
| F. Valid cookie 200; absent/invalid/expired 401 | PASS |
| G. Logout deletes row + clears cookie; old cookie 401 | PASS |
| H. Owner good→cookie, wrong password 401, no token/password in logs or body | PASS |
| I. OIDC employee cookie session authenticates; no leak in redirect/log | PASS |
| J. Cross-tenant isolation (org A session cannot see org B data) | PASS |

Unit tests (SessionCookieService): Secure auto on HTTPS / off on HTTP, override wins both ways, custom name/TTL, clear-cookie semantics — **6/6 PASS**.

## 8. Verification results

- **Backend full suite**: `mvn -pl netherite-app -am test` → **327 tests, 0 failures, 0 errors, 0 skipped — BUILD SUCCESS**.
- **Frontend**: `npx tsc --noEmit` clean; `npm run build` (tsc + vite) succeeds (1593 modules, dist produced).
- **Browser E2E** (`e2e/ws2-human-session.e2e.mjs`, Edge):
  - `document.cookie` empty of `neth_sid` (HttpOnly enforced) — PASS
  - localStorage contains only the token-free `netherite_profile`; no credential key/value; no sessionStorage credential — PASS
  - Login JSON body: no `sessionToken`, no `neth_sid` — PASS
  - Cookie jar: `neth_sid` exists with `httpOnly=true`, `sameSite=Lax`, `path=/`, domain `localhost`, no `sessionToken` cookie — PASS
  - `/v1/auth/me` exposes no credential — PASS
  - After logout, `/v1/auth/me` → 401 — PASS
  - SSO entry redirects to the live IdP (`http://localhost:8180/realms/netherite/...`) with PKCE, no token leak — PASS
  - **Result: ALL CHECKS PASSED** (run pre- and post-regression).
- **Repo secret scan**: zero remaining `X-Netherite-User-Token`, `netherite_auth`, or `?sessionToken=` surfaces in `backend/*/src`, `frontend/src`, `infrastructure`, `e2e`. Remaining `Authorization: Bearer`/`X-Netherite-Agent-Key` usage is the agent (M2M) auth path, out of human-session scope.

## 9. Out-of-scope observations (unchanged, pre-existing)

- `HttpRestResourceConnector` propagates a configured `Authorization` header from resolved credentials to target resources — the agent-to-resource M2M design, not a human-session surface.
- `frontend/src/api/client.ts` reads `netherite_api_key` from localStorage for **agent** API-key UI (agent auth documented as displayed-once). Separate from human sessions.
- `ApprovalController` logs `e.getMessage()` on continuation failures; async "Agent not found in organization" log noise in approval tests is pre-existing and non-failing.
- The full credentialed OIDC browser round-trip (Keycloak login → callback) was exercised to the IdP boundary; granting a known realm user requires Keycloak realm admin credentials, which are not available in this environment. The callback path itself is covered end-to-end by the mock-backed Phase 9 tests.

---

WORKSTREAM 2 COMPLETE AND VERIFIED