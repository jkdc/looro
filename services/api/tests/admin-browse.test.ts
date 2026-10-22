import { test, before, beforeEach, after } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { createApp } from '../src/app.js';
import { connectDatabase } from '../src/db.js';
import { migrate } from '../src/migrate.js';
import { AuthAdmission } from '../src/auth-admission.js';
import { addAdmin } from '../src/admins-admin.js';
import { adminAuthConfig, adminCookieHeader, type AdminAuthServices } from '../src/admin-auth.js';
import { buildBrowseQuery, BROWSE_TABLES } from '../src/admin-browse.js';
import { snapshot, timeseries } from '../src/admin-metrics.js';
import { appendMinuteEntry } from '../src/minutes.js';
import { transaction } from '../src/db.js';

const hmacKey = 'a'.repeat(64), proxyToken = 'b'.repeat(64);
const url = process.env.TEST_DATABASE_URL;
if (url && !new URL(url).pathname.endsWith('_test')) throw new Error('Use a dedicated database ending in _test.');
const db = url ? connectDatabase(url) : null;
before(async () => { if (db) await migrate(db); });
beforeEach(async () => {
  if (db) {
    await db.query('TRUNCATE admin_actions, admin_sessions, admins, accounts, access_requests, ai_feedback_reports, minute_campaigns CASCADE');
    await db.query("DELETE FROM auth_rate_limits WHERE operation IN ('admin','admin-api')");
  }
});
after(async () => { if (db) await db.end(); });
const integration = (name: string, fn: () => Promise<void>) => test(name, { skip: !db && 'Set TEST_DATABASE_URL for PostgreSQL tests.' }, fn);

const config = { ...adminAuthConfig({ ADMIN_HMAC_KEY: hmacKey,
  ADMIN_PROXY_TOKEN: proxyToken, ADMIN_PUBLIC_ORIGIN: 'https://admin.example' })! };const adminServices = { db: db!, config } as AdminAuthServices;
const app = () => createApp({ db: db!, auth: {}, admin: { services: adminServices,
  admission: new AuthAdmission(db!, { hmacKey, proxyToken, allowLocalLoopback: false }) } });
const adminCookie = async (): Promise<string> => {
  const admin = await addAdmin(db!, 'google', 'browser-admin');
  const token = 'E'.repeat(43);
  await db!.query(`INSERT INTO admin_sessions(id,admin_id,token_hash,expires_at) VALUES($1,$2,$3,now()+interval '1 hour')`,
    [randomUUID(), admin.id, (await import('../src/auth.js')).digest(token)]);
  return token;
};
const get = async (path: string, token: string) => (await app()).inject({ method: 'GET', url: path, headers: {
  cookie: adminCookieHeader(token), 'x-app-client-ip': '203.0.113.9', 'x-app-proxy-token': proxyToken } })
  .finally(async () => {});

