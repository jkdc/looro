import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes, randomUUID } from 'node:crypto';
import Stripe from 'stripe';
import { createApp } from '../src/app.js';
import { connectDatabase, transaction } from '../src/db.js';
import { migrate } from '../src/migrate.js';
import { authenticate, deleteAccount, digest } from '../src/auth.js';
import { AuthAdmission } from '../src/auth-admission.js';
import { AIValuePurchases, PurchaseFulfillmentRouter } from '../src/ai-value-purchases.js';
import { MinutePurchases } from '../src/minute-purchases.js';
import { makePackProduct, PackPurchases, BYOK_UNLOCK_PRICE_MINOR, TOKEN_PACK_PRICE_MINOR,
  TOKEN_PACK_CREDIT_NANO } from '../src/pack-products.js';
import { MinuteReceiptVault, MinuteDeliveryWorker } from '../src/minute-provider-delivery.js';
import { StripeMinuteProvider, type StripeMinuteTransport } from '../src/stripe-minute-provider.js';
import { PlayMinuteProvider } from '../src/play-minute-provider.js';

const databaseURL = process.env.TEST_DATABASE_URL;
if (databaseURL && !new URL(databaseURL).pathname.endsWith('_test')) throw new Error('Use an isolated test database.');
const integration = (name: string, fn: () => Promise<void>) => test(name, { skip: !databaseURL && 'Set TEST_DATABASE_URL.' }, fn);
const proxy = { hmacKey: 'a'.repeat(64), proxyToken: 'b'.repeat(64), allowLocalLoopback: false };
const network = { 'x-app-client-ip': '192.0.2.118', 'x-app-proxy-token': proxy.proxyToken };
const stripeSecret = 'whsec_' + 'syntheticfixture'.repeat(3);
const stripeKey = 'sk_test_' + 'syntheticfixture'.repeat(3);
const rootURL = '/v1/minutes/orders';
const unlock = makePackProduct({ provider: 'stripe', environment: 'test', merchant: 'acct_httpfixture', sku: 'fixture-unlock',
  providerProduct: 'price_http_unlock', currency: 'usd', totalMinor: BYOK_UNLOCK_PRICE_MINOR, entitlementKind: 'byok_unlock', creditNanoUSD: '0' });
const pack = makePackProduct({ provider: 'stripe', environment: 'test', merchant: 'acct_httpfixture', sku: 'fixture-pack',
  providerProduct: 'price_http_pack', currency: 'usd', totalMinor: TOKEN_PACK_PRICE_MINOR, entitlementKind: 'token_pack',
  creditNanoUSD: TOKEN_PACK_CREDIT_NANO.toString() });
