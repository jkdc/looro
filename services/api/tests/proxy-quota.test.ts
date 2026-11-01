import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { createServer } from 'node:http';
import { connectDatabase, transaction } from '../src/db.js';
import { migrate } from '../src/migrate.js';
import { conversationPolicy } from '../src/conversation-policy.js';
import { proxyQuotaConfig, runDailyQuotaGrants, setSubscription } from '../src/proxy-quota.js';
import { grantQuota, reconcileHold } from '../src/tokens-admin.js';
import { TokenProxy } from '../src/token-proxy.js';

const databaseURL = process.env.TEST_DATABASE_URL;
if (databaseURL && !new URL(databaseURL).pathname.endsWith('_test')) throw new Error('Dedicated test database required.');
const integration = (name: string, fn: () => Promise<void>) => test(name, { skip: !databaseURL && 'Set TEST_DATABASE_URL.' }, fn);

test('the free daily allowance defaults to off and validates its bounds', () => {
  assert.deepEqual(proxyQuotaConfig({}), { dailyInputTokens: 0, dailyOutputTokens: 0 });
  assert.deepEqual(proxyQuotaConfig({ PROXY_FREE_DAILY_INPUT_TOKENS: '400000', PROXY_FREE_DAILY_OUTPUT_TOKENS: '60000' }),
    { dailyInputTokens: 400000, dailyOutputTokens: 60000 });
  assert.throws(() => proxyQuotaConfig({ PROXY_FREE_DAILY_INPUT_TOKENS: '-1' }), /integer/);
  assert.throws(() => proxyQuotaConfig({ PROXY_FREE_DAILY_OUTPUT_TOKENS: 'nan' }), /integer/);
});

integration('daily quota grants are idempotent and cover subscriptions plus the free allowance', async () => {
  const schema = `quota_test_${randomUUID().replaceAll('-', '')}`, url = new URL(databaseURL!);
  url.searchParams.set('options', `-c search_path=${schema}`);
  const db = connectDatabase(url.toString()); await db.query(`CREATE SCHEMA ${schema}`); await migrate(db);
  try {
    const member = randomUUID(), subscriber = randomUUID(), guest = randomUUID();
    for (const [id, isGuest] of [[member, false], [subscriber, false], [guest, true]] as const) {
      await db.query('INSERT INTO accounts(id,is_guest) VALUES($1,$2)', [id, isGuest]);
      await db.query('INSERT INTO wallets(account_id) VALUES($1)', [id]);
    }
    // Pilot plan: 1000 in + 500 out daily. Free allowance: 100 in + 50 out.
    // grant-quota credits the subscriber's current day immediately, so the
    // sweep below only adds the free-allowance member grant.
    await grantQuota(db, { accountID: subscriber, actor: 'ops', reason: 'pilot onboarding', plan: 'pilot',
      days: 30, dailyInputTokens: '1000', dailyOutputTokens: '500' });
    const allowance = { dailyInputTokens: 100, dailyOutputTokens: 50 };

    // Seeded rate 2026-09-minimax-m ($0.30/M in, $1.20/M out):
    // subscriber 900n (from grant-quota), member 90n, guest nothing.
    const first = await runDailyQuotaGrants(db, allowance);
    assert.equal(first.granted, 1);
    const balance = async (account: string) =>
      (await db.query('SELECT balance_nano FROM wallets WHERE account_id=$1', [account])).rows[0].balance_nano;
    assert.equal(await balance(subscriber), '900');
    assert.equal(await balance(member), '90');
    assert.equal(await balance(guest), '0');
    // Same-day rerun (restart, retry): the unique ledger reference adds nothing.
    const rerun = await runDailyQuotaGrants(db, allowance);
    assert.equal(rerun.granted, 0);
    assert.equal(await balance(subscriber), '900');
    // One grant entry per account, all at the active rate version.
    const grants = (await db.query(`SELECT account_id,reference FROM ledger WHERE kind='purchase' AND reference LIKE 'subscription-grant:%' ORDER BY reference`)).rows;
    assert.equal(grants.length, 2);
    assert.ok(grants.every(row => (row.reference as string).startsWith('subscription-grant:')));

    // Policy: display-only quota state per account, model labels from the routes.
    const subscriberPolicy = await conversationPolicy(db, subscriber, allowance);
    assert.equal(subscriberPolicy.quota.limitTokens, 1500);
    assert.equal(subscriberPolicy.quota.usedTokensToday, 0);
    assert.equal(subscriberPolicy.quota.exhausted, false);
    assert.ok(subscriberPolicy.models.typed_reply);
    const memberPolicy = await conversationPolicy(db, member, allowance);
    assert.equal(memberPolicy.quota.limitTokens, 150);
  } finally { await db.query(`DROP SCHEMA ${schema} CASCADE`); await db.end(); }
});

