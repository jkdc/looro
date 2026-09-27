import { test, before, beforeEach, after } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID, randomBytes, createHash } from 'node:crypto';
import { createApp } from '../src/app.js';
import { connectDatabase, transaction } from '../src/db.js';
import { migrate } from '../src/migrate.js';
import { AuthAdmission } from '../src/auth-admission.js';
import { addAdmin } from '../src/admins-admin.js';
import { adminAuthConfig, adminCookieHeader, type AdminAuthServices } from '../src/admin-auth.js';
import { appendMinuteEntry, lockMinuteWallet, minuteBalance } from '../src/minutes.js';

type Services = NonNullable<Parameters<typeof createApp>[0]['admin']>;
const url = process.env.TEST_DATABASE_URL;
if (url && !new URL(url).pathname.endsWith('_test')) throw new Error('Use a dedicated database ending in _test.');
const db = url ? connectDatabase(url) : null;
before(async () => { if (db) await migrate(db); });
beforeEach(async () => {
  if (db) {
    await db.query('TRUNCATE admin_actions, admin_sessions, admins, subscriptions, subscription_grants CASCADE');
    await db.query(`DELETE FROM auth_rate_limits WHERE operation IN ('admin','admin-api')`);
    // Restore seeded commercial state: the operator test mutates it by design.
    await db.query(`UPDATE subscription_config SET version=1, free_drip_enabled=true, free_drip_minutes=5,
      free_drip_rollover_cap_minutes=15, trial_days=7, lifetime_drip_budget_minutes=0`);
    await db.query(`UPDATE subscription_plans SET monthly_minutes=75, rollover_cap_minutes=150, features='{"aiReports":false,"unlimitedThemes":false}'::jsonb WHERE tier='standard'`);
    await db.query(`UPDATE subscription_plans SET monthly_minutes=250, rollover_cap_minutes=500, features='{"aiReports":true,"unlimitedThemes":true}'::jsonb WHERE tier='pro'`);
    await db.query('DELETE FROM subscription_grants');
  }
});
after(async () => { if (db) await db.end(); });
const integration = (name: string, fn: () => Promise<void>) => test(name, { skip: !db && 'Set TEST_DATABASE_URL for PostgreSQL tests.' }, fn);

const hmacKey = 'a'.repeat(64), proxyToken = 'b'.repeat(64);
const adminEnv = { ADMIN_HMAC_KEY: hmacKey, ADMIN_PROXY_TOKEN: proxyToken, ADMIN_PUBLIC_ORIGIN: 'https://admin.example' };
const adminServices: Services | null = db ? (() => {
  const services: AdminAuthServices = { db: db!, config: adminAuthConfig(adminEnv)! };
  return { services, admission: new AuthAdmission(db!, { hmacKey, proxyToken, allowLocalLoopback: false }) };
})() : null;
const adminHeaders = (token?: string, origin?: string) => ({
  ...(token ? { cookie: adminCookieHeader(token) } : {}),
  ...(origin ? { origin } : {}), 'content-type': 'application/json',
  'x-app-client-ip': '203.0.113.9', 'x-app-proxy-token': proxyToken
});

const newAccount = async (): Promise<string> => {
  const id = randomUUID();
  await db!.query('INSERT INTO accounts(id,email) VALUES($1,$2)', [id, `sub-${id}@example.test`]);
  return id;
};
const accountToken = async (): Promise<string> => {
  const account = await newAccount();
  const token = randomBytes(32).toString('base64url').slice(0, 43);
  await db!.query(`INSERT INTO auth_sessions(id,account_id,token_hash,expires_at) VALUES($1,$2,$3,now()+interval '1 hour')`,
    [randomUUID(), account, createHash('sha256').update(token).digest('hex')]);
  return token;
};
const seedWallet = async (account: string, minutes: number) => {
  await transaction(db!, async sql => appendMinuteEntry(sql, account, `seed:${account}:${minutes}`, 'purchase', minutes * 60_000, 0));
};
const walletMinutes = async (account: string): Promise<number> => {
  const balance = await minuteBalance(db!, account);
  return Math.round(balance.availableMilliseconds / 60_000);
};

