import { test, before, beforeEach, after } from 'node:test';
import assert from 'node:assert/strict';
import { createApp } from '../src/app.js';
import { connectDatabase } from '../src/db.js';
import { migrate } from '../src/migrate.js';
import { AuthAdmission } from '../src/auth-admission.js';
import { adminAuthConfig } from '../src/admin-auth.js';

type Admin = NonNullable<Parameters<typeof createApp>[0]['admin']>;
const url = process.env.TEST_DATABASE_URL;
if (url && !new URL(url).pathname.endsWith('_test')) throw new Error('Use a dedicated database ending in _test.');
const db = url ? connectDatabase(url) : null;
before(async () => { if (db) await migrate(db); });
beforeEach(async () => {
  if (db) {
    await db.query('TRUNCATE admin_actions, admin_sessions, admins CASCADE');
    await db.query("DELETE FROM auth_rate_limits WHERE operation IN ('admin','admin-api')");
  }
});
after(async () => { if (db) await db.end(); });
const integration = (name: string, fn: () => Promise<void>) => test(name, { skip: !db && 'Set TEST_DATABASE_URL for PostgreSQL tests.' }, fn);

const hmacKey = 'c'.repeat(64), proxyToken = 'd'.repeat(64);
const adminHeaders = (origin?: string) => ({
  ...(origin ? { origin } : {}),
  'content-type': 'application/json',
  'x-app-client-ip': '203.0.113.20', 'x-app-proxy-token': proxyToken
});
const app = () => createApp({ db: db!, auth: {}, admin: {
  services: { db: db!, config: adminAuthConfig({
    ADMIN_HMAC_KEY: hmacKey, ADMIN_PROXY_TOKEN: proxyToken, ADMIN_PUBLIC_ORIGIN: 'https://admin.example' })! },
  admission: new AuthAdmission(db!, { hmacKey, proxyToken, allowLocalLoopback: false }) } });

// The four login-surface routes: reachable without a session by design, still
// CSRF-anchored (foreign Origin must never complete).
const ADMIN_PUBLIC_ROUTES = new Set(['/v1/admin/auth/challenge', '/v1/admin/auth/login',
  '/v1/admin/auth/session', '/v1/admin/auth/sign-out']);
const substituteParams = (route: string) =>
  route.replace(/:([A-Za-z]+)/g, () => '00000000-0000-4000-8000-000000000000');
const enumerateRoutes = (service: ReturnType<typeof app>): { method: 'GET' | 'POST' | 'PUT' | 'DELETE'; url: string }[] =>
  service.printRoutes({ commonPrefix: false })
    .split('\n')
    .map(line => /^(?:[├└]──\s)(.+?)\s\((.+)\)$/.exec(line.trim()))
    .filter((match): match is RegExpExecArray => match !== null)
    .flatMap(match => match[2]!.split(', ').map(method => ({ method, url: match[1]! })))
    .filter((route): route is { method: 'GET' | 'POST' | 'PUT' | 'DELETE'; url: string } =>
      ['GET', 'POST', 'PUT', 'DELETE'].includes(route.method));

integration('every registered admin route rejects unauthenticated requests', async () => {
  const service = app();
  // Canary registered after createApp the same way a naive route would be: the
  // guard must protect by path, not by per-handler calls, so even this route
  // has to fail closed.
  service.get('/v1/admin/unprotected-canary', async () => ({ ok: true }));
  await service.ready();
  try {
    const routes = enumerateRoutes(service).filter(r => r.url.startsWith('/v1/admin/'));
    assert.ok(routes.length >= 20, `expected the full admin surface, found only ${routes.length}`);
    const found = new Set(routes.map(r => r.url));
    for (const expected of ADMIN_PUBLIC_ROUTES)
      assert.ok(found.has(expected), `public auth route missing from enumeration: ${expected}`);
    assert.ok(found.has('/v1/admin/unprotected-canary'), 'canary missing from enumeration');
    for (const route of routes) {
      const response = await service.inject({ method: route.method, url: substituteParams(route.url),
        headers: ADMIN_PUBLIC_ROUTES.has(route.url) ? adminHeaders('https://evil.example') : adminHeaders(),
        payload: {} });
      if (ADMIN_PUBLIC_ROUTES.has(route.url)) {
        assert.equal(response.statusCode, 403, `${route.method} ${route.url} accepted a foreign origin without credentials`);
      } else {
        assert.equal(response.statusCode, 401, `${route.method} ${route.url} did not require an admin session`);
      }
    }
  } finally { await service.close(); }
});

integration('admin routes reject disabled administrators through the shared guard', async () => {
  const { addAdmin, disableAdmin, setAdminPassword } = await import('../src/admins-admin.js');
  const admin = await addAdmin(db!, 'local', 'guard-disabled-admin');
  await setAdminPassword(db!, admin.id, 'correct horse battery staple');
  const service = app();
  await service.ready();
  try {
    const challenge = await (await service.inject({ method: 'POST', url: '/v1/admin/auth/challenge',
      headers: adminHeaders(), payload: {} })).json();
    const login = await service.inject({ method: 'POST', url: '/v1/admin/auth/login',
      headers: adminHeaders('https://admin.example'),
      payload: { username: 'guard-disabled-admin', password: 'correct horse battery staple', challengeID: challenge.challengeID } });
    assert.equal(login.statusCode, 200);
    const token = String(login.headers['set-cookie']).split(';')[0]!.slice('admin_session='.length);
    assert.equal((await service.inject({ method: 'GET', url: '/v1/admin/me', headers: adminHeaders() })).statusCode, 401);
    const authed = await service.inject({ method: 'GET', url: '/v1/admin/me',
      headers: { ...adminHeaders(), cookie: `admin_session=${token}` } });
    assert.equal(authed.statusCode, 200);
    await disableAdmin(db!, admin.id);
    assert.equal((await service.inject({ method: 'GET', url: '/v1/admin/me',
      headers: { ...adminHeaders(), cookie: `admin_session=${token}` } })).statusCode, 401);
  } finally { await service.close(); }
});
