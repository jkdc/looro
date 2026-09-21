import { test, before, beforeEach, after } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { generateKeyPair, exportJWK, createLocalJWKSet, SignJWT } from 'jose';
import { createApp } from '../src/app.js';
import { connectDatabase } from '../src/db.js';
import { migrate } from '../src/migrate.js';
import { AuthAdmission } from '../src/auth-admission.js';
import { addAdmin, disableAdmin } from '../src/admins-admin.js';
import { adminAuthConfig, adminCookieHeader, assertAdminOrigin, type AdminAuthServices } from '../src/admin-auth.js';
import { digest, verifyIdentity } from '../src/auth.js';

const { privateKey, publicKey } = await generateKeyPair('RS256');
const jwk = await exportJWK(publicKey); jwk.kid = 'test-key';
const keys = createLocalJWKSet({ keys: [jwk] });
const nonce = 'admin-panel-test-nonce';
const adminToken = async (subject = 'admin-subject-1', nonceValue = nonce) =>
  new SignJWT({ nonce: nonceValue, email: 'root@example.test', email_verified: true })
    .setProtectedHeader({ alg: 'RS256', kid: 'test-key' }).setSubject(subject)
    .setAudience('admin-test-client').setIssuer('https://accounts.google.com')
    .setIssuedAt().setExpirationTime('5m').sign(privateKey);
const verify = ((provider: Parameters<typeof verifyIdentity>[0], token: string, nonceHash: string,
  _config: unknown, getKey?: Parameters<typeof verifyIdentity>[4]) =>
  verifyIdentity(provider, token, nonceHash, { googleClientID: 'admin-test-client' }, getKey ?? keys)) as
  typeof verifyIdentity;

type Services = NonNullable<Parameters<typeof createApp>[0]['admin']>;
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

const hmacKey = 'a'.repeat(64), proxyToken = 'b'.repeat(64);
const env = { GOOGLE_ADMIN_CLIENT_ID: 'admin-test-client', ADMIN_HMAC_KEY: hmacKey, ADMIN_PROXY_TOKEN: proxyToken,
  ADMIN_PUBLIC_ORIGIN: 'https://admin.example' };
const adminServices = (override: Partial<AdminAuthServices['config']> = {}): Services => {
  const config = { ...adminAuthConfig(env)!, ...override };
  const services: AdminAuthServices = { db: db!, config, identityVerifier: verify };
  return { services, admission: new AuthAdmission(db!, { hmacKey, proxyToken, allowLocalLoopback: false }) };
};
const adminHeaders = (token?: string, origin?: string) => ({
  ...(token ? { cookie: adminCookieHeader(token) } : {}),
  ...(origin ? { origin } : {}),
  'content-type': 'application/json',
  'x-app-client-ip': '203.0.113.9', 'x-app-proxy-token': proxyToken
});
const setCookieValue = (headers: Record<string, unknown>): string => {
  const raw = headers['set-cookie'];
  return Array.isArray(raw) ? String(raw[0]) : String(raw);
};
const app = (override: Partial<AdminAuthServices['config']> = {}) => createApp({ db: db!, auth: {}, admin: adminServices(override) });
const mintSession = async (subject: string): Promise<string> => {
  const admin = await addAdmin(db!, 'google', subject);
  const token = randomBytesToken();
  await db!.query(`INSERT INTO admin_sessions(id,admin_id,token_hash,expires_at) VALUES($1,$2,$3,now()+interval '1 hour')`,
    [randomUUID(), admin.id, digest(token)]);
  return token;
};
const randomBytesToken = () => {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ';
  return Array.from({ length: 43 }, (_, i) => alphabet[i % alphabet.length]!).join('');
};

