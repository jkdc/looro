import { test, before, beforeEach, after } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { createApp } from '../src/app.js';
import { connectDatabase, transaction } from '../src/db.js';
import { migrate } from '../src/migrate.js';
import { AuthAdmission } from '../src/auth-admission.js';
import { addAdmin } from '../src/admins-admin.js';
import { adminAuthConfig, adminCookieHeader, type AdminAuthServices } from '../src/admin-auth.js';
import { HostedVoice } from '../src/hosted-voice.js';
import { appendMinuteEntry } from '../src/minutes.js';

const hmacKey = 'a'.repeat(64), proxyToken = 'b'.repeat(64);
const url = process.env.TEST_DATABASE_URL;
if (url && !new URL(url).pathname.endsWith('_test')) throw new Error('Use a dedicated database ending in _test.');
const db = url ? connectDatabase(url) : null;
before(async () => { if (db) await migrate(db); });
beforeEach(async () => {
  if (db) {
    await db.query('TRUNCATE admin_actions, admin_sessions, admins, accounts, access_requests, minute_campaigns CASCADE');
    await db.query("DELETE FROM auth_rate_limits WHERE operation IN ('admin','admin-api')");
    await db.query('INSERT INTO minute_policy(singleton) VALUES(true) ON CONFLICT (singleton) DO NOTHING');
    await db.query('UPDATE minute_policy SET version=1,welcome_enabled=false,welcome_ms=600000 WHERE singleton');
  }
});
after(async () => { if (db) await db.end(); });
const integration = (name: string, fn: () => Promise<void>) => test(name, { skip: !db && 'Set TEST_DATABASE_URL for PostgreSQL tests.' }, fn);

const config = { ...adminAuthConfig({ ADMIN_HMAC_KEY: hmacKey,
  ADMIN_PROXY_TOKEN: proxyToken, ADMIN_PUBLIC_ORIGIN: 'https://admin.example' })! };
const adminServices = { db: db!, config } as AdminAuthServices;
const hosted = db ? new HostedVoice(db, {} as never, { accountAllowlist: new Set(), billingUnit: 'milliseconds',
  lifetimeFundingCapNano: 0n, publicMinuteAccess: true }) : undefined;
const app = (withHosted = true) => createApp({ db: db!, auth: {}, hosted: withHosted ? hosted : undefined,
  admin: { services: adminServices, admission: new AuthAdmission(db!, { hmacKey, proxyToken, allowLocalLoopback: false }) } });
const cookie = async (): Promise<string> => {
  const admin = await addAdmin(db!, 'google', 'actor-admin', 'root@example.test');
  const token = 'E'.repeat(43);
  await db!.query(`INSERT INTO admin_sessions(id,admin_id,token_hash,expires_at) VALUES($1,$2,$3,now()+interval '1 hour')`,
    [randomUUID(), admin.id, (await import('../src/auth.js')).digest(token)]);
  return token;
};
const H = (token: string, extra: Record<string, unknown> = {}) => ({
  'content-type': 'application/json', cookie: adminCookieHeader(token),
  'x-app-client-ip': '203.0.113.9', 'x-app-proxy-token': proxyToken,
  origin: 'https://admin.example', ...extra
});

integration('welcome policy update writes both audits atomically and refuses stale versions', async () => {
  const token = await cookie(), service = app();
  try {
    const current = (await service.inject({ method: 'GET', url: '/v1/admin/policy/welcome', headers: H(token) })).json();
    assert.equal(current.welcomeEnabled, false);
    const updated = await service.inject({ method: 'PUT', url: '/v1/admin/policy/welcome', headers: H(token),
      payload: { version: current.version, welcomeEnabled: true, welcomeMinutes: 15, reason: 'launch cohort allowance' } });
    assert.equal(updated.statusCode, 200);
    assert.equal(updated.json().welcomeMinutes, 15);
    const policyAudit = await db!.query('SELECT actor,reason FROM minute_policy_audit ORDER BY created_at DESC LIMIT 1');
    assert.equal(policyAudit.rows[0]!.actor, 'admin:root@example.test');
    const adminAudit = await db!.query("SELECT action,target,reason FROM admin_actions WHERE action='policy.update'");
    assert.equal(adminAudit.rows[0]!.reason, 'launch cohort allowance');
    // A stale version is refused before any write, including the panel audit.
    const stale = await service.inject({ method: 'PUT', url: '/v1/admin/policy/welcome', headers: H(token),
      payload: { version: current.version, welcomeEnabled: true, welcomeMinutes: 20, reason: 'stale attempt' } });
    assert.equal(stale.statusCode, 409);
    const actions = await db!.query("SELECT count(*) AS count FROM admin_actions WHERE reason='stale attempt'");
    assert.equal(Number(actions.rows[0]!.count), 0);
    // Reasons are mandatory.
    const noReason = await service.inject({ method: 'PUT', url: '/v1/admin/policy/welcome', headers: H(token),
      payload: { version: current.version + 1, welcomeEnabled: true, welcomeMinutes: 15, reason: '' } });
    assert.equal(noReason.statusCode, 400);
  } finally { await service.close(); }
});