integration('subscribers are funded even with the free daily allowance off', async () => {
  const schema = `quota_test_${randomUUID().replaceAll('-', '')}`, url = new URL(databaseURL!);
  url.searchParams.set('options', `-c search_path=${schema}`);
  const db = connectDatabase(url.toString()); await db.query(`CREATE SCHEMA ${schema}`); await migrate(db);
  try {
    const subscriber = randomUUID();
    await db.query('INSERT INTO accounts(id) VALUES($1)', [subscriber]);
    await db.query('INSERT INTO wallets(account_id) VALUES($1)', [subscriber]);
    // Default configuration: the free funnel is off. The paid plan must still
    // be funded — the sweep gate must only cover the member free sweep (B1).
    // The subscription is created with the bare upsert (no immediate grant)
    // so this test isolates the sweep behavior.
    await setSubscription(db, { accountID: subscriber, plan: 'standard', days: 30, dailyInputTokens: 1000n, dailyOutputTokens: 500n });
    const first = await runDailyQuotaGrants(db, { dailyInputTokens: 0, dailyOutputTokens: 0 });
    assert.equal(first.granted, 1);
    const balance = async () =>
      (await db.query('SELECT balance_nano FROM wallets WHERE account_id=$1', [subscriber])).rows[0].balance_nano;
    // 1000 in + 500 out = 300 + 600 nano at the seeded rate.
    assert.equal(await balance(), '900');
    // Same-day rerun stays idempotent.
    const rerun = await runDailyQuotaGrants(db, { dailyInputTokens: 0, dailyOutputTokens: 0 });
    assert.equal(rerun.granted, 0);
    assert.equal(await balance(), '900');
  } finally { await db.query(`DROP SCHEMA ${schema} CASCADE`); await db.end(); }
});

integration('a rejected grant-quota leaves plan, ledger and audit untouched', async () => {
  const schema = `quota_test_${randomUUID().replaceAll('-', '')}`, url = new URL(databaseURL!);
  url.searchParams.set('options', `-c search_path=${schema}`);
  const db = connectDatabase(url.toString()); await db.query(`CREATE SCHEMA ${schema}`); await migrate(db);
  try {
    const guest = randomUUID();
    await db.query('INSERT INTO accounts(id,is_guest) VALUES($1,true)', [guest]);
    await db.query('INSERT INTO wallets(account_id) VALUES($1)', [guest]);
    await assert.rejects(
      grantQuota(db, { accountID: guest, actor: 'ops', reason: 'must be refused', plan: 'pilot',
        days: 30, dailyInputTokens: '1000', dailyOutputTokens: '500' }),
      /campaign_grant_requires_member/,
    );
    // Validation, upsert and audit share one transaction: the refused
    // assignment persists nothing (B4).
    const plans = (await db.query('SELECT count(*)::int AS n FROM token_subscriptions')).rows[0].n;
    assert.equal(plans, 0);
    const audits = (await db.query(`SELECT count(*)::int AS n FROM token_admin_audit WHERE action='grant_quota'`)).rows[0].n;
    assert.equal(audits, 0);
  } finally { await db.query(`DROP SCHEMA ${schema} CASCADE`); await db.end(); }
});

