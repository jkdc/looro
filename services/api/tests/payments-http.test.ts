import { test, before, beforeEach, after } from 'node:test';
import assert from 'node:assert/strict';
import { createHash, randomBytes, randomUUID } from 'node:crypto';
import { createApp, type Services } from '../src/app.js';
import { connectDatabase } from '../src/db.js';
import { migrate } from '../src/migrate.js';
import { catalogFromEnvironment, type SandboxPayments } from '../src/payments.js';
import { STRIPE_MINUTES_WEBHOOK_PATH } from '../src/stripe-minute-provider.js';

test('environment catalog builds only complete sandbox top-ups', () => {
  const catalog = catalogFromEnvironment({
    STRIPE_TEST_PRICE_10_USD: 'price_ten', STRIPE_TEST_PAYMENT_FEE_10_MINOR: '59',
    STRIPE_TEST_PRICE_25_USD: 'price_twentyfive', STRIPE_TEST_PAYMENT_FEE_25_MINOR: '10000'
  });
  assert.deepEqual([...catalog.keys()], ['ai-10-usd', 'ai-25-usd']);
  assert.deepEqual(catalog.get('ai-10-usd'), { id: 'ai-10-usd', priceID: 'price_ten',
    aiMinor: 1000, serviceFeeMinor: 150, paymentFeeMinor: 59 });
  assert.equal(catalog.get('ai-25-usd')!.paymentFeeMinor, 10_000);

  const absent = catalogFromEnvironment({});
  assert.equal(absent.size, 0);
  for (const env of [
    { STRIPE_TEST_PRICE_10_USD: 'pk_not_a_price', STRIPE_TEST_PAYMENT_FEE_10_MINOR: '59' },
    { STRIPE_TEST_PRICE_10_USD: 'price_ten' },
    { STRIPE_TEST_PRICE_10_USD: 'price_ten', STRIPE_TEST_PAYMENT_FEE_10_MINOR: 'abc' },
    { STRIPE_TEST_PRICE_10_USD: 'price_ten', STRIPE_TEST_PAYMENT_FEE_10_MINOR: '10001' },
    { STRIPE_TEST_PRICE_10_USD: 'price_ten', STRIPE_TEST_PAYMENT_FEE_10_MINOR: '1.5' }
  ]) assert.equal(catalogFromEnvironment(env).size, 0);
});

const url = process.env.TEST_DATABASE_URL;
if (url && !new URL(url).pathname.endsWith('_test')) throw new Error('Use an isolated database ending in _test.');
const db = url ? connectDatabase(url) : null;
before(async () => { if (db) await migrate(db); });
beforeEach(async () => {
  if (db) {
    await db.query('TRUNCATE auth_sessions, accounts CASCADE');
    await db.query("DELETE FROM auth_rate_limits WHERE operation='account'");
  }
});
after(async () => { if (db) await db.end(); });
const integration = (name: string, fn: () => Promise<void>) => test(name, { skip: !db && 'Set TEST_DATABASE_URL for PostgreSQL tests.' }, fn);

const accountAndToken = async (): Promise<{ account: string; token: string }> => {
  const account = randomUUID();
  const token = randomBytes(32).toString('base64url').slice(0, 43);
  await db!.query('INSERT INTO accounts(id,email) VALUES($1,$2)', [account, `checkout-${account}@example.test`]);
  await db!.query(`INSERT INTO auth_sessions(id,account_id,token_hash,expires_at) VALUES($1,$2,$3,now()+interval '1 hour')`,
    [randomUUID(), account, createHash('sha256').update(token).digest('hex')]);
  return { account, token };
};