integration('plan catalog exposes versioned config and public tiers', async () => {
  const catalog = await import('../src/subscriptions.js').then(m => m.subscriptionCatalog(db!));
  assert.equal(catalog.config.freeDripMinutes, 5);
  assert.equal(catalog.config.trialDays, 7);
  assert.deepEqual(catalog.tiers.map(t => t.tier), ['standard', 'pro']);
  for (const tier of catalog.tiers) assert.equal((tier as { stripePriceID?: string }).stripePriceID, undefined);
});

integration('subscription config updates are versioned, reason-required and audited', async () => {
  const admin = await addAdmin(db!, 'google', 'plans-admin');
  const token = 'A'.repeat(43);
  await db!.query(`INSERT INTO admin_sessions(id,admin_id,token_hash,expires_at) VALUES($1,$2,$3,now()+interval '1 hour')`,
    [randomUUID(), admin.id, createHash('sha256').update(token).digest('hex')]);
  const service = createApp({ db: db!, auth: {}, admin: adminServices! });
  try {
    const get = await service.inject({ method: 'GET', url: '/v1/admin/plans', headers: adminHeaders(token) });
    assert.equal(get.statusCode, 200);
    const version = get.json().config.version;
    const stale = await service.inject({ method: 'PUT', url: '/v1/admin/plans', headers: adminHeaders(token, 'https://admin.example'),
      payload: { version: version - 1, reason: 'prueba de version anticuada', freeDripMinutes: 3 } });
    assert.equal(stale.statusCode, 409);
    const noReason = await service.inject({ method: 'PUT', url: '/v1/admin/plans', headers: adminHeaders(token, 'https://admin.example'),
      payload: { version, freeDripMinutes: 3 } });
    assert.equal(noReason.statusCode, 400);
    const ok = await service.inject({ method: 'PUT', url: '/v1/admin/plans', headers: adminHeaders(token, 'https://admin.example'),
      payload: { version, reason: 'subir goteo a 3 minutos', freeDripMinutes: 3,
        tiers: [{ tier: 'pro', monthlyMinutes: 300, rolloverCapMinutes: 600, features: { aiReports: true } }] } });
    assert.equal(ok.statusCode, 200);
    assert.equal(ok.json().config.version, version + 1);
    assert.equal(ok.json().config.freeDripMinutes, 3);
    assert.equal(ok.json().tiers.find((t: { tier: string }) => t.tier === 'pro').monthlyMinutes, 300);
    const audit = await db!.query(`SELECT action, reason FROM admin_actions WHERE action='plans.update'`);
    assert.equal(audit.rowCount, 1);
  } finally { await service.close(); }
});

integration('period grants are idempotent and respect the rollover cap', async () => {
  const { subscriptionCatalog, upsertSubscriptionFromStore, grantSubscriptionPeriod } =
    await import('../src/subscriptions.js');
  const account = await newAccount();
  const sub = await upsertSubscriptionFromStore(db!, { accountID: account, tier: 'pro', status: 'active', store: 'stripe',
    storeReference: 'sub_test_123', periodStart: new Date('2026-10-01T00:00:00Z'), periodEnd: new Date('2026-11-01T00:00:00Z') });
  const first = await grantSubscriptionPeriod(db!, sub);
  assert.equal(first, 250);
  assert.equal(await walletMinutes(account), 250);
  const replay = await grantSubscriptionPeriod(db!, sub);
  assert.equal(replay, 0);
  assert.equal(await walletMinutes(account), 250);
  // Rollover cap: existing balance above the cap means the grant shrinks to zero.
  const pro = (await subscriptionCatalog(db!)).tiers.find(t => t.tier === 'pro')!;
  const sub2 = await upsertSubscriptionFromStore(db!, { accountID: account, tier: 'standard', status: 'active', store: 'play',
    storeReference: 'play-token-1', periodStart: new Date('2026-10-02T00:00:00Z'), periodEnd: new Date('2026-11-02T00:00:00Z') });
  const capped = await grantSubscriptionPeriod(db!, sub2);
  assert.ok(capped <= 75);
  assert.ok(await walletMinutes(account) <= pro.rolloverCapMinutes + capped);
});

