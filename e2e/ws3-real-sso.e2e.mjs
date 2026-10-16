/**
 * WORKSTREAM 3 — Real Single Sign-On — browser E2E (default profile).
 *
 * Proves the DEFAULT Spring profile is the real enterprise runtime: it drives
 * the full OIDC authorization-code + PKCE flow against a REAL Keycloak realm
 * (netherite :8180) through the REAL control-plane IdP configuration, and
 * verifies that the employee (employee@netherite.local, Keycloak user with the
 * netherite-admin realm role) is authenticated and handed its session only as
 * an HttpOnly cookie, with no credential ever surfacing in URLs, storage, or
 * bodies. WS6: the IdP admin claim gates SSO login only — it grants no
 * application authority and never auto-designates an authorized person.
 *
 * Preconditions (default profile, no test profile):
 *   - backend on :8080  (default profile, OIDC enabled, PG on :5433)
 *   - frontend dev server on :3000 (Vite, proxying /v1 to :8080)
 *   - Keycloak on :8180 with realm 'netherite' (client 'netherite-agent'),
 *     user employee@netherite.local (local Keycloak dev-realm fixture password
 *     defined as SSO_PASSWORD below; harness-only, never production),
 *     role netherite-admin
 *   - org 'netherite' + verified domain 'netherite.local' + ACTIVE IdP config
 *
 * Run:  node e2e/ws3-real-sso.e2e.mjs
 */

import { chromium } from 'playwright-core';

const FRONTEND = 'http://localhost:3000';
const IDP_BASE = 'http://localhost:8180/realms/netherite';
const SSO_EMAIL = 'employee@netherite.local';
// DISPOSABLE TEST-ONLY credential for the local Keycloak dev-realm fixture user;
// harness-only, never a production value.
const SSO_PASSWORD = 'Workforce@2026';
const SESSION_COOKIE = 'neth_sid';

let failures = 0;

function check(name, condition, detail = '') {
  const status = condition ? 'PASS' : 'FAIL';
  if (!condition) failures += 1;
  console.log(`[${status}] ${name}${detail ? ` — ${detail}` : ''}`);
}

const browser = await chromium.launch({ channel: 'msedge', headless: true });
const context = await browser.newContext();
const page = await context.newPage();

// 1. Start SSO from the Login page for the work email.
await page.goto(`${FRONTEND}/login`, { waitUntil: 'domcontentloaded' });
await page.locator('#work-email').fill(SSO_EMAIL);
await page.getByRole('button', { name: 'Continue with SSO' }).click();

// 2. The app must redirect to the REAL Keycloak authorization endpoint without
//    leaking any session credential in the URL.
await page.waitForURL(new RegExp(`localhost:8180/realm`), { timeout: 15000 });
let idpUrl = page.url();
check('redirected to the real Keycloak realm authorization URL',
  idpUrl.includes(`${IDP_BASE}/protocol/openid-connect/auth`), idpUrl.split('?')[0]);
check('authorization URL leaks no session credential',
  !idpUrl.includes('neth_sid') && !idpUrl.includes('sessionToken')
    && !/([?&](code|access_token|id_token)=)/.test(idpUrl), '');

// 3. Log in at Keycloak as the real employee.
await page.locator('#username').fill(SSO_EMAIL);
await page.locator('#password').fill(SSO_PASSWORD);
await page.getByRole('button', { name: /Sign ?In/i }).click();

// 4. Keycloak redirects back to the backend callback, which sets the HttpOnly
//    cookie and redirects to the frontend callback page, which completes the
//    session and lands on the dashboard.
await page.waitForURL((url) => url.origin === FRONTEND, { timeout: 20000 });
await page.waitForURL((url) => new URL(url).pathname === '/', { timeout: 20000 });
await page.waitForLoadState('networkidle');

// 5. Cookie delivered exclusively as HttpOnly + SameSite=Lax over localhost.
const cookies = await context.cookies(FRONTEND);
const sessionCookie = cookies.find((c) => c.name === SESSION_COOKIE);
check('neth_sid cookie exists for localhost after real SSO', !!sessionCookie,
  `cookies=${JSON.stringify(cookies.map((c) => c.name))}`);
check('session cookie is HttpOnly (invisible to JS)', !!sessionCookie && sessionCookie.httpOnly,
  sessionCookie ? `httpOnly=${sessionCookie.httpOnly}` : 'no cookie');
check('session cookie is SameSite=Lax', !!sessionCookie && sessionCookie.sameSite === 'Lax',
  sessionCookie ? `sameSite=${sessionCookie.sameSite}` : 'no cookie');
check('session cookie has Path=/', !!sessionCookie && sessionCookie.path === '/',
  sessionCookie ? `path=${sessionCookie.path}` : 'no cookie');
check('session cookie domain is localhost', !!sessionCookie && sessionCookie.domain === 'localhost',
  sessionCookie ? `domain=${sessionCookie.domain}` : 'no cookie');

// 6. JS cannot read the credential, and no credential is persisted client-side.
const jsVisible = await page.evaluate(() => document.cookie);
check('document.cookie does not expose neth_sid or sessionToken',
  !jsVisible.includes(SESSION_COOKIE) && !jsVisible.includes('sessionToken'),
  `js-visible cookies: "${jsVisible}"`);
const storage = await page.evaluate(() => {
  const ls = {};
  for (let i = 0; i < localStorage.length; i++) {
    const k = localStorage.key(i);
    ls[k] = localStorage.getItem(k);
  }
  return { localStorage: ls, sessionStorage: Object.keys(sessionStorage) };
});
const storageKeys = Object.keys(storage.localStorage);
const forbiddenKeys = storageKeys.filter((k) => k !== 'netherite_profile' && k !== '');
const storedValues = Object.entries(storage.localStorage).flatMap(([k, v]) => [k, v]).join(' ');
check('localStorage holds only the token-free identity profile',
  !forbiddenKeys.length && !/sessionToken|neth_sid|bearer |\"password\"/i.test(storedValues),
  `keys=${JSON.stringify(storageKeys)}`);
check('nothing stored in sessionStorage', storage.sessionStorage.length === 0,
  `sessionStorage=${JSON.stringify(storage.sessionStorage)}`);

// 7. The authenticated profile is exactly the SSO employee. WS6 removed
//    role-based authorization: there is no `role` field at all, and the IdP
//    admin claim that admitted this user grants no application authority.
const me = await page.evaluate(() =>
  fetch('/v1/auth/me', { credentials: 'same-origin' }).then((r) => r.json()));
check('authenticated user is the SSO employee', me.email === SSO_EMAIL,
  JSON.stringify(me));
check('no role field on the profile (WS6 role-free model)',
  !('role' in me), `me=${JSON.stringify(me)}`);
check('/v1/auth/me exposes no session credential',
  !JSON.stringify(me).includes('sessionToken'), '');

await browser.close();

console.log(failures === 0 ? '\nE2E RESULT: ALL CHECKS PASSED' : `\nE2E RESULT: ${failures} CHECK(S) FAILED`);
process.exit(failures === 0 ? 0 : 1);