const playUnlock = { ...unlock, provider: 'play' as const, merchant: 'com.jkdc.app.httpfixture', providerProduct: 'http_unlock' };
const playPack = { ...pack, provider: 'play' as const, merchant: 'com.jkdc.app.httpfixture', providerProduct: 'http_pack' };
const prices: Record<string, number> = { [unlock.providerProduct]: BYOK_UNLOCK_PRICE_MINOR, [pack.providerProduct]: TOKEN_PACK_PRICE_MINOR };
const clone = <T,>(value: T): T => structuredClone(value);
async function fixture(options: { salesEnabled?: boolean; packEnabled?: boolean } = {}) {
  const schema = `pack_http_${randomUUID().replaceAll('-', '')}`, url = new URL(databaseURL!);
  url.searchParams.set('options', `-c search_path=${schema}`);
  const db = connectDatabase(url.toString()); await db.query(`CREATE SCHEMA ${schema}`); await migrate(db);
  const vault = new MinuteReceiptVault(db, 'test-key', new Map([['test-key', randomBytes(32)]]));
  const sessions = new Map<string, any>(), refunded = new Map<string, number>();
  const stripeSDK = new Stripe(stripeKey);
  const amountOf = (id: string) => prices[[...sessions.values()].find(s => s.id === id)?.line_price ?? ''] ?? 0;
  let createCount = 0, playFacts: any, consumeCalls = 0;
  const transport: StripeMinuteTransport = {
    account: async () => ({ id: 'acct_httpfixture' }),
    price: async id => ({ id, active: true, livemode: false, currency: 'usd', unit_amount: prices[id] ?? 0, type: 'one_time' }) as any,
    create: async params => {
      createCount++; const orderID = params.client_reference_id!, priceID = params.line_items![0]!.price as string, amount = prices[priceID]!;
      const id = `cs_test_${orderID.replaceAll('-', '')}`;
      const existing = sessions.get(id); if (existing) return clone(existing);
      const session = { id, status: 'open', mode: 'payment', livemode: false, payment_status: 'unpaid', line_price: priceID,
        client_reference_id: orderID, metadata: { app_minute_order: orderID }, currency: 'usd', amount_total: amount,
        payment_intent: `pi_${orderID.replaceAll('-', '')}`, url: `https://checkout.stripe.com/c/pay/${id}` };
      sessions.set(id, session); return clone(session) as any;
    },
    session: async id => { const session = sessions.get(id); if (!session) throw new Error('Unknown fixture session'); return clone(session); },
    lines: async id => { const session = sessions.get(id); return { data: [{ quantity: 1, price: { id: session.line_price },
      currency: 'usd', amount_total: session.amount_total }], has_more: false } as any; },
    sessionsForIntent: async id => ({ data: [...sessions.values()].filter(s => s.payment_intent === id).map(clone), has_more: false }) as any,
    intent: async id => { const session = [...sessions.values()].find(s => s.payment_intent === id)!; return { id, livemode: false,
      status: 'succeeded', currency: 'usd', amount_received: session.amount_total, metadata: session.metadata, latest_charge: `ch_${id.slice(3)}` } as any; },
    charge: async id => { const session = [...sessions.values()].find(s => s.payment_intent === `pi_${id.slice(3)}`)!; return { id, status: 'succeeded',
      livemode: false, paid: true, captured: true, amount: session.amount_total, currency: 'usd', payment_intent: `pi_${id.slice(3)}`, disputed: false } as any; },
    refunds: async intent => ({ data: refunded.get(intent) ? [{ id: `re_${intent.slice(3)}`, payment_intent: intent, currency: 'usd',
      status: 'succeeded', amount: refunded.get(intent) }] : [], has_more: false }) as any,
    verifyEvent: (raw, signature) => stripeSDK.webhooks.constructEvent(raw, signature, stripeSecret),
  };
  const stripe = new StripeMinuteProvider(db, vault, { secretKey: stripeKey, webhookSecret: stripeSecret, accountID: 'acct_httpfixture',
    webOrigin: 'https://app.example.test', checkoutEnabled: true }, transport);
  const playTotals: Record<string, { units: string; nanos: number }> = {
    [playUnlock.providerProduct]: { units: '6', nanos: 990_000_000 }, [playPack.providerProduct]: { units: '4', nanos: 990_000_000 } };
  const play = new PlayMinuteProvider(db, vault, { packageName: 'com.jkdc.app.httpfixture', bindingKey: Buffer.alloc(32, 8),
    currencyExponents: { usd: 2 }, purchasesEnabled: true }, {
    purchase: async () => clone(playFacts),
    order: async (_merchant: string, orderId: string) => ({ orderId, purchaseToken: playFacts.token, state: 'PROCESSED',
      total: { currencyCode: 'USD', ...playTotals[playFacts.productLineItem[0].productId] },
      lineItems: [{ productId: playFacts.productLineItem[0].productId, total: { currencyCode: 'USD', ...playTotals[playFacts.productLineItem[0].productId] },
        oneTimePurchaseDetails: { quantity: 1 } }], orderHistory: {} }),
    consume: async () => { consumeCalls++; playFacts.productLineItem[0].productOfferDetails.consumptionState = 'CONSUMPTION_STATE_CONSUMED'; },
    voided: async () => ({}),
  });
  const catalog = [unlock, pack, playUnlock, playPack];
  const purchases = new MinutePurchases(db, { salesEnabled: false, verifiers: [stripe, play] });
  const aiPurchases = new AIValuePurchases(db, { verifiers: [stripe, play], salesEnabled: false });
  const packPurchases = new PackPurchases(db, { catalog, verifiers: [stripe, play], salesEnabled: options.packEnabled ?? true });
  const fulfillment = new PurchaseFulfillmentRouter(db, purchases, aiPurchases, [stripe, play], packPurchases);
  const app = createApp({ db, auth: { googleClientID: 'synthetic-google-client' }, accounts: { admission: new AuthAdmission(db, proxy) },
    minuteCommerce: { purchases, aiPurchases, packPurchases, fulfillment, stripe, play } });
  return { db, app, packPurchases, fulfillment, stripe, play, vault, sessions, refunded,
    get createCount() { return createCount; }, get consumeCalls() { return consumeCalls; },
    async account(guest = false) {
      const id = randomUUID(), token = randomBytes(32).toString('base64url');
      await transaction(db, async sql => {
        await sql.query('INSERT INTO accounts(id,email,is_guest) VALUES($1,$2,$3)', [id, 'synthetic@example.test', guest]);
        await sql.query('INSERT INTO wallets(account_id) VALUES($1)', [id]);
        await sql.query("INSERT INTO identities(provider,subject,account_id) VALUES('google',$1,$2)", [randomUUID(), id]);
        await sql.query("INSERT INTO auth_sessions(id,account_id,token_hash,expires_at) VALUES($1,$2,$3,now()+interval '1 hour')", [randomUUID(), id, digest(token)]);
      });
      return { id, token, headers: { ...network, authorization: `Bearer ${token}` } };
    },
    async order(account: { headers: Record<string,string> }, sku: string, provider: 'stripe' | 'play' = 'stripe', key: string = randomUUID()) {
      const response = await app.inject({ method: 'POST', url: rootURL, headers: { ...account.headers, 'idempotency-key': key }, payload: { provider, sku } });
      assert.equal(response.statusCode, 200, response.body); return response.json();
    },
    stripeEvent(orderID: string, state: 'paid' | 'pending' | 'expired' = 'paid', refund = 0) {
      const session = [...sessions.values()].find(s => s.client_reference_id === orderID)!;
      session.status = state === 'paid' ? 'complete' : state === 'expired' ? 'expired' : 'open';
      session.payment_status = state === 'paid' ? 'paid' : 'unpaid';
      if (refund) refunded.set(session.payment_intent, refund);
      const payload = JSON.stringify({ id: `evt_${randomUUID().replaceAll('-', '')}`, object: 'event', type: state === 'expired' ? 'checkout.session.expired' : 'checkout.session.completed',
        livemode: false, account: 'acct_httpfixture', data: { object: { id: session.id } } }, null, 2);
      return { payload, signature: stripeSDK.webhooks.generateTestHeaderString({ payload, secret: stripeSecret }) };
    },
    async webhook(event: { payload: string; signature: string }) {
      return app.inject({ method: 'POST', url: '/v1/webhooks/stripe/minutes', headers: { ...network, 'content-type': 'application/json', 'stripe-signature': event.signature }, payload: event.payload });
    },
    bindPlay(order: any, product: { providerProduct: string }) {
      const token = `synthetic-http-token.${randomUUID()}`;
      playFacts = { token, orderId: 'GPA.1234-5678-9012-34567', testPurchaseContext: { fopType: 'TEST' },
        purchaseStateContext: { purchaseState: 'PURCHASED' }, obfuscatedExternalAccountId: order.payment.obfuscatedAccountID,
        obfuscatedExternalProfileId: order.payment.obfuscatedProfileID,
        productLineItem: [{ productId: product.providerProduct, productOfferDetails: { quantity: 1, refundableQuantity: 1, consumptionState: 'CONSUMPTION_STATE_YET_TO_BE_CONSUMED' } }] };
      return token;
    },
    async drain() { return new MinuteDeliveryWorker(db, fulfillment, [stripe, play]).runBatch(); },
    async cleanup() { await app.close(); await db.query(`DROP SCHEMA ${schema} CASCADE`); await db.end(); },
  };
}

