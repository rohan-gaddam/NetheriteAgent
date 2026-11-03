/**
 * WORKSTREAM 2 — Human Session Security — browser E2E.
 *
 * Verifies, in a real browser, that human session credentials never surface in
 * URLs, query parameters, fragments, HTML, JS-accessible storage, or client-
 * visible API responses, and that the session is delivered only as an HttpOnly
 * cookie (same site = Lax) and invalidated on logout.
 *
 * Preconditions:
 *   - backend on :8080 running with the 'test' profile
 *       (owner: strangeunknown03@gmail.com / Fakepassword@26)
 *   - frontend dev server on :3000 (Vite, proxying /v1 to :8080)
 *   - Microsoft Edge installed (script launches the msedge channel)
 *
 * Run:  node e2e/ws2-human-session.e2e.mjs
 */

import { chromium } from 'playwright-core';

const FRONTEND = 'http://localhost:3000';
const OWNER_EMAIL = 'strangeunknown03@gmail.com';
const OWNER_PASSWORD = 'Fakepassword@26';
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

// Capture the login response body at the network level (header sniffing is
// unreliable through Vite's proxy; the browser cookie jar is authoritative).
let loginResponse = null;
page.on('response', (res) => {
  if (res.url().includes('/v1/auth/login')) {
    res.text().then((t) => { loginResponse = t; }).catch(() => {});
  }
});

// 1. Owner login through the real UI.
await page.goto(`${FRONTEND}/login`, { waitUntil: 'domcontentloaded' });
await page.getByText('Owner Login', { exact: true }).click();
await page.locator('#owner-email').fill(OWNER_EMAIL);
await page.locator('#owner-password').fill(OWNER_PASSWORD);
await page.getByRole('button', { name: 'Sign in' }).click();

// 2. Wait until we are authenticated and on the dashboard.
await page.waitForURL(`${FRONTEND}/`, { timeout: 15000 });
await page.waitForLoadState('networkidle');

// 3. HttpOnly cookie: document.cookie must NOT contain the session credential.
const jsVisibleCookies = await page.evaluate(() => document.cookie);
check('document.cookie does not contain the session cookie (HttpOnly enforced)',
  !jsVisibleCookies.includes(SESSION_COOKIE) && !jsVisibleCookies.includes('sessionToken'),
  `js-visible cookies: "${jsVisibleCookies}"`);

// 4. No credential in localStorage/sessionStorage; only a token-free profile.
const storage = await page.evaluate(() => {
  const ls = {};
  for (let i = 0; i < localStorage.length; i++) {
    const k = localStorage.key(i);
    ls[k] = localStorage.getItem(k);
  }
  return { localStorage: ls, sessionStorage: Object.keys(sessionStorage) };
});
const storageKeys = Object.keys(storage.localStorage);
// The app may persist ONLY a token-free identity profile (netherite_profile).
// Any other key, or any credential string inside any stored value, is a leak.
const forbiddenKeys = storageKeys.filter((k) => k !== 'netherite_profile' && k !== '');
const storedValues = Object.entries(storage.localStorage)
  .flatMap(([k, v]) => [k, v]).join(' ');
const hasCredentialMarkers = /sessionToken|neth_sid|bearer |\"password\"/i.test(storedValues);
check('localStorage holds only the token-free identity profile',
  !forbiddenKeys.length && !hasCredentialMarkers,
  `keys=${JSON.stringify(storageKeys)}`);
check('no credential stored in sessionStorage',
  storage.sessionStorage.length === 0, `sessionStorage=${JSON.stringify(storage.sessionStorage)}`);

// 5. Network-level: login response body carries no credential; cookie is HttpOnly + SameSite=Lax.
check('login JSON body does not contain sessionToken or the cookie name',
  !!loginResponse && !loginResponse.includes('sessionToken') && !loginResponse.includes('neth_sid'),
  `body=${loginResponse}`);

// 5b. Browser cookie jar: the session exists ONLY as an HttpOnly, SameSite=Lax,
//     Path=/ cookie (no Secure over this local HTTP origin), and no credential
//     cookie is visible to JavaScript.
const cookies = await context.cookies(FRONTEND);
const sessionCookie = cookies.find((c) => c.name === SESSION_COOKIE);
check('a session cookie named neth_sid exists for the app origin',
  !!sessionCookie, `cookies=${JSON.stringify(cookies.map((c) => c.name))}`);
check('session cookie is HttpOnly (invisible to JS)', !!sessionCookie && sessionCookie.httpOnly,
  sessionCookie ? `httpOnly=${sessionCookie.httpOnly}` : 'no cookie');
check('session cookie is SameSite=Lax', !!sessionCookie && sessionCookie.sameSite === 'Lax',
  sessionCookie ? `sameSite=${sessionCookie.sameSite}` : 'no cookie');
check('session cookie has Path=/', !!sessionCookie && sessionCookie.path === '/',
  sessionCookie ? `path=${sessionCookie.path}` : 'no cookie');
check('session cookie is scoped to localhost only',
  !!sessionCookie && sessionCookie.domain === 'localhost',
  sessionCookie ? `domain=${sessionCookie.domain}` : 'no cookie');
check('no credential cookie named sessionToken exists',
  !cookies.some((c) => c.name.includes('sessionToken')), '');

// 6. /v1/auth/me must not echo the credential, and the app is authenticated.
const meBody = await page.evaluate(() => fetch('/v1/auth/me', { credentials: 'same-origin' }).then((r) => r.json()));
check('/v1/auth/me exposes no credential', !JSON.stringify(meBody).includes('sessionToken'), JSON.stringify(meBody));

// 7. Logout: POST to /v1/auth/logout, verify the session is then rejected.
await page.evaluate(() => fetch('/v1/auth/logout', { method: 'POST', credentials: 'same-origin' }));
const meAfterLogout = await page.evaluate(() => fetch('/v1/auth/me', { credentials: 'same-origin' }).then((r) => r.status));
check('session is rejected after logout (401)', meAfterLogout === 401, `status=${meAfterLogout}`);

// 8. SSO entry: discovery + OIDC start must redirect to the IdP login page
//    (Keycloak realm netherite on :8180) — proving the OIDC path is reachable
//    and issues no credential before the IdP interaction.
await page.goto(`${FRONTEND}/login`, { waitUntil: 'domcontentloaded' });
await page.locator('#work-email').fill('employee@netherite.local');
await page.getByRole('button', { name: 'Continue with SSO' }).click();
let landed = '';
try {
  await page.waitForURL(/localhost:8180\/realms\/netherite/, { timeout: 15000 });
  landed = page.url();
} catch {
  landed = page.url();
}
check('SSO flow redirects to the configured IdP (Keycloak realm) and leaks no token',
  landed.includes('localhost:8180/realms/netherite')
    && !landed.includes('neth_sid')
    && !landed.includes('sessionToken'),
  `landed=${landed}`);

await browser.close();

console.log(failures === 0 ? '\nE2E RESULT: ALL CHECKS PASSED' : `\nE2E RESULT: ${failures} CHECK(S) FAILED`);
process.exit(failures === 0 ? 0 : 1);