integration('single-user campaigns prepare and apply grant audited minutes', async () => {
  const token = await cookie(), service = app();
  const account = randomUUID();
  await db!.query('INSERT INTO accounts(id,email) VALUES($1,$2)', [account, 'giftee@example.test']);
  await db!.query('INSERT INTO minute_wallets(account_id) VALUES($1)', [account]);
  try {
    const prepared = await service.inject({ method: 'POST', url: '/v1/admin/campaigns', headers: H(token),
      payload: { audience: [account], minutesPerUser: 5, maxTotalMinutes: 100, reason: 'make-good for support ticket' } });
    assert.equal(prepared.statusCode, 201);
    const summary = prepared.json();
    assert.equal(summary.recipients, 1);
    assert.match(summary.confirmation, /^[a-f0-9]{64}$/);
    const applied = await service.inject({ method: 'POST',
      url: `/v1/admin/campaigns/${summary.campaignID}/apply`, headers: H(token),
      payload: { confirmation: summary.confirmation, reason: 'make-good for support ticket' } });
    assert.equal(applied.statusCode, 200);
    assert.deepEqual(applied.json(), { campaignID: summary.campaignID, pending: 0, granted: 1, skipped: 0 });
    const wallet = await db!.query('SELECT balance_ms FROM minute_wallets WHERE account_id=$1', [account]);
    assert.equal(Number(wallet.rows[0]!.balance_ms), 300_000);
    const actions = await db!.query("SELECT action FROM admin_actions WHERE action IN ('campaign.prepare','campaign.apply')");
    assert.deepEqual(actions.rows.map(row => row.action).sort(), ['campaign.apply', 'campaign.prepare']);
    // A wrong confirmation cannot apply someone else's campaign.
    const forged = await service.inject({ method: 'POST',
      url: `/v1/admin/campaigns/${summary.campaignID}/apply`, headers: H(token),
      payload: { confirmation: 'f'.repeat(64), reason: 'forged apply attempt' } });
    assert.equal(forged.statusCode, 409);
  } finally { await service.close(); }
});

integration('waitlist deletion is audited and missing entries return 404', async () => {
  const token = await cookie(), service = app();
  await db!.query(`INSERT INTO access_requests(email,requested_at,consent_version,source)
    VALUES('gone@example.test',now(),'waitlist-v1','website')`);
  try {
    const deleted = await service.inject({ method: 'DELETE', url: '/v1/admin/access-requests', headers: H(token),
      payload: { email: 'gone@example.test', reason: 'user asked to be removed' } });
    assert.equal(deleted.statusCode, 200);
    const remaining = await db!.query('SELECT count(*) AS count FROM access_requests WHERE email=$1', ['gone@example.test']);
    assert.equal(Number(remaining.rows[0]!.count), 0);
    const audit = await db!.query("SELECT target,reason FROM admin_actions WHERE action='access_request.delete'");
    assert.equal(audit.rows[0]!.target, 'gone@example.test');
    const again = await service.inject({ method: 'DELETE', url: '/v1/admin/access-requests', headers: H(token),
      payload: { email: 'gone@example.test', reason: 'repeat removal attempt' } });
    assert.equal(again.statusCode, 404);
    const invalid = await service.inject({ method: 'DELETE', url: '/v1/admin/access-requests', headers: H(token),
      payload: { email: 'not-an-email', reason: 'malformed address' } });
    assert.equal(invalid.statusCode, 400);
  } finally { await service.close(); }
});