test('browse query builder rejects unknown tables, columns, operators and magnitudes', () => {
  assert.throws(() => buildBrowseQuery('identities', {}), /table_not_browsable/);
  assert.throws(() => buildBrowseQuery('accounts', { q_nope: 'eq:1' }), /invalid_request/);
  assert.throws(() => buildBrowseQuery('accounts', { q_email: 'gte:abc' }), /invalid_request/);
  assert.throws(() => buildBrowseQuery('accounts', { sort: '(SELECT 1)' }), /invalid_request/);
  assert.throws(() => buildBrowseQuery('accounts', { dir: 'BETWEEN' }), /invalid_request/);
  assert.throws(() => buildBrowseQuery('accounts', { limit: '0' }), /invalid_request/);
  assert.throws(() => buildBrowseQuery('accounts', { limit: '101' }), /invalid_request/);
  assert.throws(() => buildBrowseQuery('accounts', { offset: '-1' }), /invalid_request/);
  assert.throws(() => buildBrowseQuery('accounts', { q_email: 'contains:' }), /invalid_request/);
  assert.throws(() => buildBrowseQuery('accounts', { q_email: 'is-null:x' }), /invalid_request/);
  // The generated SQL never interpolates client text: values bind as parameters.
  const built = buildBrowseQuery('accounts', { q_email: "contains:'; DROP TABLE accounts; --", sort: 'created_at', dir: 'desc' });
  assert.match(built.sql, /ILIKE \$1/);
  assert.equal(built.values[0], "%'; DROP TABLE accounts; --%");
  for (const bad of [
    { q_id: 'eq:not-a-uuid' }, { q_created_at: 'gte:yesterday-ish' }, { q_id: 'eq:abc), (SELECT 1' },
    { q_is_guest: 'eq:1' }, { q_id: 'in:a,b' }
  ]) assert.throws(() => buildBrowseQuery('accounts', bad), /invalid_request/, JSON.stringify(bad));
  // Values PostgreSQL itself would reject (22003) must never reach the cast.
  assert.throws(() => buildBrowseQuery('minute_campaigns', { q_recipient_count: 'gt:2147483648' }), /invalid_request/);
  assert.throws(() => buildBrowseQuery('minute_campaigns', { q_recipient_count: 'gt:9007199254740992' }), /invalid_request/);
  assert.ok(buildBrowseQuery('minute_campaigns', { q_recipient_count: 'gte:2147483647' }));
  assert.throws(() => buildBrowseQuery('wallets', { q_balance_nano: 'gt:9223372036854775808' }), /invalid_request/);
  assert.throws(() => buildBrowseQuery('wallets', { q_balance_nano: 'gt:9999999999999999999' }), /invalid_request/);
  assert.ok(buildBrowseQuery('wallets', { q_balance_nano: 'gte:9223372036854775807' }));
  assert.throws(() => buildBrowseQuery('accounts', { q_created_at: `gte:${'2'.repeat(33)}` }), /invalid_request/);
  assert.ok(buildBrowseQuery('accounts', { q_id: 'in:00000000-0000-0000-0000-000000000000,11111111-1111-1111-1111-111111111111' }));
});

test('every configured table is a plain declared table with whitelisted orderable columns', () => {
  for (const [name, config] of Object.entries(BROWSE_TABLES)) {
    assert.equal(name, config.table);
    assert.ok(config.orderable.includes(config.defaultOrder.column), name);
    for (const columnName of config.orderable) assert.ok(config.columns[columnName], `${name}.${columnName}`);
    // Credential material is never browsable.
    assert.equal(config.columns.token_hash, undefined);
  }
});

integration('browse returns schema, totals, filters and ordered pages', async () => {
  for (const email of ['alpha@example.test', 'beta@example.test', 'gamma@example.test']) {
    const id = randomUUID();
    await db!.query('INSERT INTO accounts(id,email) VALUES($1,$2)', [id, email]);
  }
  const token = await adminCookie();
  const service = app();
  try {
    const page = await service.inject({ method: 'GET', url: '/v1/admin/browse/accounts?limit=2&offset=0&sort=created_at&dir=asc', headers: {
      cookie: adminCookieHeader(token), 'x-app-client-ip': '203.0.113.9', 'x-app-proxy-token': proxyToken } });
    assert.equal(page.statusCode, 200);
    const body = page.json();
    assert.equal(body.total, 3);
    assert.equal(body.rows.length, 2);
    assert.ok(body.columns.columns.email);
    const filtered = await service.inject({ method: 'GET',
      url: `/v1/admin/browse/accounts?q_email=contains:bet&limit=10`, headers: {
      cookie: adminCookieHeader(token), 'x-app-client-ip': '203.0.113.9', 'x-app-proxy-token': proxyToken } });
    assert.equal(filtered.json().total, 1);
    assert.equal(filtered.json().rows[0]!.email, 'beta@example.test');
    const guest = await service.inject({ method: 'GET', url: '/v1/admin/browse/accounts?q_is_guest=eq:true', headers: {
      cookie: adminCookieHeader(token), 'x-app-client-ip': '203.0.113.9', 'x-app-proxy-token': proxyToken } });
    assert.equal(guest.json().total, 0);
    const unknown = await service.inject({ method: 'GET', url: '/v1/admin/browse/not_a_table', headers: {
      cookie: adminCookieHeader(token), 'x-app-client-ip': '203.0.113.9', 'x-app-proxy-token': proxyToken } });
    assert.equal(unknown.statusCode, 404);
    const catalog = await service.inject({ method: 'GET', url: '/v1/admin/browse', headers: {
      cookie: adminCookieHeader(token), 'x-app-client-ip': '203.0.113.9', 'x-app-proxy-token': proxyToken } });
    assert.ok(catalog.json().tables.accounts);
  } finally { await service.close(); }
});