integration('the legacy checkout delegates a validated product and idempotency key', async () => {
  const calls: Array<{ account: string; product: string; key: string }> = [];
  const payments = { async checkout(_db: unknown, account: string, product: string, key: string) {
    calls.push({ account, product, key });
    return { url: 'https://checkout.stripe.test/legacy', product, key };
  } } as unknown as SandboxPayments;
  const service = createApp({ db: db!, auth: {}, payments });
  try {
    const { account, token } = await accountAndToken();
    const headers = { authorization: `Bearer ${token}`, 'content-type': 'application/json' };
    assert.equal((await service.inject({ method: 'POST', url: '/v1/checkout', headers,
      payload: { product: 'ai-10-usd' } })).statusCode, 400);
    assert.equal((await service.inject({ method: 'POST', url: '/v1/checkout',
      headers: { ...headers, 'idempotency-key': 'short' }, payload: { product: 'ai-10-usd' } })).statusCode, 400);
    assert.equal((await service.inject({ method: 'POST', url: '/v1/checkout',
      headers: { ...headers, 'idempotency-key': 'k'.repeat(12) }, payload: {} })).statusCode, 400);
    const ok = await service.inject({ method: 'POST', url: '/v1/checkout',
      headers: { ...headers, 'idempotency-key': 'k'.repeat(12) }, payload: { product: 'ai-10-usd' } });
    assert.equal(ok.statusCode, 200);
    assert.deepEqual(ok.json(), { url: 'https://checkout.stripe.test/legacy', product: 'ai-10-usd', key: 'k'.repeat(12) });
    assert.deepEqual(calls, [{ account, product: 'ai-10-usd', key: 'k'.repeat(12) }]);
  } finally { await service.close(); }

  const unconfigured = createApp({ db: db!, auth: {} });
  try {
    const { token } = await accountAndToken();
    const response = await unconfigured.inject({ method: 'POST', url: '/v1/checkout',
      headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json', 'idempotency-key': 'k'.repeat(12) },
      payload: { product: 'ai-10-usd' } });
    assert.equal(response.statusCode, 503);
    assert.equal(response.json().error?.code, 'checkout_not_configured');
  } finally { await unconfigured.close(); }
});

test('the minutes webhook lets subscriptions claim their events before the minute reconcile', async () => {
  const db = connectDatabase('postgresql://unused@127.0.0.1:1/unused');
  const reconciled: Array<{ provider: string; request: unknown }> = [];
  const commerce = {
    stripe: {} as never,
    purchases: {},
    fulfillment: { async reconcile(provider: string, request: unknown) { reconciled.push({ provider, request }); } }
  } as unknown as NonNullable<Services['minuteCommerce']>;
  const event = { type: 'invoice.paid', data: { object: { subscription: 'sub_hook' } } };
  let handled = true;
  const subscriptions = {
    transport: { verifyEvent() { return event; } },
    async handleEvent(received: unknown) { assert.equal(received, event); return handled; }
  } as unknown as Services['subscriptions'];
  const app = createApp({ db, auth: {}, minuteCommerce: commerce, subscriptions });
  try {
    const raw = JSON.stringify({ id: 'evt_hook', type: 'invoice.paid' });
    const claimed = await app.inject({ method: 'POST', url: STRIPE_MINUTES_WEBHOOK_PATH,
      headers: { 'content-type': 'application/json', 'stripe-signature': 'sig_hook' }, payload: raw });
    assert.equal(claimed.statusCode, 200);
    assert.deepEqual(claimed.json(), { received: true });
    assert.equal(reconciled.length, 0);

    handled = false;
    const fallsThrough = await app.inject({ method: 'POST', url: STRIPE_MINUTES_WEBHOOK_PATH,
      headers: { 'content-type': 'application/json', 'stripe-signature': 'sig_hook' }, payload: raw });
    assert.equal(fallsThrough.statusCode, 200);
    assert.deepEqual(fallsThrough.json(), { received: true });
    assert.equal(reconciled.length, 1);
    assert.equal(reconciled[0]!.provider, 'stripe');
    const request = reconciled[0]!.request as { kind: string; signature: string; raw: Buffer };
    assert.equal(request.kind, 'webhook');
    assert.equal(request.signature, 'sig_hook');
    assert.equal(request.raw.toString(), raw);
  } finally { await app.close(); await db.end(); }
});