integration('grant-quota credits the first day immediately for late signups', async () => {
  const schema = `quota_test_${randomUUID().replaceAll('-', '')}`, url = new URL(databaseURL!);
  url.searchParams.set('options', `-c search_path=${schema}`);
  const db = connectDatabase(url.toString()); await db.query(`CREATE SCHEMA ${schema}`); await migrate(db);
  try {
    const member = randomUUID();
    await db.query('INSERT INTO accounts(id) VALUES($1)', [member]);
    await db.query('INSERT INTO wallets(account_id) VALUES($1)', [member]);
    // Signup after the day's sweep ran: the assignment itself must fund the
    // current day idempotently (B2), never double-grant on a rerun.
    const applied = await grantQuota(db, { accountID: member, actor: 'ops', reason: 'late signup', plan: 'pilot',
      days: 30, dailyInputTokens: '1000', dailyOutputTokens: '500' });
    assert.equal(applied.accountID, member);
    const balance = (await db.query('SELECT balance_nano FROM wallets WHERE account_id=$1', [member])).rows[0].balance_nano;
    assert.equal(balance, '900');
    await runDailyQuotaGrants(db, { dailyInputTokens: 0, dailyOutputTokens: 0 });
    const after = (await db.query('SELECT balance_nano FROM wallets WHERE account_id=$1', [member])).rows[0].balance_nano;
    assert.equal(after, '900');
  } finally { await db.query(`DROP SCHEMA ${schema} CASCADE`); await db.end(); }
});

integration('a plan upgrade after the free grant tops up the difference under its own identity', async () => {
  const schema = `quota_test_${randomUUID().replaceAll('-', '')}`, url = new URL(databaseURL!);
  url.searchParams.set('options', `-c search_path=${schema}`);
  const db = connectDatabase(url.toString()); await db.query(`CREATE SCHEMA ${schema}`); await migrate(db);
  try {
    const member = randomUUID();
    await db.query('INSERT INTO accounts(id) VALUES($1)', [member]);
    await db.query('INSERT INTO wallets(account_id) VALUES($1)', [member]);
    // Free sweep already funded today (100/50 → 90n). Assigning a bigger plan
    // the same day must succeed by topping the difference up under a distinct
    // idempotent reference — reusing the same reference with another amount is
    // a 409 by design (audit rev.3, high).
    await runDailyQuotaGrants(db, { dailyInputTokens: 100, dailyOutputTokens: 50 });
    const applied = await grantQuota(db, { accountID: member, actor: 'ops', reason: 'upgrade to paid plan',
      plan: 'standard', days: 30, dailyInputTokens: '1000', dailyOutputTokens: '500' });
    assert.equal(applied.accountID, member);
    const balance = async () =>
      (await db.query('SELECT balance_nano FROM wallets WHERE account_id=$1', [member])).rows[0].balance_nano;
    // Total credit equals the plan allowance (900n), not 90 + 900.
    assert.equal(await balance(), '900');
    const entries = (await db.query(`SELECT reference,balance_delta_nano FROM ledger WHERE account_id=$1 AND kind='purchase' ORDER BY reference`,
      [member])).rows;
    const day = entries[0].reference.split(':')[1];
    assert.deepEqual(entries.map(row => [row.reference, BigInt(row.balance_delta_nano)]), [
      [`subscription-grant:${day}:${member}`, 90n],
      [`subscription-topup:${day}:${member}:900`, 810n],
    ]);
    // The sweep rerun is still a no-op for the standard reference.
    await runDailyQuotaGrants(db, { dailyInputTokens: 100, dailyOutputTokens: 50 });
    assert.equal(await balance(), '900');
  } finally { await db.query(`DROP SCHEMA ${schema} CASCADE`); await db.end(); }
});

