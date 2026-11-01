import { test, before, beforeEach, after } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { createApp } from '../src/app.js';
import { connectDatabase } from '../src/db.js';
import { migrate } from '../src/migrate.js';
import { AuthAdmission } from '../src/auth-admission.js';
import { addAdmin } from '../src/admins-admin.js';
import { adminAuthConfig, adminCookieHeader, type AdminAuthServices } from '../src/admin-auth.js';
import { digest } from '../src/auth.js';

const hmacKey = 'a'.repeat(64), proxyToken = 'b'.repeat(64);
const url = process.env.TEST_DATABASE_URL;
if (url && !new URL(url).pathname.endsWith('_test')) throw new Error('Use a dedicated database ending in _test.');
const db = url ? connectDatabase(url) : null;
before(async () => { if (db) await migrate(db); });
beforeEach(async () => {
  if (db) {
    await db.query('TRUNCATE admin_actions, admin_sessions, admins, accounts, usage_events CASCADE');
    await db.query("DELETE FROM auth_rate_limits WHERE operation IN ('admin','admin-api')");
  }
});
after(async () => { if (db) await db.end(); });
const integration = (name: string, fn: () => Promise<void>) => test(name, { skip: !db && 'Set TEST_DATABASE_URL for PostgreSQL tests.' }, fn);

const adminServices: AdminAuthServices | null = db ? {
  db: db!, config: adminAuthConfig({ ADMIN_HMAC_KEY: hmacKey, ADMIN_PROXY_TOKEN: proxyToken,
    ADMIN_PUBLIC_ORIGIN: 'https://admin.example' })!
} : null;
const app = (environment: NodeJS.ProcessEnv = process.env) => createApp({ db: db!, auth: {}, environment, admin: { services: adminServices!,
  admission: new AuthAdmission(db!, { hmacKey, proxyToken, allowLocalLoopback: false }) } });
const session = async (): Promise<string> => {
  const admin = await addAdmin(db!, 'google', 'metrics-admin');
  const token = 'M'.repeat(43);
  await db!.query(`INSERT INTO admin_sessions(id,admin_id,token_hash,expires_at)
    VALUES($1,$2,$3,now()+interval '1 hour')`, [randomUUID(), admin.id, digest(token)]);
  return token;
};
const get = async (path: string, token?: string) => (await app()).inject({ method: 'GET', url: path, headers: {
  ...(token ? { cookie: adminCookieHeader(token) } : {}),
  'x-app-client-ip': '203.0.113.9', 'x-app-proxy-token': proxyToken } });

integration('dashboard metrics expose the snapshot, series and usage with bounded windows', async () => {
  const token = await session();
  const account = randomUUID();
  await db!.query('INSERT INTO accounts(id,email) VALUES($1,$2)', [account, 'metrics@example.test']);
  await db!.query(`INSERT INTO usage_events(id,client_event_id,account_id,name,platform,app_version,client_at)
    VALUES(gen_random_uuid(),$1,$2,'app_open','android','1.0',now())`, [randomUUID(), account]);

  const snapshot = await get('/v1/admin/metrics', token);
  assert.equal(snapshot.statusCode, 200);
  const body = snapshot.json();
  assert.equal(body.accounts.total, 1);
  assert.equal(typeof body.generatedAt, 'string');
  assert.ok(Array.isArray(body.orders));
  assert.equal(body.adminActions, 0);
  assert.equal(typeof body.subscriptions.active, 'number');

  const overview = await get('/v1/admin/metrics/timeseries?set=overview&days=7', token);
  assert.equal(overview.statusCode, 200);
  assert.equal(overview.json().set, 'overview');
  assert.equal(overview.json().days, 7);
  assert.ok(overview.json().series.length > 0);

  // Unknown days fall back to the service default; out-of-range values clamp.
  assert.equal((await get('/v1/admin/metrics/timeseries?set=overview&days=abc', token)).json().days, 30);
  assert.equal((await get('/v1/admin/metrics/timeseries?set=overview&days=9999', token)).json().days, 90);
  assert.equal((await get('/v1/admin/metrics/timeseries?set=overview&days=0', token)).json().days, 1);

  const unknownSet = await get('/v1/admin/metrics/timeseries?set=unknown', token);
  assert.equal(unknownSet.statusCode, 400);
  assert.equal(unknownSet.json().error?.code, 'invalid_request');

  const usage = await get('/v1/admin/metrics/usage?days=7', token);
  assert.equal(usage.statusCode, 200);
  assert.equal(usage.json().dau, 1);
  assert.equal(usage.json().events7d, 1);
  assert.deepEqual(usage.json().topFeatures, [{ name: 'app_open', count: 1 }]);
  assert.ok(Array.isArray(usage.json().retention));
  // days=1 still includes today's signup cohort (one row for the created account).
  assert.equal((await get('/v1/admin/metrics/usage?days=1', token)).json().retention.length, 1);
  const anonymous = await (await app()).inject({ url: '/v1/admin/metrics',
    headers: { 'x-app-client-ip': '203.0.113.9', 'x-app-proxy-token': proxyToken } });
  assert.equal(anonymous.statusCode, 401);
});

