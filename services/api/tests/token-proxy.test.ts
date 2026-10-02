import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { randomUUID } from 'node:crypto';
import { connectDatabase } from '../src/db.js';
import { migrate } from '../src/migrate.js';
import { appendEntry } from '../src/ledger.js';
import { transaction } from '../src/db.js';
import { ServiceError } from '../src/errors.js';
import { TokenProxy, tokenProxyConfig } from '../src/token-proxy.js';

const databaseURL = process.env.TEST_DATABASE_URL;
if (databaseURL && !new URL(databaseURL).pathname.endsWith('_test')) throw new Error('Dedicated test database required.');
const integration = (name: string, fn: () => Promise<void>) => test(name, { skip: !databaseURL && 'Set TEST_DATABASE_URL.' }, fn);

const CONFIG = { providerURL: 'http://provider.internal/v1/chat/completions', providerKey: 'server-key', maxTokens: 128 };

integration('tokenProxyConfig requires both URL and key', async () => {
  assert.equal(tokenProxyConfig({}), undefined);
  assert.throws(() => tokenProxyConfig({ PROXY_PROVIDER_URL: 'not a url', PROXY_PROVIDER_KEY: 'k' }), /absolute URL/);
  assert.equal(tokenProxyConfig({ PROXY_PROVIDER_URL: 'http://p/v1', PROXY_PROVIDER_KEY: 'k' })?.providerKey, 'k');
});

integration('relay meters provider usage at the active rate version and debits the wallet', async () => {
  const schema = `token_test_${randomUUID().replaceAll('-', '')}`, url = new URL(databaseURL!);
  url.searchParams.set('options', `-c search_path=${schema}`);
  const db = connectDatabase(url.toString()); await db.query(`CREATE SCHEMA ${schema}`); await migrate(db);
  const account = randomUUID();
  await db.query('INSERT INTO accounts(id) VALUES($1)', [account]);
  await db.query('INSERT INTO wallets(account_id) VALUES($1)', [account]);
  await transaction(db, sql => appendEntry(sql, account, `seed:${account}`, 'purchase', 10_000_000n, 0n));

  let calls = 0;
  const provider = createServer((request, response) => {
    calls++;
    response.setHeader('content-type', 'application/json');
    // First call: 1M input + 1M output tokens = 1_500_000 nano at the seeded rate.
    // Second call: 30M input + 30M output = 45_000_000 nano — beyond the remaining balance.
    const usage = calls === 1 ? { prompt_tokens: 1_000_000, completion_tokens: 1_000_000 }
      : { prompt_tokens: 30_000_000, completion_tokens: 30_000_000 };
    response.end(JSON.stringify({ choices: [{ message: { role: 'assistant', content: 'Hello!' } }], usage }));
  });
  await new Promise<void>(resolve => provider.listen(0, '127.0.0.1', resolve));
  const port = (provider.address() as { port: number }).port;
  const proxy = new TokenProxy(db, { ...CONFIG, providerURL: `http://127.0.0.1:${port}/v1/chat/completions` });

  const result = await proxy.relay(account, { messages: [{ role: 'user', content: 'Hi' }] });
  const metering = result.payload.metering as { rateVersion: string; nanoUSDSettled: string };
  assert.equal(result.status, 200);
  assert.equal(metering.rateVersion, '2026-09-minimax-m');
  assert.equal(metering.nanoUSDSettled, '1500000');
  const balance = (await db.query('SELECT balance_nano FROM wallets WHERE account_id=$1', [account])).rows[0];
  assert.equal(BigInt(balance.balance_nano), 8_500_000n);
  // ledger.id is a random UUID: order chronologically, never by id.
  const ledger = (await db.query('SELECT kind,balance_delta_nano FROM ledger WHERE account_id=$1 ORDER BY created_at, id', [account])).rows;
  assert.equal(ledger.at(-1).kind, 'settle');
  assert.equal(BigInt(ledger.at(-1).balance_delta_nano), -1_500_000n);

  // Balance floor: a call costing more than the balance settles at the balance (bounded grace), never negative.
  const second = await proxy.relay(account, { messages: [{ role: 'user', content: 'Hi again' }] });
  const secondMetering = second.payload.metering as { nanoUSDSettled: string };
  assert.equal(secondMetering.nanoUSDSettled, '8500000');
  const after = (await db.query('SELECT balance_nano FROM wallets WHERE account_id=$1', [account])).rows[0];
  assert.equal(BigInt(after.balance_nano), 0n);

  // Zero balance refuses the next call before the provider is called.
  const before = calls;
  await assert.rejects(proxy.relay(account, { messages: [] }), /token_balance_exhausted/);
  assert.equal(calls, before);
  await new Promise<void>(resolve => provider.close(() => resolve()));
});

integration('relay rejects streaming requests before admission or the provider call', async () => {
  const schema = `token_test_${randomUUID().replaceAll('-', '')}`, url = new URL(databaseURL!);
  url.searchParams.set('options', `-c search_path=${schema}`);
  const db = connectDatabase(url.toString()); await db.query(`CREATE SCHEMA ${schema}`); await migrate(db);
  const account = randomUUID();
  await db.query('INSERT INTO accounts(id) VALUES($1)', [account]);
  await db.query('INSERT INTO wallets(account_id, balance_nano) VALUES($1,$2)', [account, '10000000']);
  // No provider server is started on purpose: the v1 contract refuses
  // `stream: true` before admission, so a provider must never be contacted.
  const proxy = new TokenProxy(db, { ...CONFIG, providerURL: 'http://127.0.0.1:9/v1/chat/completions' });
  await assert.rejects(
    proxy.relay(account, { model: 'stub', stream: true, messages: [{ role: 'user', content: 'Hi' }] }),
    (error: ServiceError) => error.code === 'streaming_unsupported_v1' && error.status === 400,
  );
  const wallet = (await db.query('SELECT balance_nano FROM wallets WHERE account_id=$1', [account])).rows[0];
  assert.equal(BigInt(wallet.balance_nano), 10_000_000n);
});

integration('estimate refuses calls the balance cannot plausibly cover', async () => {
  const schema = `token_test_${randomUUID().replaceAll('-', '')}`, url = new URL(databaseURL!);
  url.searchParams.set('options', `-c search_path=${schema}`);
  const db = connectDatabase(url.toString()); await db.query(`CREATE SCHEMA ${schema}`); await migrate(db);
  const account = randomUUID();
  await db.query('INSERT INTO accounts(id) VALUES($1)', [account]);
  await db.query('INSERT INTO wallets(account_id, balance_nano) VALUES($1,$2)', [account, '100']);
  const proxy = new TokenProxy(db, CONFIG);
  await assert.rejects(
    proxy.relay(account, { messages: [{ role: 'user', content: 'x'.repeat(4000) }] }),
    /token_balance_exhausted/,
  );
});
