import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createApp } from '../src/app.js';
import { connectDatabase, type Database } from '../src/db.js';
import { SandboxPayments, STRIPE_WEBHOOK_PATH } from '../src/payments.js';
import { AuthAdmission } from '../src/auth-admission.js';
import { STRIPE_MINUTES_WEBHOOK_PATH, STRIPE_WEBHOOK_BODY_LIMIT } from '../src/stripe-minute-provider.js';

test('unconfigured trial and hosted voice fail closed without database or provider access', async () => {
  const db = connectDatabase('postgresql://unused@127.0.0.1:1/unused');
  const app = createApp({ db, auth: {} });
  try {
    for (const url of ['/v1/trial/eligibility', '/v1/live/sessions']) {
      const response = await app.inject({ method: 'POST', url, payload: { deviceID: 'self-claimed', remainingSeconds: 600 } });
      assert.equal(response.statusCode, 503);
    }
    const wallet = await app.inject({ method: 'GET', url: '/v1/wallet' });
    assert.equal(wallet.statusCode, 503);
    const health = (await app.inject({ method: 'GET', url: '/healthz' })).json();
    assert.equal(health.hostedVoice, false); assert.equal(health.livePayments, false);
  } finally { await app.close(); await db.end(); }
});
test('Stripe signature validates the original bytes and rejects mutation, old timestamps and live events', () => {
  const payments = new SandboxPayments('sk_test_example_not_a_real_key', 'whsec_example_not_a_real_secret', new Map(), 'http://localhost:8080');
  const raw = Buffer.from(JSON.stringify({ id: 'evt_test', livemode: false, type: 'checkout.session.completed', data: { object: { id: 'cs_test' } } }));
  const signature = payments.stripe.webhooks.generateTestHeaderString({ payload: raw.toString(), secret: payments.webhookSecret });
  assert.equal(payments.verify(raw, signature).id, 'evt_test');
  assert.throws(() => payments.verify(Buffer.from(raw.toString() + ' '), signature));
  const expired = payments.stripe.webhooks.generateTestHeaderString({ payload: raw.toString(), secret: payments.webhookSecret, timestamp: 1 });
  assert.throws(() => payments.verify(raw, expired));
  const live = Buffer.from(raw.toString().replace('"livemode":false', '"livemode":true'));
  const liveSignature = payments.stripe.webhooks.generateTestHeaderString({ payload: live.toString(), secret: payments.webhookSecret });
  assert.throws(() => payments.verify(live, liveSignature));
  assert.throws(() => new SandboxPayments('sk_live_never_allowed', 'whsec_test', new Map(), 'https://example.test'));
});
test('webhook route verifies raw bytes before doing any database work', async () => {
  const db = connectDatabase('postgresql://unused@127.0.0.1:1/unused');
  const payments = new SandboxPayments('sk_test_example_not_a_real_key', 'whsec_example_not_a_real_secret', new Map(), 'http://localhost:8080');
  const app = createApp({ db, auth: {}, payments });
  try {
    const response = await app.inject({ method: 'POST', url: '/v1/webhooks/stripe', payload: { id: 'untrusted', privateData: 'must-not-be-echoed' }, headers: { 'stripe-signature': 'invalid' } });
    assert.equal(response.statusCode, 400);
    assert.deepEqual(response.json(), { error: { code: 'invalid_webhook_signature' } });
    assert.equal(response.body.includes('privateData'), false);
  } finally { await app.close(); await db.end(); }
});
test('webhooks and public routes answer without proxy headers while client routes stay gated and limited', async () => {
  const db = { query: async (text: string) => {
    if (text.startsWith('SELECT 1')) return { rows: [{ ok: 1 }], rowCount: 1 };
    if (text.includes('ai_pricing_policy')) return { rows: [{ version: 1, service_fee_basis_points: 1500 }], rowCount: 1 };
    throw new Error('Unexpected database query');
  },
  // Public reads without an authenticated identity share the durable global
  // admission cap; this fixture always reports a window below its maximum.
  connect: async () => ({ query: async (text: string) => {
    if (text.includes('auth_rate_limits')) return { rows: [{ hits: 1 }], rowCount: 1 };
    if (text.startsWith('SELECT now()')) return { rows: [{ now: new Date() }], rowCount: 1 };
    if (['BEGIN', 'COMMIT', 'ROLLBACK'].includes(text.trim())) return { rows: [], rowCount: 0 };
    throw new Error('Unexpected admission query');
  }, release: () => {} }) } as unknown as Database;
  const proxy = { hmacKey: 'a'.repeat(64), proxyToken: 'b'.repeat(64), allowLocalLoopback: false };
  const payments = new SandboxPayments('sk_test_example_not_a_real_key', 'whsec_example_not_a_real_secret', new Map(), 'http://localhost:8080');
  const app = createApp({ db, auth: {}, payments, accounts: { admission: new AuthAdmission(db, proxy) } });
  try {
    // Stripe delivers webhooks server-to-server, without trusted-proxy headers: commerce
    // availability and signature checks decide, never the proxy gate.
    const minutes = await app.inject({ method: 'POST', url: STRIPE_MINUTES_WEBHOOK_PATH, payload: {} });
    assert.equal(minutes.statusCode, 503); assert.equal(minutes.json().error.code, 'minute_purchases_unavailable');
    const legacy = await app.inject({ method: 'POST', url: STRIPE_WEBHOOK_PATH, payload: { id: 'untrusted' }, headers: { 'stripe-signature': 'invalid' } });
    assert.equal(legacy.statusCode, 400); assert.equal(legacy.json().error.code, 'invalid_webhook_signature');
    // Public catalog, pricing, readiness and the payment return page are reachable without proxy headers.
    assert.equal((await app.inject({ url: '/v1/minutes/products?provider=stripe' })).statusCode, 200);
    const pricing = await app.inject({ url: '/v1/pricing' });
    assert.equal(pricing.statusCode, 200); assert.equal(pricing.json().serviceFeePercent, 15);
    const ready = await app.inject({ url: '/readyz' });
    assert.equal(ready.statusCode, 200); assert.equal(ready.json().database, true);
    assert.equal((await app.inject({ url: '/payment-return' })).statusCode, 200);
    // Client routes without proxy headers still fail closed...
    const gated = await app.inject({ url: '/v1/live/capabilities' });
    assert.equal(gated.statusCode, 503); assert.equal(gated.json().error.code, 'trusted_proxy_required');
    // ...and keep their shared per-network limiter bucket at 120 requests per minute.
    const trusted = { 'x-app-client-ip': '192.0.2.7', 'x-app-proxy-token': proxy.proxyToken };
    for (let count = 0; count < 120; count++) {
      const response = await app.inject({ url: '/v1/live/capabilities', headers: trusted });
      assert.equal(response.statusCode, 200);
    }
    const limited = await app.inject({ url: '/v1/live/capabilities', headers: trusted });
    assert.equal(limited.statusCode, 429); assert.equal(limited.json().error.code, 'rate_limit');
    // Webhook delivery never consumes a limiter bucket, so a client 429 storm cannot delay payments.
    for (let count = 0; count < 150; count++) {
      const response = await app.inject({ method: 'POST', url: STRIPE_MINUTES_WEBHOOK_PATH, payload: {} });
      assert.equal(response.statusCode, 503); assert.equal(response.json().error.code, 'minute_purchases_unavailable');
    }
    // Public reads stay open through a storm: without an authenticated network
    // identity they share only the durable global cap, never a per-address bucket.
    for (let count = 0; count < 150; count++) {
      const response = await app.inject({ url: '/v1/pricing' });
      assert.equal(response.statusCode, 200);
    }
    assert.equal((await app.inject({ url: '/readyz' })).statusCode, 200);
    assert.equal((await app.inject({ url: '/v1/minutes/products?provider=stripe' })).statusCode, 200);
    assert.equal((await app.inject({ url: '/payment-return' })).statusCode, 200);
  } finally { await app.close(); }
});
test('the webhook routes admit payloads up to the provider signature ceiling instead of failing transport first', async () => {
  const db = { query: async () => { throw new Error('Unexpected database query'); } } as unknown as Database;
  const app = createApp({ db, auth: {} });
  try {
    for (const size of [STRIPE_WEBHOOK_BODY_LIMIT - 1, STRIPE_WEBHOOK_BODY_LIMIT]) {
      const response = await app.inject({ method: 'POST', url: STRIPE_MINUTES_WEBHOOK_PATH,
        headers: { 'content-type': 'application/json' }, payload: 'x'.repeat(size) });
      assert.equal(response.statusCode, 503); assert.equal(response.json().error.code, 'minute_purchases_unavailable');
    }
    const oversized = await app.inject({ method: 'POST', url: STRIPE_MINUTES_WEBHOOK_PATH,
      headers: { 'content-type': 'application/json' }, payload: 'x'.repeat(STRIPE_WEBHOOK_BODY_LIMIT + 1) });
    assert.equal(oversized.statusCode, 413);
  } finally { await app.close(); }
});