test('admin auth configuration validates secrets and origin', () => {
  assert.equal(adminAuthConfig({}), undefined);
  assert.throws(() => adminAuthConfig({ GOOGLE_ADMIN_CLIENT_ID: 'x' }));
  assert.throws(() => adminAuthConfig({ GOOGLE_ADMIN_CLIENT_ID: 'x', ADMIN_HMAC_KEY: hmacKey, ADMIN_PROXY_TOKEN: hmacKey, ADMIN_PUBLIC_ORIGIN: 'https://admin.example' }));
  assert.throws(() => adminAuthConfig({ ...env, ADMIN_PUBLIC_ORIGIN: 'https://evil.test/path' }));
  assert.throws(() => adminAuthConfig({ ...env, ADMIN_PUBLIC_ORIGIN: 'http://localhost:8081' }));
  assert.ok(adminAuthConfig({ ...env, ADMIN_ALLOW_LOCAL_LOOPBACK: 'true', ADMIN_PUBLIC_ORIGIN: 'http://localhost:8081' }));
});

test('mutating admin routes reject foreign origins and accept exact or absent ones', () => {
  assertAdminOrigin({ origin: 'https://admin.example' }, 'https://admin.example', 'POST');
  assertAdminOrigin({}, 'https://admin.example', 'POST');
  assertAdminOrigin({ origin: 'https://other.example' }, 'https://admin.example', 'GET');
  assert.throws(() => assertAdminOrigin({ origin: 'https://other.example' }, 'https://admin.example', 'POST'), /admin_origin_required/);
  assert.throws(() => assertAdminOrigin({ origin: 'https://evil.example' }, 'https://admin.example', 'DELETE'), /admin_origin_required/);
});

test('disabled or missing admin surface fails closed', async () => {
  const service = createApp({ db: db!, auth: {} });
  try {
    assert.equal((await service.inject({ method: 'GET', url: '/v1/admin/me', headers: adminHeaders() })).statusCode, 503);
    assert.equal((await service.inject({ method: 'POST', url: '/v1/admin/auth/challenge', headers: adminHeaders(), payload: {} })).statusCode, 503);
  } finally { await service.close(); }
});

integration('exchange registers a session cookie only for registered administrators', async () => {
  const service = app();
  try {
    const challenge = await (await service.inject({ method: 'POST', url: '/v1/admin/auth/challenge',
      headers: adminHeaders(), payload: {} })).json();
    // Unknown identity: the same generic 403 whether or not any admin exists.
    const stranger = await service.inject({ method: 'POST', url: '/v1/admin/auth/exchange', headers: adminHeaders(undefined, 'https://admin.example'),
      payload: { provider: 'google', idToken: await adminToken('stranger-subject', challenge.nonce), challengeID: challenge.challengeID } });
    assert.equal(stranger.statusCode, 403);
    assert.equal(stranger.json().error.code, 'not_admin');
    const admin = await addAdmin(db!, 'google', 'admin-subject-1', 'root@example.test');
    const challenge2 = await (await service.inject({ method: 'POST', url: '/v1/admin/auth/challenge',
      headers: adminHeaders(), payload: {} })).json();
    const ok = await service.inject({ method: 'POST', url: '/v1/admin/auth/exchange', headers: adminHeaders(undefined, 'https://admin.example'),
      payload: { provider: 'google', idToken: await adminToken('admin-subject-1', challenge2.nonce), challengeID: challenge2.challengeID } });
    assert.equal(ok.statusCode, 200);
    const raw = ok.headers['set-cookie'] as unknown;
    const cookie = Array.isArray(raw) ? String(raw[0]) : String(raw);
    assert.match(cookie, /^admin_session=/);
    assert.match(cookie, /HttpOnly/);
    assert.match(cookie, /Secure/);
    assert.match(cookie, /SameSite=Strict/);
    assert.match(cookie, /Max-Age=28800/);
    const me = await service.inject({ method: 'GET', url: '/v1/admin/me',
      headers: adminHeaders(cookie.split(';')[0]!.slice('admin_session='.length)) });
    assert.equal(me.statusCode, 200);
    assert.equal(me.json().adminID, admin.id);
    assert.equal(me.json().email, 'root@example.test');
  } finally { await service.close(); }
});