integration('browse injection battery stays literal or rejects', async () => {
  await db!.query('INSERT INTO accounts(id,email) VALUES($1,$2)', [randomUUID(), 'victim@example.test']);
  const token = await adminCookie();
  const service = app();
  try {
    const attempts = [
      `/v1/admin/browse/accounts?q_email=contains:%27%3B%20DROP%20TABLE%20accounts%3B--`,
      `/v1/admin/browse/accounts?q_email=eq:x%27%20OR%20%271%27%3D%271`,
      `/v1/admin/browse/accounts?q_id=eq:00000000-0000-0000-0000-000000000000),%20(SELECT%201`,
      `/v1/admin/browse/accounts?sort=created_at;DROP%20TABLE%20accounts`,
      `/v1/admin/browse/accounts?sort=email--`,
      `/v1/admin/browse/accounts?q_email=in:a@b.test,..%2F..%2Fetc`,
      `/v1/admin/browse/accounts?limit=2%3BDROP%20TABLE%20accounts`
    ];
    for (const target of attempts) {
      const response = await service.inject({ method: 'GET', url: target, headers: {
        cookie: adminCookieHeader(token), 'x-app-client-ip': '203.0.113.9', 'x-app-proxy-token': proxyToken } });
      assert.ok([200, 400].includes(response.statusCode), `${target} -> ${response.statusCode}`);
      if (response.statusCode === 200) assert.equal(response.json().total, 0);
    }
    const surviving = await db!.query('SELECT count(*) AS count FROM accounts');
    assert.equal(Number(surviving.rows[0]!.count), 1);
  } finally { await service.close(); }
});

integration('auth session browsing never exposes token hashes', async () => {
  const account = randomUUID();
  await db!.query('INSERT INTO accounts(id,email) VALUES($1,$2)', [account, 'hashed@example.test']);
  await db!.query(`INSERT INTO auth_sessions(id,account_id,token_hash,expires_at) VALUES($1,$2,'supersecret-hmac',now()+interval '1 hour')`,
    [randomUUID(), account]);
  const token = await adminCookie();
  const service = app();
  try {
    const page = await service.inject({ method: 'GET', url: '/v1/admin/browse/auth_sessions', headers: {
      cookie: adminCookieHeader(token), 'x-app-client-ip': '203.0.113.9', 'x-app-proxy-token': proxyToken } });
    assert.equal(page.statusCode, 200);
    assert.equal(page.body.includes('supersecret-hmac'), false);
    assert.equal(page.json().columns.columns.token_hash, undefined);
  } finally { await service.close(); }
});