integration('the free drip runs once per month and not for subscribed accounts', async () => {
  const { runDripForAccount, upsertSubscriptionFromStore } = await import('../src/subscriptions.js');
  const account = await newAccount();
  assert.equal(await runDripForAccount(db!, account), 5);
  assert.equal(await walletMinutes(account), 5);
  assert.equal(await runDripForAccount(db!, account), 0);
  assert.equal(await walletMinutes(account), 5);
  // An active subscription suppresses the drip for the same period key.
  const sub = await upsertSubscriptionFromStore(db!, { accountID: account, tier: 'standard', status: 'active', store: 'stripe',
    storeReference: 'sub_drip_test', periodStart: new Date(), periodEnd: new Date(Date.now() + 86_400_000) });
  const { grantSubscriptionPeriod: grant2 } = await import("../src/subscriptions.js");
  await grant2(db!, sub);
  const before = await walletMinutes(account);
  await runDripForAccount(db!, account);
  assert.equal(await walletMinutes(account), before);
});

integration('the minutes endpoint enriches the balance with subscription state', async () => {
  const service = createApp({ db: db!, auth: {}, accounts: { admission: new AuthAdmission(db!, { hmacKey: 'c'.repeat(64), proxyToken: 'd'.repeat(64), allowLocalLoopback: false }) } });
  try {
    const token = await accountToken();
    const response = await service.inject({ method: 'GET', url: '/v1/minutes',
      headers: { authorization: `Bearer ${token}`, 'x-app-client-ip': '203.0.113.9', 'x-app-proxy-token': 'd'.repeat(64) } });
    assert.equal(response.statusCode, 200);
    const body = response.json();
    assert.equal(body.subscription.tier, 'free');
    assert.equal(body.subscription.drip.enabled, true);
    assert.deepEqual(body.subscription.features, {});
  } finally { await service.close(); }
});

integration('stripe subscription webhooks upsert state and grant once per delivery', async () => {
  const { SubscriptionBilling } = await import('../src/subscription-billing.js');
  const account = await newAccount();
  const events: Array<{ type: string; data: { object: any } }> = [];
  const billing = new SubscriptionBilling(db!, {
    async create() { return { id: 'cs_test_1', url: 'https://checkout.stripe.test/1' }; },
    verifyEvent() { return events.shift()!; }
  }, { origin: 'https://api.example' });
  // checkout.session.completed creates the subscription (trialing by default config).
  events.push({ type: 'checkout.session.completed', data: { object: { mode: 'subscription', subscription: 'sub_web_1',
    metadata: { account_id: account, tier: 'pro' } } } });
  assert.equal(await billing.handleEvent(events[0]!), true);
  const mine = await db!.query('SELECT status,tier FROM subscriptions WHERE store_reference=$1', ['sub_web_1']);
  assert.equal(mine.rows[0]!.status, 'trialing');
  // invoice.paid activates and grants; a retry is a no-op.
  const invoice = { type: 'invoice.paid', data: { object: { subscription: 'sub_web_1', period_start: 1_783_000_000, period_end: 1_785_600_000 } } };
  assert.equal(await billing.handleEvent(invoice), true);
  const walletAfterFirst = await walletMinutes(account);
  assert.equal(walletAfterFirst, 250);
  events.push(invoice);
  assert.equal(await billing.handleEvent(invoice), true);
  assert.equal(await walletMinutes(account), walletAfterFirst);
  // payment failure moves the row to past_due.
  assert.equal(await billing.handleEvent({ type: 'invoice.payment_failed', data: { object: { subscription: 'sub_web_1' } } }), true);
  assert.equal((await db!.query('SELECT status FROM subscriptions WHERE store_reference=$1', ['sub_web_1'])).rows[0]!.status, 'past_due');
  // Non-subscription events are left for the minutes path.
  assert.equal(await billing.handleEvent({ type: 'charge.refunded', data: { object: {} } }), false);
});

integration('the subscription checkout endpoint requires configuration and a tier', async () => {
  const service = createApp({ db: db!, auth: {}, accounts: { admission: new AuthAdmission(db!, { hmacKey: 'c'.repeat(64), proxyToken: 'd'.repeat(64), allowLocalLoopback: false }) } });
  try {
    const token = await accountToken();
    const missing = await service.inject({ method: 'POST', url: '/v1/subscriptions/checkout',
      headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json', 'idempotency-key': 'k'.repeat(12),
        'x-app-client-ip': '203.0.113.9', 'x-app-proxy-token': 'd'.repeat(64) }, payload: { tier: 'pro' } });
    assert.equal(missing.statusCode, 503);
  } finally { await service.close(); }
});