integration('challenges are single-use and foreign-audience tokens never reach the admin table', async () => {
  const service = app();
  try {
    await addAdmin(db!, 'google', 'admin-subject-1');
    // A token for the app-user audience is valid for the app, not for the panel.
    const foreign = await new SignJWT({ nonce, email_verified: true })
      .setProtectedHeader({ alg: 'RS256', kid: 'test-key' }).setSubject('admin-subject-1')
      .setAudience('app-test-client').setIssuer('https://accounts.google.com')
      .setIssuedAt().setExpirationTime('5m').sign(privateKey);
    const challenge = await (await service.inject({ method: 'POST', url: '/v1/admin/auth/challenge', headers: adminHeaders(), payload: {} })).json();
    const rejected = await service.inject({ method: 'POST', url: '/v1/admin/auth/exchange', headers: adminHeaders(undefined, 'https://admin.example'),
      payload: { provider: 'google', idToken: foreign, challengeID: challenge.challengeID } });
    assert.equal(rejected.statusCode, 401);
    assert.equal(rejected.json().error.code, 'invalid_identity_token');
    // A successful exchange burns its challenge; replaying it fails even with a fresh valid token.
    const ok = await service.inject({ method: 'POST', url: '/v1/admin/auth/exchange', headers: adminHeaders(undefined, 'https://admin.example'),
      payload: { provider: 'google', idToken: await adminToken('admin-subject-1', challenge.nonce), challengeID: challenge.challengeID } });
    assert.equal(ok.statusCode, 200);
    const replay = await service.inject({ method: 'POST', url: '/v1/admin/auth/exchange', headers: adminHeaders(undefined, 'https://admin.example'),
      payload: { provider: 'google', idToken: await adminToken('admin-subject-1', challenge.nonce), challengeID: challenge.challengeID } });
    assert.equal(replay.statusCode, 401);
    assert.equal(replay.json().error.code, 'invalid_challenge');
  } finally { await service.close(); }
});

integration('disabled administrators are refused and lose live sessions', async () => {
  const admin = await addAdmin(db!, 'google', 'admin-subject-2');
  const service = app();
  try {
    const challenge = await (await service.inject({ method: 'POST', url: '/v1/admin/auth/challenge', headers: adminHeaders(), payload: {} })).json();
    const ok = await service.inject({ method: 'POST', url: '/v1/admin/auth/exchange', headers: adminHeaders(undefined, 'https://admin.example'),
      payload: { provider: 'google', idToken: await adminToken('admin-subject-2', challenge.nonce), challengeID: challenge.challengeID } });
    assert.equal(ok.statusCode, 200);
    const raw = ok.headers['set-cookie'] as unknown;
    const cookie = Array.isArray(raw) ? String(raw[0]) : String(raw);
    const token = cookie.split(';')[0]!.slice('admin_session='.length);
    await disableAdmin(db!, admin.id);
    assert.equal((await service.inject({ method: 'GET', url: '/v1/admin/me', headers: adminHeaders(token) })).statusCode, 401);
    const challenge3 = await (await service.inject({ method: 'POST', url: '/v1/admin/auth/challenge', headers: adminHeaders(), payload: {} })).json();
    const again = await service.inject({ method: 'POST', url: '/v1/admin/auth/exchange', headers: adminHeaders(undefined, 'https://admin.example'),
      payload: { provider: 'google', idToken: await adminToken('admin-subject-2', challenge3.nonce), challengeID: challenge3.challengeID } });
    assert.equal(again.statusCode, 403);
    assert.equal(again.json().error.code, 'admin_disabled');
  } finally { await service.close(); }
});

