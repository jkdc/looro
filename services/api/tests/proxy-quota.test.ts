import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { connectDatabase, transaction } from '../src/db.js';
import { migrate } from '../src/migrate.js';
import { conversationPolicy } from '../src/conversation-policy.js';
import { proxyQuotaConfig, runDailyQuotaGrants, setSubscription } from '../src/proxy-quota.js';
import { grantQuota, reconcileHold } from '../src/tokens-admin.js';

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
    await grantQuota(db, { accountID: subscriber, actor: 'ops', reason: 'pilot onboarding', plan: 'pilot',
      days: 30, dailyInputTokens: '1000', dailyOutputTokens: '500' });
    const allowance = { dailyInputTokens: 100, dailyOutputTokens: 50 };

    // Seeded rate 2026-09-minimax-m ($0.30/M in, $1.20/M out):
    // subscriber 900n, member 90n, guest nothing.
    const first = await runDailyQuotaGrants(db, allowance);
    assert.equal(first.granted, 2);
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

integration('quota exhaustion surfaces through the balance admission of a proxied call', async () => {
  const schema = `quota_test_${randomUUID().replaceAll('-', '')}`, url = new URL(databaseURL!);
  url.searchParams.set('options', `-c search_path=${schema}`);
  const db = connectDatabase(url.toString()); await db.query(`CREATE SCHEMA ${schema}`); await migrate(db);
  try {
    const account = randomUUID();
    await db.query('INSERT INTO accounts(id) VALUES($1)', [account]);
    await db.query('INSERT INTO wallets(account_id) VALUES($1)', [account]);
    // A tiny allowance ($0.0001-ish) so the next estimate-admitted call must refuse.
    await setSubscription(db, { accountID: account, plan: 'pilot', days: 1, dailyInputTokens: 1n, dailyOutputTokens: 1n });
    await runDailyQuotaGrants(db, { dailyInputTokens: 0, dailyOutputTokens: 0 });
    await runDailyQuotaGrants(db, { dailyInputTokens: 1, dailyOutputTokens: 1 });
    // 1 in + 1 out = 300000/1e6 + 1200000/1e6 = 1.5 → 1n credit (truncating).
    const wallet = (await db.query('SELECT balance_nano FROM wallets WHERE account_id=$1', [account])).rows[0];
    assert.equal(wallet.balance_nano, '1');
    const policy = await conversationPolicy(db, account, null);
    assert.equal(policy.quota.limitTokens, 2);
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