integration('pack products are publicly advertised through the established catalog endpoint', async () => {
  const f = await fixture();
  try {
    const catalog = await f.app.inject({ url: '/v1/minutes/products?provider=stripe', headers: network });
    assert.deepEqual(catalog.json(), { available: true, billingBasis: 'prepaid-service-credit', products: [
      { sku: 'fixture-unlock', providerProduct: 'price_http_unlock', currency: 'usd', totalMinor: 699, entitlementKind: 'byok_unlock', creditNanoUSD: '0', environment: 'test' },
      { sku: 'fixture-pack', providerProduct: 'price_http_pack', currency: 'usd', totalMinor: 499, entitlementKind: 'token_pack', creditNanoUSD: '2160000000', environment: 'test' }] });
    const play = await f.app.inject({ url: '/v1/minutes/products?provider=play', headers: network });
    assert.equal(play.json().products.map((product: any) => product.providerProduct).join(','), 'http_unlock,http_pack');
    assert.equal(catalog.body.includes('acct_httpfixture'), false);
  } finally { await f.cleanup(); }
});

integration('disabled pack sales hide the catalog but a configured catalog still routes order errors', async () => {
  const f = await fixture({ packEnabled: false });
  try {
    const account = await f.account();
    const catalog = await f.app.inject({ url: '/v1/minutes/products?provider=stripe', headers: network });
    assert.deepEqual(catalog.json(), { available: false, billingBasis: 'actual-ai-usage', products: [] });
    const response = await f.app.inject({ method: 'POST', url: rootURL, headers: { ...account.headers, 'idempotency-key': randomUUID() },
      payload: { provider: 'stripe', sku: 'fixture-pack' } });
    assert.equal(response.statusCode, 503); assert.equal(response.json().error.code, 'pack_purchases_unavailable');
    assert.equal((await f.db.query('SELECT count(*) FROM minute_purchase_orders')).rows[0].count, '0');
  } finally { await f.cleanup(); }
});

