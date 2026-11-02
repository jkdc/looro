import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { randomUUID } from 'node:crypto';
import { connectDatabase, type Database } from '../src/db.js';
import { migrate } from '../src/migrate.js';
import { ServiceError } from '../src/errors.js';
import { grantUnlock, revokeUnlock, grantBalance, tokenAccountSummary } from '../src/tokens-admin.js';
import { TokenProxy } from '../src/token-proxy.js';
import { createApp } from '../src/app.js';
import { AuthAdmission } from '../src/auth-admission.js';
import { bearerHash } from '../src/auth.js';

const databaseURL = process.env.TEST_DATABASE_URL;
if (databaseURL && !new URL(databaseURL).pathname.endsWith('_test')) throw new Error('Dedicated test database required.');
const integration = (name: string, fn: () => Promise<void>) => test(name, { skip: !databaseURL && 'Set TEST_DATABASE_URL.' }, fn);

const ACTOR = 'operator:test', REASON = 'campaign pilot';
const CONFIG = { maxTokens: 128, maxConcurrentPerAccount: 2, maxInFlight: 10, timeoutMs: 30000, maxResponseBytes: 1048576 };

async function withSchema(fn: (db: Database, account: string) => Promise<void>) {
  const schema = `token_admin_test_${randomUUID().replaceAll('-', '')}`, url = new URL(databaseURL!);
  url.searchParams.set('options', `-c search_path=${schema}`);
  const db = connectDatabase(url.toString());
  await db.query(`CREATE SCHEMA ${schema}`); await migrate(db);
  const account = randomUUID();
  await db.query('INSERT INTO accounts(id) VALUES($1)', [account]);
  await db.query('INSERT INTO wallets(account_id) VALUES($1)', [account]);
  try { await fn(db, account); } finally { await db.end(); }
}
const member = (db: Database) => randomUUID();
async function paidUnlock(db: Database, account: string, source: 'stripe' | 'play' = 'stripe') {
  await db.query('INSERT INTO accounts(id) VALUES($1)', [account]);
  await db.query('INSERT INTO wallets(account_id) VALUES($1)', [account]);
  await db.query(`INSERT INTO entitlements(account_id,unlocked_at,unlock_source) VALUES($1,now(),$2)`, [account, source]);
}

integration('grant-unlock assigns the campaign unlock once, idempotently, without overwriting a paid unlock', async () => {
  await withSchema(async (db, account) => {
    const first = await grantUnlock(db, { accountID: account, actor: ACTOR, reason: REASON });
    assert.deepEqual(first, { accountID: account, unlocked: true, changed: true, unlockSource: 'campaign' });
    const row = (await db.query('SELECT unlocked_at,unlock_source,unlock_order_id FROM entitlements WHERE account_id=$1', [account])).rows[0];
    assert.ok(row.unlocked_at); assert.equal(row.unlock_source, 'campaign'); assert.equal(row.unlock_order_id, null);
    assert.equal((await db.query('SELECT count(*)::int AS n FROM token_admin_audit WHERE account_id=$1', [account])).rows[0].n, 1);
    // A replay is an honest no-op: no second audit row, nothing overwritten.
    const replay = await grantUnlock(db, { accountID: account, actor: ACTOR, reason: REASON });
    assert.deepEqual(replay, { accountID: account, unlocked: true, changed: false, unlockSource: 'campaign' });
    assert.equal((await db.query('SELECT count(*)::int AS n FROM token_admin_audit WHERE account_id=$1', [account])).rows[0].n, 1);
    // A paid unlock is never overwritten by a campaign grant.
    const paid = member(db);
    await paidUnlock(db, paid, 'stripe');
    const untouched = await grantUnlock(db, { accountID: paid, actor: ACTOR, reason: REASON });
    assert.deepEqual(untouched, { accountID: paid, unlocked: true, changed: false, unlockSource: 'stripe' });
    assert.equal((await db.query('SELECT unlock_source FROM entitlements WHERE account_id=$1', [paid])).rows[0].unlock_source, 'stripe');
  });
});

integration('campaign grants refuse guest accounts, deleted accounts and unknown accounts', async () => {
  await withSchema(async (db, account) => {
    await db.query('UPDATE accounts SET is_guest=true WHERE id=$1', [account]);
    await assert.rejects(grantUnlock(db, { accountID: account, actor: ACTOR, reason: REASON }),
      (error: ServiceError) => error.code === 'campaign_grant_requires_member' && error.status === 403);
    await assert.rejects(grantBalance(db, { accountID: account, grantID: 'g1', creditNanoUSD: '1000', actor: ACTOR, reason: REASON }),
      (error: ServiceError) => error.code === 'campaign_grant_requires_member' && error.status === 403);
    await assert.rejects(grantUnlock(db, { accountID: randomUUID(), actor: ACTOR, reason: REASON }),
      (error: ServiceError) => error.code === 'account_not_found' && error.status === 404);
    await db.query('UPDATE accounts SET is_guest=false, deleted_at=now() WHERE id=$1', [account]);
    await assert.rejects(grantUnlock(db, { accountID: account, actor: ACTOR, reason: REASON }),
      (error: ServiceError) => error.code === 'account_not_found' && error.status === 404);
  });
});