integration('only three live sessions survive and sign-out revokes', async () => {
  const service = app();
  try {
    await addAdmin(db!, 'google', 'admin-subject-1');
    const mint = async (): Promise<string> => {
      const challenge = await (await service.inject({ method: 'POST', url: '/v1/admin/auth/challenge', headers: adminHeaders(), payload: {} })).json();
      const ok = await service.inject({ method: 'POST', url: '/v1/admin/auth/exchange', headers: adminHeaders(undefined, 'https://admin.example'),
        payload: { provider: 'google', idToken: await adminToken('admin-subject-1', challenge.nonce), challengeID: challenge.challengeID } });
      const raw = ok.headers['set-cookie'] as unknown;
      return (Array.isArray(raw) ? String(raw[0]) : String(raw)).split(';')[0]!.slice('admin_session='.length);
    };
    const [first, , third, fourth] = [await mint(), await mint(), await mint(), await mint()];
    const rows = await db!.query('SELECT count(*) AS count FROM admin_sessions WHERE revoked_at IS NULL');
    assert.equal(Number(rows.rows[0]!.count), 3);
    // The oldest session was pruned by the cap.
    assert.equal((await service.inject({ method: 'GET', url: '/v1/admin/me', headers: adminHeaders(first!) })).statusCode, 401);
    assert.equal((await service.inject({ method: 'GET', url: '/v1/admin/me', headers: adminHeaders(fourth!) })).statusCode, 200);
    const signOut = await service.inject({ method: 'POST', url: '/v1/admin/auth/sign-out', headers: adminHeaders(third, 'https://admin.example'), payload: {} });
    assert.equal(signOut.statusCode, 200);
    assert.match(setCookieValue(signOut.headers), /Max-Age=0/);
    assert.equal((await service.inject({ method: 'GET', url: '/v1/admin/me', headers: adminHeaders(third) })).statusCode, 401);
    assert.equal((await service.inject({ method: 'GET', url: '/v1/admin/me', headers: adminHeaders(fourth!) })).statusCode, 200);
  } finally { await service.close(); }
});

integration('the session endpoint converts a bearer into the cookie form without new authority', async () => {
  const bearer = await mintSession('admin-subject-3');
  const service = app();
  try {
    const ok = await service.inject({ method: 'POST', url: '/v1/admin/auth/session', headers: adminHeaders(undefined, 'https://admin.example'),
      payload: { bearer } });
    assert.equal(ok.statusCode, 200);
    const raw = ok.headers['set-cookie'] as unknown;
    const cookie = (Array.isArray(raw) ? String(raw[0]) : String(raw));
    assert.match(cookie, /^admin_session=/);
    // The bearer itself is not an admin credential on GET routes; only the cookie is.
    assert.equal((await service.inject({ method: 'GET', url: '/v1/admin/me', headers: adminHeaders() })).statusCode, 401);
    assert.equal((await service.inject({ method: 'GET', url: '/v1/admin/me',
      headers: adminHeaders(cookie.split(';')[0]!.slice('admin_session='.length)) })).statusCode, 200);
    const bad = await service.inject({ method: 'POST', url: '/v1/admin/auth/session', headers: adminHeaders(undefined, 'https://admin.example'),
      payload: { bearer: 'Z'.repeat(43) } });
    assert.equal(bad.statusCode, 401);
    assert.equal((await service.inject({ method: 'POST', url: '/v1/admin/auth/session', headers: adminHeaders(undefined, 'https://evil.example'),
      payload: { bearer } })).statusCode, 403);
  } finally { await service.close(); }
});

integration('admin and app tokens never cross the boundary', async () => {
  const service = app();
  try {
    const token = await mintSession('admin-subject-4');
    // Admin session presented as an app bearer on a real app route: refused.
    const app1 = await service.inject({ method: 'GET', url: '/v1/minutes', headers: { authorization: `Bearer ${token}`,
      'x-app-client-ip': '203.0.113.9', 'x-app-proxy-token': proxyToken } });
    assert.equal(app1.statusCode, 401);
    assert.equal(app1.json().error.code, 'sign_in_required');
    // Admin session presented as a bearer header on an admin route: refused; only the cookie authenticates.
    const admin1 = await service.inject({ method: 'GET', url: '/v1/admin/me', headers: { authorization: `Bearer ${token}`,
      'x-app-client-ip': '203.0.113.9', 'x-app-proxy-token': proxyToken } });
    assert.equal(admin1.statusCode, 401);
    assert.equal(admin1.json().error.code, 'admin_sign_in_required');
  } finally { await service.close(); }
});

integration('admin admission limits are strict and return Retry-After', async () => {
  const service = app();
  try {
    let status = 0, retryAfter = '';
    for (let i = 0; i < 31; i++) {
      const response = await service.inject({ method: 'POST', url: '/v1/admin/auth/challenge', headers: adminHeaders(), payload: {} });
      status = response.statusCode; retryAfter = String(response.headers['retry-after'] ?? '');
    }
    assert.equal(status, 429);
    assert.equal(retryAfter, '3600');
  } finally { await service.close(); }
});