integration('metrics snapshot and timeseries summarize synthetic activity with zero-filled days', async () => {
  const account = randomUUID(), guest = randomUUID();
  await db!.query('INSERT INTO accounts(id,email) VALUES($1,$2)', [account, 'metrics@example.test']);
  await db!.query('INSERT INTO accounts(id,email,is_guest) VALUES($1,$2,true)', [guest, null]);
  await transaction(db!, async sql => {
    await appendMinuteEntry(sql, account, `welcome:${account}`, 'welcome', 600_000, 0);
    await appendMinuteEntry(sql, account, `settle:${account}`, 'settle', -120_000, 0);
  });
  // minute-funded session following the existing test pattern (reservation row first).
  await transaction(db!, async sql => {
    const reservation = randomUUID();
    await appendMinuteEntry(sql, account, `reserve:${account}`, 'reserve', 0, 60_000);
    await sql.query('INSERT INTO minute_reservations(id,account_id,idempotency_key,amount_ms) VALUES($1,$2,$3,60000)',
      [reservation, account, 'metrics-reserve']);
    await sql.query(`INSERT INTO hosted_sessions(id,account_id,idempotency_key,minute_reservation_id,reserved_ms,
      rate_version,state,deadline,funding_exposure_nano,minimum_charge_ms,charged_ms,provider_cost_nano,close_requested_at,close_reason)
      VALUES($1,$2,'metrics-key',$3,60000,'test','closed',now()+interval '10 minutes',0,15000,50000,1000,now(),'operator_close')`,
      [randomUUID(), account, reservation]);
  });
  await db!.query(`INSERT INTO access_requests(email,requested_at,consent_version,source) VALUES('wl@example.test',now(),'waitlist-v1','website')`);
  await db!.query(`INSERT INTO minute_purchase_orders(id,account_id,idempotency_key,provider,environment,merchant,sku,provider_product,currency,total_minor,allowance_ms,entitlement_kind)
    VALUES($1,$2,'metrics-order','stripe','test','merchant','sku','prod','usd',1000,600000,'minutes')`, [randomUUID(), account]);
  const snap = await snapshot(db!);
  assert.ok(snap.accounts.total >= 2);
  assert.ok(snap.accounts.guests >= 1);
  assert.ok(snap.minuteWallets.balanceMs >= 480_000);
  assert.ok((snap.hostedSessions['closed'] ?? 0) >= 1);
  assert.ok(snap.waitlist >= 1);
  const voice = await timeseries(db!, 'voice', 30);
  assert.equal(voice.series.length, 5);
  for (const serie of voice.series) {
    assert.equal(serie.points.length, 30);
    assert.ok(serie.points.at(-1)!.value >= 0);
  }
  const sessions = voice.series.find(entry => entry.name === 'sessions')!;
  assert.ok(sessions.points.at(-1)!.value >= 1);
  const filled = await timeseries(db!, 'overview', 90);
  assert.equal(filled.series[0]!.points.length, 90);
  await assert.rejects(timeseries(db!, 'nope', 30));
});