integration('revoke-unlock clears only the campaign unlock', async () => {
  await withSchema(async (db, account) => {
    await assert.rejects(revokeUnlock(db, { accountID: account, actor: ACTOR, reason: REASON }),
      (error: ServiceError) => error.code === 'campaign_unlock_not_found' && error.status === 404);
    await grantUnlock(db, { accountID: account, actor: ACTOR, reason: REASON });
    const revoked = await revokeUnlock(db, { accountID: account, actor: ACTOR, reason: REASON });
    assert.deepEqual(revoked, { accountID: account, unlocked: false, changed: true });
    const row = (await db.query('SELECT unlocked_at,unlock_source FROM entitlements WHERE account_id=$1', [account])).rows[0];
    assert.equal(row.unlocked_at, null); assert.equal(row.unlock_source, null);
    // A paid unlock is untouchable from the CLI: only its refund machinery clears it.
    const paid = member(db);
    await paidUnlock(db, paid, 'play');
    await assert.rejects(revokeUnlock(db, { accountID: paid, actor: ACTOR, reason: REASON }),
      (error: ServiceError) => error.code === 'campaign_unlock_not_found');
    assert.ok((await db.query('SELECT unlocked_at FROM entitlements WHERE account_id=$1', [paid])).rows[0].unlocked_at);
    // A cleared row can be re-granted, mirroring the purchase path.
    const regrant = await grantUnlock(db, { accountID: account, actor: ACTOR, reason: 'second campaign' });
    assert.equal(regrant.changed, true);
    assert.equal(regrant.unlockSource, 'campaign');
  });
});

integration('grant-balance credits the wallet through the ledger, idempotently, and rejects conflicting reuse', async () => {
  await withSchema(async (db, account) => {
    const first = await grantBalance(db, { accountID: account, grantID: 'pilot-1', creditNanoUSD: '2160000000', actor: ACTOR, reason: REASON });
    assert.equal(first.applied, true);
    assert.equal(first.reference, 'campaign-grant:pilot-1');
    let wallet = (await db.query('SELECT balance_nano,sandbox_balance_nano FROM wallets WHERE account_id=$1', [account])).rows[0];
    assert.equal(BigInt(wallet.balance_nano), 2_160_000_000n);
    assert.equal(BigInt(wallet.sandbox_balance_nano), 0n);
    const entry = (await db.query('SELECT kind,balance_delta_nano,rate_version FROM ledger WHERE account_id=$1 AND reference=$2',
      [account, 'campaign-grant:pilot-1'])).rows[0];
    assert.equal(entry.kind, 'purchase');
    assert.equal(BigInt(entry.balance_delta_nano), 2_160_000_000n);
    assert.equal(entry.rate_version, '2026-09-minimax-m');
    assert.equal((await db.query(`SELECT count(*)::int AS n FROM token_admin_audit WHERE account_id=$1 AND action='grant_balance'`,
      [account])).rows[0].n, 1);
    // Replay: no double credit, no duplicate audit.
    const replay = await grantBalance(db, { accountID: account, grantID: 'pilot-1', creditNanoUSD: '2160000000', actor: ACTOR, reason: REASON });
    assert.equal(replay.applied, false);
    wallet = (await db.query('SELECT balance_nano FROM wallets WHERE account_id=$1', [account])).rows[0];
    assert.equal(BigInt(wallet.balance_nano), 2_160_000_000n);
    assert.equal((await db.query('SELECT count(*)::int AS n FROM token_admin_audit WHERE account_id=$1', [account])).rows[0].n, 1);
    // The same grantID with a different amount or account conflicts.
    await assert.rejects(grantBalance(db, { accountID: account, grantID: 'pilot-1', creditNanoUSD: '1', actor: ACTOR, reason: REASON }),
      (error: ServiceError) => error.code === 'idempotency_conflict' && error.status === 409);
    // Malformed amounts and grant ids never reach the ledger.
    for (const creditNanoUSD of ['0', '-5', '12.5', 'abc', '', '1000000000000001'])
      await assert.rejects(grantBalance(db, { accountID: account, grantID: 'shape-check', creditNanoUSD, actor: ACTOR, reason: REASON }),
        (error: ServiceError) => error.code === 'invalid_token_grant');
    await assert.rejects(grantBalance(db, { accountID: account, grantID: 'bad id!', creditNanoUSD: '1000', actor: ACTOR, reason: REASON }),
      (error: ServiceError) => error.code === 'invalid_token_grant');
    // A deleted account is refused before any credit.
    await db.query('UPDATE accounts SET deleted_at=now() WHERE id=$1', [account]);
    await assert.rejects(grantBalance(db, { accountID: account, grantID: 'pilot-2', creditNanoUSD: '1000', actor: ACTOR, reason: REASON }),
      (error: ServiceError) => error.code === 'account_not_found' && error.status === 404);
  });
});