integration('a plan downgrade after a bigger free grant adds nothing and never conflicts', async () => {
  const schema = `quota_test_${randomUUID().replaceAll('-', '')}`, url = new URL(databaseURL!);
  url.searchParams.set('options', `-c search_path=${schema}`);
  const db = connectDatabase(url.toString()); await db.query(`CREATE SCHEMA ${schema}`); await migrate(db);
  try {
    const member = randomUUID();
    await db.query('INSERT INTO accounts(id) VALUES($1)', [member]);
    await db.query('INSERT INTO wallets(account_id) VALUES($1)', [member]);
    await runDailyQuotaGrants(db, { dailyInputTokens: 100, dailyOutputTokens: 50 }); // 90n
    const applied = await grantQuota(db, { accountID: member, actor: 'ops', reason: 'smaller pilot', plan: 'pilot',
      days: 7, dailyInputTokens: '50', dailyOutputTokens: '25' }); // 45n < 90n
    assert.equal(applied.firstGrantNanoUSD, '0');
    const balance = (await db.query('SELECT balance_nano FROM wallets WHERE account_id=$1', [member])).rows[0].balance_nano;
    assert.equal(balance, '90'); // credit already granted is never clawed back
  } finally { await db.query(`DROP SCHEMA ${schema} CASCADE`); await db.end(); }
});

integration('sweep and assignment value the same allowance identically', async () => {
  const schema = `quota_test_${randomUUID().replaceAll('-', '')}`, url = new URL(databaseURL!);
  url.searchParams.set('options', `-c search_path=${schema}`);
  const db = connectDatabase(url.toString()); await db.query(`CREATE SCHEMA ${schema}`); await migrate(db);
  try {
    // 2 in + 2 out: per-direction truncation would give 0+2=2n, shared
    // sum-then-divide valuation gives 3n — one rounding for both paths
    // (audit rev.3, medium).
    const swept = randomUUID(), assigned = randomUUID();
    for (const id of [swept, assigned]) {
      await db.query('INSERT INTO accounts(id) VALUES($1)', [id]);
      await db.query('INSERT INTO wallets(account_id) VALUES($1)', [id]);
    }
    await setSubscription(db, { accountID: swept, plan: 'pilot', days: 1, dailyInputTokens: 2n, dailyOutputTokens: 2n });
    await runDailyQuotaGrants(db, { dailyInputTokens: 2, dailyOutputTokens: 2 });
    // The assignment for an identical allowance must reuse the swept grant
    // (same reference, same amount) instead of conflicting.
    const applied = await grantQuota(db, { accountID: swept, actor: 'ops', reason: 'same-day reassignment', plan: 'pilot',
      days: 1, dailyInputTokens: '2', dailyOutputTokens: '2' });
    assert.equal(applied.firstGrantNanoUSD, '0');
    // A fresh assignment values it identically.
    await grantQuota(db, { accountID: assigned, actor: 'ops', reason: 'fresh assignment', plan: 'pilot',
      days: 1, dailyInputTokens: '2', dailyOutputTokens: '2' });
    const balance = async (account: string) =>
      (await db.query('SELECT balance_nano FROM wallets WHERE account_id=$1', [account])).rows[0].balance_nano;
    assert.equal(await balance(swept), '3');
    assert.equal(await balance(assigned), '3');
  } finally { await db.query(`DROP SCHEMA ${schema} CASCADE`); await db.end(); }
});