integration('Stripe webhook delivers the unlock once, replays idempotently and refunds clear it', async () => {
  const f = await fixture();
  try {
    const account = await f.account(), other = await f.account(), order = await f.order(account, 'fixture-unlock');
    assert.equal(order.entitlementKind, 'byok_unlock'); assert.equal(order.creditNanoUSD, '0'); assert.equal(order.totalMinor, 699);
    assert.equal((await f.app.inject({ url: `${rootURL}/${order.orderID}`, headers: other.headers })).statusCode, 404);
    const event = f.stripeEvent(order.orderID);
    assert.equal((await f.webhook(event)).statusCode, 200);
    assert.equal((await f.webhook(event)).statusCode, 200);
    const status = (await f.app.inject({ url: `${rootURL}/${order.orderID}`, headers: account.headers })).json();
    assert.equal(status.state, 'purchased'); assert.equal(status.fulfillmentRecorded, true);
    assert.equal(typeof status.unlockedAt, 'string'); assert.equal(status.grantedNanoUSD, '0');
    const entitlement = (await f.db.query('SELECT unlock_source,unlock_order_id,unlocked_at FROM entitlements WHERE account_id=$1', [account.id])).rows[0];
    assert.equal(entitlement.unlock_source, 'stripe'); assert.equal(entitlement.unlock_order_id, order.orderID);
    assert.equal((await f.db.query('SELECT count(*) FROM entitlements')).rows[0].count, '1');
    const refund = f.stripeEvent(order.orderID, 'paid', 699);
    assert.equal((await f.webhook(refund)).statusCode, 200);
    assert.equal((await f.webhook(refund)).statusCode, 200);
    const refundedStatus = (await f.app.inject({ url: `${rootURL}/${order.orderID}`, headers: account.headers })).json();
    assert.equal(refundedStatus.state, 'purchased'); assert.equal(refundedStatus.fulfillmentRecorded, false); assert.equal(refundedStatus.unlockedAt, null);
    assert.equal((await f.db.query('SELECT unlocked_at FROM entitlements WHERE account_id=$1', [account.id])).rows[0].unlocked_at, null);
    assert.equal((await f.db.query('SELECT count(*) FROM ledger')).rows[0].count, '0');
    // A fully refunded unlock never traps the account: deletion resolves after refund.
    const result = await deleteAccount(f.db, account.id);
    assert.equal(result.retainedFinancialRecords, true);
    await assert.rejects(authenticate(f.db, account.headers.authorization), { code: 'sign_in_required' });
  } finally { await f.cleanup(); }
});