integration('operator force close marks the session and the action is audited', async () => {
  const token = await cookie(), service = app(true);
  const account = randomUUID(), reservation = randomUUID(), session = randomUUID();
  await db!.query('INSERT INTO accounts(id,email) VALUES($1,$2)', [account, 'talker@example.test']);
  await db!.query('INSERT INTO minute_wallets(account_id) VALUES($1)', [account]);
  await transaction(db!, async sql => {
    await appendMinuteEntry(sql, account, `welcome:${account}`, 'welcome', 600_000, 0);
    await appendMinuteEntry(sql, account, 'op-reserve', 'reserve', 0, 120_000);
    await sql.query('INSERT INTO minute_reservations(id,account_id,idempotency_key,amount_ms) VALUES($1,$2,$3,120000)',
      [reservation, account, 'op-close']);
    await sql.query(`INSERT INTO hosted_sessions(id,account_id,idempotency_key,minute_reservation_id,reserved_ms,
      rate_version,state,deadline,funding_exposure_nano,minimum_charge_ms)
      VALUES($1,$2,'op-close-key',$3,120000,'test','active',now()+interval '10 minutes',50000000,15000)`,
      [session, account, reservation]);
  });
  try {
    const closed = await service.inject({ method: 'POST', url: `/v1/admin/hosted-sessions/${session}/close`,
      headers: H(token), payload: { reason: 'runaway session cost control' } });
    assert.equal(closed.statusCode, 200);
    const row = await db!.query('SELECT state,close_reason FROM hosted_sessions WHERE id=$1', [session]);
    assert.ok(['closing', 'closed'].includes(row.rows[0]!.state));
    const audit = await db!.query("SELECT target,reason FROM admin_actions WHERE action='hosted_session.close'");
    assert.equal(audit.rows[0]!.target, session);
    const missing = await service.inject({ method: 'POST', url: `/v1/admin/hosted-sessions/${randomUUID()}/close`,
      headers: H(token), payload: { reason: 'session vanished mid-check' } });
    assert.equal(missing.statusCode, 404);
  } finally { await service.close(); }
});

integration('write actions fail closed without the hosted service or a foreign origin', async () => {
  const token = await cookie();
  const noHosted = app(false);
  try {
    const closed = await noHosted.inject({ method: 'POST', url: `/v1/admin/hosted-sessions/${randomUUID()}/close`,
      headers: H(token), payload: { reason: 'hosted unavailable' } });
    assert.equal(closed.statusCode, 503);
  } finally { await noHosted.close(); }
  const service = app();
  try {
    const foreign = await service.inject({ method: 'PUT', url: '/v1/admin/policy/welcome',
      headers: H(token, { origin: 'https://evil.example' }),
      payload: { version: 1, welcomeEnabled: true, welcomeMinutes: 10, reason: 'cross-site attempt' } });
    assert.equal(foreign.statusCode, 403);
    const actions = await db!.query('SELECT count(*) AS count FROM admin_actions');
    assert.equal(Number(actions.rows[0]!.count), 0);
  } finally { await service.close(); }
});

integration('daily admin job monitor is private and omits learner-bound attempt data', async () => {
  const token = await cookie(), service = app(), runID = randomUUID(), bindingID = randomUUID();
  const day = new Date().toISOString().slice(0, 10);
  await db!.query(`INSERT INTO daily_jobs(id,kind,binding_id,language,editorial_date,state,config_snapshot,error_code)
    VALUES($1,'content',$2,'en',$3,'failed','{"schemaVersion":1}'::jsonb,'audio_check_failed')`, [runID,bindingID,day]);
  await db!.query(`INSERT INTO daily_cost_reservations(id,job_id,cost_center,budget_date,binding_id,max_nano_usd,settled_nano_usd,state,settled_at)
    VALUES($1,$2,'content',$3,$4,500,200,'settled',now())`, [randomUUID(),runID,day,bindingID]);
  try {
    const denied = await service.inject({ method: 'GET', url: `/v1/admin/daily-practice/jobs?language=en&dateFrom=${day}&dateTo=${day}`,
      headers: H('invalid') });
    assert.equal(denied.statusCode, 401);
    const response = await service.inject({ method: 'GET', url: `/v1/admin/daily-practice/jobs?language=en&dateFrom=${day}&dateTo=${day}`,
      headers: H(token) });
    assert.equal(response.statusCode, 200, response.body);
    assert.equal(response.headers['cache-control'], 'private, no-store');
    const body = response.json();
    assert.equal(body.items.length, 1);
    assert.equal(body.items[0].id, runID);
    assert.equal(body.items[0].error_code, 'audio_check_failed');
    assert.equal(body.items[0].reserved_nano_usd, '500');
    assert.equal(body.items[0].settled_nano_usd, '200');
    assert.equal('attempt_id' in body.items[0], false);
    assert.equal('config_snapshot' in body.items[0], false);
  } finally {
    await db!.query('DELETE FROM daily_cost_reservations WHERE job_id=$1', [runID]);
    await db!.query('DELETE FROM daily_jobs WHERE id=$1', [runID]);
    await service.close();
  }
});