integration('account closeout detail explains the blockers and refuses unknown or malformed ids', async () => {
  const token = await session();
  const account = randomUUID();
  await db!.query('INSERT INTO accounts(id,email) VALUES($1,$2)', [account, 'closeout@example.test']);
  await db!.query("INSERT INTO minute_wallets(account_id) VALUES($1) ON CONFLICT DO NOTHING", [account]);

  const found = await get(`/v1/admin/accounts/${account}`, token);
  assert.equal(found.statusCode, 200);
  assert.equal(found.json().accountID, account);
  assert.equal(found.json().alreadyDeleted, false);
  assert.equal(found.json().guest, false);
  assert.equal(found.json().manualReviewOnly, true);
  assert.equal(found.json().readyForExistingDeletionChecks, true);
  assert.deepEqual(found.json().orders, []);
  assert.deepEqual(Object.keys(found.json().blockers).sort(), [
    'activeConversation', 'cashBalanceRemaining', 'cashDebtRemaining', 'cashReserved',
    'helperUsageUnresolved', 'legacyCheckoutUnresolved', 'minuteReservation', 'orderListTruncated',
    'purchaseUnresolved', 'purchasedMinutesRemaining'
  ]);

  const unknown = await get(`/v1/admin/accounts/${randomUUID()}`, token);
  assert.equal(unknown.statusCode, 404);
  assert.equal(unknown.json().error?.code, 'account_not_found');

  const malformed = await get('/v1/admin/accounts/not-a-uuid', token);
  assert.equal(malformed.statusCode, 400);
  assert.equal(malformed.json().error?.code, 'invalid_request');

  assert.equal((await get(`/v1/admin/accounts/${account}`)).statusCode, 401);
});

integration('llm configuration errors map to clean 4xx and 5xx responses', async () => {
  const token = await session();
  const headers = { cookie: adminCookieHeader(token),
    'x-app-client-ip': '203.0.113.9', 'x-app-proxy-token': proxyToken };
  const environment = { ...process.env };
  delete environment.LEVELTEST_LLM_MASTER_KEY;
  const service = app(environment);
  try {
    // A body with no provider or settings change is rejected before any write.
    const invalid = await service.inject({ method: 'PUT', url: '/v1/admin/llm-config', headers,
      payload: {} });
    assert.equal(invalid.statusCode, 400);
    assert.equal(invalid.json().error?.code, 'invalid_llm_config');

    // Storing a key without the master key is a configuration failure, not a 500.
    const missingKey = await service.inject({ method: 'PUT', url: '/v1/admin/llm-config', headers,
      payload: { provider: 'glm', baseUrl: 'https://llm.example/v1', model: 'glm-4.5', apiKey: 'sk-test' } });
    assert.equal(missingKey.statusCode, 503);
    assert.equal(missingKey.json().error?.code, 'llm_master_key_missing');
  } finally {
    await service.close();
  }
});