integration('successive plan upgrades never conflict and accumulate to the last allowance', async () => {
  const schema = `quota_test_${randomUUID().replaceAll('-', '')}`, url = new URL(databaseURL!);
  url.searchParams.set('options', `-c search_path=${schema}`);
  const db = connectDatabase(url.toString()); await db.query(`CREATE SCHEMA ${schema}`); await migrate(db);
  try {
    const member = randomUUID();
    await db.query('INSERT INTO accounts(id) VALUES($1)', [member]);
    await db.query('INSERT INTO wallets(account_id) VALUES($1)', [member]);
    const balance = async () =>
      (await db.query('SELECT balance_nano FROM wallets WHERE account_id=$1', [member])).rows[0].balance_nano;
    // Free sweep 90n → plan 900n → plan 1800n the same day: each change tops
    // up only its pending difference, and no top-up reuses another amount's
    // idempotent identity (audit rev.4, high).
    await runDailyQuotaGrants(db, { dailyInputTokens: 100, dailyOutputTokens: 50 });   // 90n
    await grantQuota(db, { accountID: member, actor: 'ops', reason: 'plan 900', plan: 'standard',
      days: 30, dailyInputTokens: '1000', dailyOutputTokens: '500' });                  // +810 → 900
    assert.equal(await balance(), '900');
    await grantQuota(db, { accountID: member, actor: 'ops', reason: 'plan 1800', plan: 'standard',
      days: 30, dailyInputTokens: '2000', dailyOutputTokens: '1000' });                 // +900 → 1800
    assert.equal(await balance(), '1800');
    // Retrying the same assignment is a no-op.
    await grantQuota(db, { accountID: member, actor: 'ops', reason: 'plan 1800 retry', plan: 'standard',
      days: 30, dailyInputTokens: '2000', dailyOutputTokens: '1000' });
    assert.equal(await balance(), '1800');
    // A sweep rerun is a no-op too.
    await runDailyQuotaGrants(db, { dailyInputTokens: 0, dailyOutputTokens: 0 });
    assert.equal(await balance(), '1800');
  } finally { await db.query(`DROP SCHEMA ${schema} CASCADE`); await db.end(); }
});

integration('a downgrade after a bigger same-day allowance adds nothing and never conflicts', async () => {
  const schema = `quota_test_${randomUUID().replaceAll('-', '')}`, url = new URL(databaseURL!);
  url.searchParams.set('options', `-c search_path=${schema}`);
  const db = connectDatabase(url.toString()); await db.query(`CREATE SCHEMA ${schema}`); await migrate(db);
  try {
    const member = randomUUID();
    await db.query('INSERT INTO accounts(id) VALUES($1)', [member]);
    await db.query('INSERT INTO wallets(account_id) VALUES($1)', [member]);
    const balance = async () =>
      (await db.query('SELECT balance_nano FROM wallets WHERE account_id=$1', [member])).rows[0].balance_nano;
    // 90 → 900 → 450: already funded above the new plan, so nothing happens —
    // and no idempotency identity is reused with another amount (audit rev.4).
    await runDailyQuotaGrants(db, { dailyInputTokens: 100, dailyOutputTokens: 50 });   // 90n
    await grantQuota(db, { accountID: member, actor: 'ops', reason: 'plan 900', plan: 'standard',
      days: 30, dailyInputTokens: '1000', dailyOutputTokens: '500' });                  // 900n
    const applied = await grantQuota(db, { accountID: member, actor: 'ops', reason: 'downgrade to 450',
      plan: 'pilot', days: 7, dailyInputTokens: '500', dailyOutputTokens: '250' });     // 450n < 900n
    assert.equal(applied.firstGrantNanoUSD, '0');
    assert.equal(await balance(), '900');
  } finally { await db.query(`DROP SCHEMA ${schema} CASCADE`); await db.end(); }
});