integration('Stripe webhook credits the token pack once with the pinned rate version and refunds reverse it', async () => {
  const f = await fixture();
  try {
    const account = await f.account(), order = await f.order(account, 'fixture-pack');
    assert.equal(order.entitlementKind, 'token_pack'); assert.equal(order.creditNanoUSD, '2160000000');
    // Pending checkout never credits and blocks deletion while the charge is unresolved.
    await assert.rejects(deleteAccount(f.db, account.id), { code: 'unresolved_billing', status: 409 });
    await f.webhook(f.stripeEvent(order.orderID, 'pending'));
    assert.equal((await f.db.query('SELECT balance_nano FROM wallets WHERE account_id=$1', [account.id])).rows[0].balance_nano, '0');
    const event = f.stripeEvent(order.orderID);
    assert.equal((await f.webhook(event)).statusCode, 200);
    assert.equal((await f.webhook(event)).statusCode, 200);
    const wallet = (await f.db.query('SELECT balance_nano,sandbox_balance_nano FROM wallets WHERE account_id=$1', [account.id])).rows[0];
    assert.equal(wallet.balance_nano, '2160000000');
    // A test receipt is sandbox provenance: it cannot pay for public conversations.
    assert.equal(wallet.sandbox_balance_nano, '2160000000');
    const ledger = (await f.db.query('SELECT kind,balance_delta_nano,rate_version FROM ledger WHERE account_id=$1 ORDER BY created_at, id', [account.id])).rows;
    assert.deepEqual(ledger, [{ kind: 'purchase', balance_delta_nano: '2160000000', rate_version: '2026-09-minimax-m' }]);
    assert.equal((await f.db.query("SELECT count(*) FROM minute_entries WHERE account_id=$1", [account.id])).rows[0].count, '0');
    assert.equal((await f.webhook(f.stripeEvent(order.orderID, 'paid', 499))).statusCode, 200);
    const refunded = (await f.db.query('SELECT balance_nano,sandbox_balance_nano FROM wallets WHERE account_id=$1', [account.id])).rows[0];
    assert.equal(refunded.balance_nano, '0'); assert.equal(refunded.sandbox_balance_nano, '0');
    const reversal = (await f.db.query("SELECT kind,balance_delta_nano,rate_version FROM ledger WHERE account_id=$1 AND kind='reversal'", [account.id])).rows[0];
    assert.equal(reversal.balance_delta_nano, '-2160000000'); assert.equal(reversal.rate_version, '2026-09-minimax-m');
    const result = await deleteAccount(f.db, account.id);
    assert.equal(result.retainedFinancialRecords, true);
  } finally { await f.cleanup(); }
});

integration('Play delivers the non-consumable unlock without consumption and the consumable pack with it', async () => {
  const f = await fixture();
  try {
    const account = await f.account(), unlockOrder = await f.order(account, 'fixture-unlock', 'play'), token = f.bindPlay(unlockOrder, playUnlock);
    for (let count = 0; count < 2; count++) {
      const result = await f.app.inject({ method: 'POST', url: `${rootURL}/${unlockOrder.orderID}/play`, headers: account.headers, payload: { purchaseToken: token } });
      assert.equal(result.statusCode, 200, result.body); assert.equal(result.json().entitlementKind, 'byok_unlock');
      assert.equal(result.json().fulfillmentRecorded, true);
    }
    await f.drain();
    assert.equal(f.consumeCalls, 0); // The unlock is a non-consumable one-time product.
    assert.ok((await f.db.query('SELECT unlocked_at FROM entitlements WHERE account_id=$1', [account.id])).rows[0].unlocked_at);
    assert.equal((await f.db.query('SELECT count(*) FROM ledger')).rows[0].count, '0');
    const packAccount = await f.account(), packOrder = await f.order(packAccount, 'fixture-pack', 'play'), packToken = f.bindPlay(packOrder, playPack);
    const result = await f.app.inject({ method: 'POST', url: `${rootURL}/${packOrder.orderID}/play`, headers: packAccount.headers, payload: { purchaseToken: packToken } });
    assert.equal(result.statusCode, 200, result.body); assert.equal(result.json().entitlementKind, 'token_pack');
    assert.equal(result.json().grantedNanoUSD, '2160000000');
    await f.drain();
    assert.equal(f.consumeCalls, 1); // The token pack is consumable.
    const wallet = (await f.db.query('SELECT balance_nano,sandbox_balance_nano FROM wallets WHERE account_id=$1', [packAccount.id])).rows[0];
    assert.equal(wallet.balance_nano, '2160000000'); assert.equal(wallet.sandbox_balance_nano, '2160000000');
    assert.equal((await f.db.query('SELECT unlocked_at FROM entitlements WHERE account_id=$1', [packAccount.id])).rowCount, 0);
    const recovery = await f.app.inject({ method: 'POST', url: '/v1/minutes/play/recover', headers: packAccount.headers, payload: { purchaseToken: packToken } });
    assert.equal(recovery.statusCode, 200); assert.equal(recovery.json().orderID, packOrder.orderID);
    assert.equal((await f.db.query('SELECT count(*) FROM ledger WHERE account_id=$1', [packAccount.id])).rows[0].count, '1');
  } finally { await f.cleanup(); }
});