integration('tokenAccountSummary reports wallet, unlock and 30-day usage', async () => {
  await withSchema(async (db, account) => {
    await grantUnlock(db, { accountID: account, actor: ACTOR, reason: REASON });
    await grantBalance(db, { accountID: account, grantID: 'sum-1', creditNanoUSD: '5000000000', actor: ACTOR, reason: REASON });
    await db.query(`INSERT INTO proxy_usage(id,account_id,rate_version,input_tokens,output_tokens,estimate_nano,cost_nano,settled_nano,ledger_reference)
      VALUES(gen_random_uuid(),$1,'2026-09-minimax-m',1000,2000,5000,600,600,NULL)`, [account]);
    const summary = await tokenAccountSummary(db, account);
    assert.equal(summary.accountID, account);
    assert.equal(summary.deleted, false); assert.equal(summary.isGuest, false);
    assert.equal(summary.wallet?.balanceNanoUSD, '5000000000');
    assert.equal(summary.entitlement.unlocked, true); assert.equal(summary.entitlement.unlockSource, 'campaign');
    assert.equal(summary.usage30d.calls, 1);
    assert.equal(summary.usage30d.inputTokens, 1000);
    assert.equal(summary.usage30d.settledNanoUSD, '600');
  });
});

integration('assigned unlock and balance open the HTTP proxy route end to end', async () => {
  await withSchema(async (db, account) => {
    let calls = 0;
    const provider = createServer((_request, response) => {
      calls++;
      response.setHeader('content-type', 'application/json');
      response.end(JSON.stringify({ choices: [{ message: { role: 'assistant', content: 'Hola' } }],
        usage: { prompt_tokens: 1_000_000, completion_tokens: 1_000_000 } }));
    });
    await new Promise<void>(resolve => provider.listen(0, '127.0.0.1', resolve));
    const port = (provider.address() as { port: number }).port;
    const admission = { hmacKey: 'c'.repeat(64), proxyToken: 'd'.repeat(64), allowLocalLoopback: true };
    const app = createApp({ db, auth: { googleClientID: 'tokens-admin-test' },
      accounts: { admission: new AuthAdmission(db, admission) },
      tokenProxy: new TokenProxy(db, CONFIG, async () =>
        ({ provider: 'openai', baseUrl: `http://127.0.0.1:${port}`, model: 'test-model', apiKey: 'server-key' })) });
    try {
      const bearer = 'a'.repeat(43);
      await db.query(`INSERT INTO auth_sessions(id,account_id,token_hash,expires_at)
        VALUES(gen_random_uuid(),$1,$2, now() + interval '1 hour')`, [account, bearerHash(`Bearer ${bearer}`)]);
      const headers = { authorization: `Bearer ${bearer}`, 'x-app-client-ip': '192.0.2.10', 'x-app-proxy-token': admission.proxyToken };
      const call = () => app.inject({ method: 'POST', url: '/v1/proxy/responses', headers,
        payload: { model: 'MiniMax-M2', stream: false, messages: [{ role: 'user', content: 'Hi' }] } });
      // Locked and unfunded: the unlock gate fires first, before any provider call.
      let response = await call();
      assert.equal(response.statusCode, 403); assert.equal(response.json().error.code, 'unlock_required');
      // Assigned via campaign: the unlock passes but the zero balance refuses with 402.
      await grantUnlock(db, { accountID: account, actor: ACTOR, reason: REASON });
      response = await call();
      assert.equal(response.statusCode, 402); assert.equal(response.json().error.code, 'token_balance_exhausted');
      assert.equal(calls, 0);
      const grant = await grantBalance(db, { accountID: account, grantID: 'e2e-1', creditNanoUSD: '2000000000', actor: ACTOR, reason: REASON });
      assert.equal(grant.applied, true);
      response = await call();
      assert.equal(response.statusCode, 200);
      assert.equal((response.json().metering as { nanoUSDSettled: string }).nanoUSDSettled, '1500000');
      assert.equal(calls, 1);
      // The wallet now advertises the campaign unlock next to the debited balance.
      const wallet = await app.inject({ method: 'GET', url: '/v1/wallet', headers });
      assert.equal(wallet.statusCode, 200);
      assert.equal(wallet.json().unlocked, true);
      assert.equal(wallet.json().unlockSource, 'campaign');
      assert.equal(BigInt(wallet.json().availableNanoUSD), 1_998_500_000n);
      assert.equal((await db.query('SELECT count(*)::int AS n FROM proxy_usage WHERE account_id=$1', [account])).rows[0].n, 1);
      assert.equal((await db.query(`SELECT count(*)::int AS n FROM token_admin_audit WHERE account_id=$1`, [account])).rows[0].n, 2);
    } finally { await app.close(); await new Promise<void>(resolve => provider.close(() => resolve())); }
  });
});