integration('token-billing rows are browsable: entitlements, metering ledger and pack transactions', async () => {
  const account = randomUUID(), order = randomUUID();
  await db!.query('INSERT INTO accounts(id,email) VALUES($1,$2)', [account, 'packs@example.test']);
  // The pack quote is enforced by a deferred constraint trigger, so order and quote share one transaction.
  await transaction(db!, async sql => {
    await sql.query(`INSERT INTO minute_purchase_orders(id,account_id,idempotency_key,provider,environment,merchant,sku,provider_product,currency,total_minor,entitlement_kind)
      VALUES($1,$2,'pack-order','play','test','merchant','token_pack_1m','prod.pack','usd',499,'token_pack')`, [order, account]);
    await sql.query(`INSERT INTO pack_purchase_quotes(order_id,kind,credit_nano,rate_version)
      VALUES($1,'token_pack',1000000000,'2026-09-minimax-m')`, [order]);
  });
  await db!.query(`INSERT INTO pack_purchase_transactions(order_id,account_id,provider,environment,merchant,transaction_hash,state,credit_nano,granted_nano)
    VALUES($1,$2,'play','test','merchant',$3,'purchased',1000000000,1000000000)`, [order, account, 'c'.repeat(64)]);
  await db!.query(`INSERT INTO entitlements(account_id,unlocked_at,unlock_source,unlock_order_id) VALUES($1,now(),'play',$2)`, [account, order]);
  await db!.query(`INSERT INTO ledger(id,account_id,reference,kind,balance_delta_nano,reserved_delta_nano,rate_version)
    VALUES($1,$2,'pack:purchase','purchase',1000000000,0,'2026-09-minimax-m')`, [randomUUID(), account]);
  await db!.query(`INSERT INTO ledger(id,account_id,reference,kind,balance_delta_nano,reserved_delta_nano,rate_version)
    VALUES($1,$2,$3,'settle',-90000,0,'2026-09-minimax-m')`, [randomUUID(), account, `settlement:${order}`]);
  const token = await adminCookie();
  const service = app();
  const headers = { cookie: adminCookieHeader(token), 'x-app-client-ip': '203.0.113.9', 'x-app-proxy-token': proxyToken };
  try {
    const entitlement = await service.inject({ method: 'GET', url: '/v1/admin/browse/entitlements?q_unlock_source=eq:play', headers });
    assert.equal(entitlement.statusCode, 200);
    assert.equal(entitlement.json().total, 1);
    assert.equal(entitlement.json().rows[0]!.unlock_order_id, order);
    const metering = await service.inject({ method: 'GET',
      url: '/v1/admin/browse/ledger?q_rate_version=eq:2026-09-minimax-m&sort=created_at&dir=asc', headers });
    assert.equal(metering.statusCode, 200);
    assert.equal(metering.json().total, 2);
    assert.deepEqual(metering.json().rows.map((row: { kind: string }) => row.kind), ['purchase', 'settle']);
    assert.equal(metering.json().rows[1]!.balance_delta_nano, '-90000');
    const unpriced = await service.inject({ method: 'GET', url: '/v1/admin/browse/ledger?q_rate_version=is-null:', headers });
    assert.equal(unpriced.json().total, 0);
    const packs = await service.inject({ method: 'GET', url: '/v1/admin/browse/pack_purchase_transactions?q_state=eq:purchased', headers });
    assert.equal(packs.statusCode, 200);
    assert.equal(packs.json().total, 1);
    assert.equal(packs.json().rows[0]!.order_id, order);
    assert.equal(packs.json().rows[0]!.granted_nano, '1000000000');
    const catalog = await service.inject({ method: 'GET', url: '/v1/admin/browse', headers });
    for (const name of ['entitlements', 'ledger', 'pack_purchase_transactions']) assert.ok(catalog.json().tables[name], name);
  } finally { await service.close(); }
});

integration('unauthenticated browsing fails closed', async () => {
  const service = app();
  try {
    const bare = await service.inject({ method: 'GET', url: '/v1/admin/browse/accounts', headers: {
      'x-app-client-ip': '203.0.113.9', 'x-app-proxy-token': proxyToken } });
    assert.equal(bare.statusCode, 401);
    const foreign = await service.inject({ method: 'GET', url: '/v1/admin/browse/accounts', headers: {
      cookie: 'admin_session=FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF',
      'x-app-client-ip': '203.0.113.9', 'x-app-proxy-token': proxyToken } });
    assert.equal(foreign.statusCode, 401);
  } finally { await service.close(); }
});

integration('browse rejects values PostgreSQL cannot cast instead of surfacing a 500', async () => {
  const token = await adminCookie();
  const service = app();
  try {
    for (const target of [
      '/v1/admin/browse/minute_campaigns?q_recipient_count=gt:2147483648',
      '/v1/admin/browse/wallets?q_balance_nano=gt:9999999999999999999',
      `/v1/admin/browse/accounts?q_created_at=gte:${'2'.repeat(33)}`
    ]) {
      const response = await service.inject({ method: 'GET', url: target, headers: {
        cookie: adminCookieHeader(token), 'x-app-client-ip': '203.0.113.9', 'x-app-proxy-token': proxyToken } });
      assert.equal(response.statusCode, 400, target);
      assert.equal(response.json().error?.code, 'invalid_request');
    }
  } finally { await service.close(); }
});