integration('quota exhaustion refuses a proxied call before the provider is contacted', async () => {
  const schema = `quota_test_${randomUUID().replaceAll('-', '')}`, url = new URL(databaseURL!);
  url.searchParams.set('options', `-c search_path=${schema}`);
  const db = connectDatabase(url.toString()); await db.query(`CREATE SCHEMA ${schema}`); await migrate(db);
  try {
    const account = randomUUID();
    await db.query('INSERT INTO accounts(id) VALUES($1)', [account]);
    await db.query('INSERT INTO wallets(account_id) VALUES($1)', [account]);
    // A tiny allowance: 1 in + 1 out = 1.5 nano → 1n credit (truncating),
    // below any plausible estimate, so the proxy must refuse with 402.
    await setSubscription(db, { accountID: account, plan: 'pilot', days: 1, dailyInputTokens: 1n, dailyOutputTokens: 1n });
    await runDailyQuotaGrants(db, { dailyInputTokens: 1, dailyOutputTokens: 1 });
    const wallet = (await db.query('SELECT balance_nano FROM wallets WHERE account_id=$1', [account])).rows[0];
    assert.equal(wallet.balance_nano, '1');
    const policy = await conversationPolicy(db, account, null);
    assert.equal(policy.quota.limitTokens, 2);
    // Through the real proxy this time: the provider is never contacted.
    let calls = 0;
    const provider = createServer((_request, response) => {
      calls++;
      response.setHeader('content-type', 'application/json');
      response.end(JSON.stringify({ choices: [], usage: { prompt_tokens: 1, completion_tokens: 1 } }));
    });
    await new Promise<void>(resolve => provider.listen(0, '127.0.0.1', resolve));
    const port = (provider.address() as { port: number }).port;
    const proxy = new TokenProxy(db, { providerURL: `http://127.0.0.1:${port}/v1/chat/completions`,
      providerKey: 'k', maxTokens: 128, maxConcurrentPerAccount: 2 });
    try {
      await assert.rejects(
        proxy.relay(account, { messages: [{ role: 'user', content: 'Hi' }] }),
        (error: { code?: string; status?: number }) => error.code === 'token_balance_exhausted' && error.status === 402,
      );
      assert.equal(calls, 0);
    } finally {
      await new Promise<void>(resolve => provider.close(() => resolve()));
    }
  } finally { await db.query(`DROP SCHEMA ${schema} CASCADE`); await db.end(); }
});

integration('hold reconciliation releases only open holds and audits the decision', async () => {
  const schema = `quota_test_${randomUUID().replaceAll('-', '')}`, url = new URL(databaseURL!);
  url.searchParams.set('options', `-c search_path=${schema}`);
  const db = connectDatabase(url.toString()); await db.query(`CREATE SCHEMA ${schema}`); await migrate(db);
  try {
    const account = randomUUID();
    await db.query('INSERT INTO accounts(id) VALUES($1)', [account]);
    await db.query('INSERT INTO wallets(account_id) VALUES($1)', [account]);
    const { transaction } = await import('../src/db.js');
    const { appendEntry } = await import('../src/ledger.js');
    const { prepareProxyAttempt, markProxyDispatched, finishProxyAttempt } = await import('../src/proxy-attempts.js');
    await transaction(db, sql => appendEntry(sql, account, `seed:${account}`, 'purchase', 10_000n, 0n));
    const id = randomUUID();
    await prepareProxyAttempt(db, { id, accountID: account, rateVersionID: '2026-09-minimax-m', estimateNano: 500n });
    await markProxyDispatched(db, id);
    await finishProxyAttempt(db, id, 'unknown');
    const released = await reconcileHold(db, { attemptID: id, actor: 'ops', reason: 'provider outage confirmed, no spend' });
    assert.equal(released.releasedNano, '500');
    const wallet = (await db.query('SELECT reserved_nano,balance_nano FROM wallets WHERE account_id=$1', [account])).rows[0];
    assert.equal(wallet.reserved_nano, '0');
    assert.equal(wallet.balance_nano, '10000');
    // Audited with the new action enum.
    const audit = (await db.query(`SELECT action,reference FROM token_admin_audit WHERE action='release_hold'`)).rows;
    assert.equal(audit.length, 1);
    assert.equal(audit[0].reference, `proxy:${id}`);
    await assert.rejects(reconcileHold(db, { attemptID: id, actor: 'ops', reason: 'double release rejected' }), /proxy_hold_not_open/);
  } finally { await db.query(`DROP SCHEMA ${schema} CASCADE`); await db.end(); }
});
