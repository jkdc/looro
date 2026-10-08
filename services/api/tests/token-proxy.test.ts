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
import { timeseries } from '../src/admin-metrics.js';

const databaseURL = process.env.TEST_DATABASE_URL;
if (databaseURL && !new URL(databaseURL).pathname.endsWith('_test')) throw new Error('Dedicated test database required.');
const integration = (name: string, fn: () => Promise<void>) => test(name, { skip: !databaseURL && 'Set TEST_DATABASE_URL.' }, fn);

const CONFIG = { providerURL: 'http://provider.internal/v1/chat/completions', providerKey: 'server-key', maxTokens: 128, maxConcurrentPerAccount: 2 };

integration('tokenProxyConfig requires both URL and key', async () => {
  assert.equal(tokenProxyConfig({}), undefined);
  assert.throws(() => tokenProxyConfig({ PROXY_PROVIDER_URL: 'not a url', PROXY_PROVIDER_KEY: 'k' }), /absolute URL/);
  assert.equal(tokenProxyConfig({ PROXY_PROVIDER_URL: 'http://p/v1', PROXY_PROVIDER_KEY: 'k' })?.providerKey, 'k');
  // Concurrency cap knob: defaults to 2, must be a positive integer when set.
  assert.equal(tokenProxyConfig({ PROXY_PROVIDER_URL: 'http://p/v1', PROXY_PROVIDER_KEY: 'k' })?.maxConcurrentPerAccount, 2);
  assert.throws(() => tokenProxyConfig({ PROXY_PROVIDER_URL: 'http://p/v1', PROXY_PROVIDER_KEY: 'k', PROXY_MAX_CONCURRENT: '0' }), /positive integer/);
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
  const ledger = (await db.query('SELECT kind,balance_delta_nano,reference FROM ledger WHERE account_id=$1 ORDER BY created_at, id', [account])).rows;
  assert.equal(ledger.at(-1).kind, 'settle');
  assert.equal(BigInt(ledger.at(-1).balance_delta_nano), -1_500_000n);
  // Durable usage record: the token counts, the admission estimate and the
  // settled cost, tied to the settle ledger entry.
  const usageRows = (await db.query(`SELECT input_tokens,output_tokens,estimate_nano,cost_nano,settled_nano,ledger_reference
    FROM proxy_usage WHERE account_id=$1 ORDER BY created_at, id`, [account])).rows;
  assert.equal(usageRows.length, 1);
  assert.equal(BigInt(usageRows[0].input_tokens), 1_000_000n);
  assert.equal(BigInt(usageRows[0].output_tokens), 1_000_000n);
  assert.equal(BigInt(usageRows[0].cost_nano), 1_500_000n);
  assert.equal(BigInt(usageRows[0].settled_nano), 1_500_000n);
  assert.equal(BigInt(usageRows[0].estimate_nano) > 0n, true);
  assert.equal(usageRows[0].ledger_reference, ledger.at(-1).reference);

  // Balance floor: a call costing more than the balance settles at the balance (bounded grace), never negative.
  const second = await proxy.relay(account, { messages: [{ role: 'user', content: 'Hi again' }] });
  const secondMetering = second.payload.metering as { nanoUSDSettled: string };
  assert.equal(secondMetering.nanoUSDSettled, '8500000');
  const after = (await db.query('SELECT balance_nano FROM wallets WHERE account_id=$1', [account])).rows[0];
  assert.equal(BigInt(after.balance_nano), 0n);
  const grace = (await db.query(`SELECT cost_nano,settled_nano,ledger_reference FROM proxy_usage WHERE account_id=$1
    ORDER BY created_at, id`, [account])).rows[1];
  assert.equal(BigInt(grace.cost_nano), 45_000_000n);
  assert.equal(BigInt(grace.settled_nano), 8_500_000n);
  assert.ok(grace.ledger_reference);

  // Zero balance refuses the next call before the provider is called.
  const before = calls;
  await assert.rejects(proxy.relay(account, { messages: [] }), /token_balance_exhausted/);
  assert.equal(calls, before);
  assert.equal((await db.query('SELECT count(*)::int AS n FROM proxy_usage WHERE account_id=$1', [account])).rows[0].n, 2);
  await new Promise<void>(resolve => provider.close(() => resolve()));
});

integration('a zero-cost usage report records usage without a ledger entry', async () => {
  const schema = `token_test_${randomUUID().replaceAll('-', '')}`, url = new URL(databaseURL!);
  url.searchParams.set('options', `-c search_path=${schema}`);
  const db = connectDatabase(url.toString()); await db.query(`CREATE SCHEMA ${schema}`); await migrate(db);
  const account = randomUUID();
  await db.query('INSERT INTO accounts(id) VALUES($1)', [account]);
  await db.query('INSERT INTO wallets(account_id) VALUES($1)', [account]);
  await transaction(db, sql => appendEntry(sql, account, `seed:${account}`, 'purchase', 10_000_000n, 0n));
  const provider = createServer((_request, response) => {
    response.setHeader('content-type', 'application/json');
    response.end(JSON.stringify({ choices: [{ message: { role: 'assistant', content: '' } }], usage: { prompt_tokens: 0, completion_tokens: 0 } }));
  });
  await new Promise<void>(resolve => provider.listen(0, '127.0.0.1', resolve));
  const port = (provider.address() as { port: number }).port;
  const proxy = new TokenProxy(db, { ...CONFIG, providerURL: `http://127.0.0.1:${port}/v1/chat/completions` });
  try {
    const result = await proxy.relay(account, { messages: [{ role: 'user', content: 'Hi' }] });
    assert.equal(result.status, 200);
    assert.equal((result.payload.metering as { nanoUSDSettled: string }).nanoUSDSettled, '0');
    const wallet = (await db.query('SELECT balance_nano FROM wallets WHERE account_id=$1', [account])).rows[0];
    assert.equal(BigInt(wallet.balance_nano), 10_000_000n);
    // The ledger only carries the seed purchase: no settle entry for a zero cost.
    const ledger = (await db.query('SELECT kind FROM ledger WHERE account_id=$1', [account])).rows;
    assert.equal(ledger.length, 1); assert.equal(ledger[0].kind, 'purchase');
    const usage = (await db.query('SELECT input_tokens,output_tokens,settled_nano,ledger_reference FROM proxy_usage WHERE account_id=$1', [account])).rows[0];
    assert.equal(BigInt(usage.input_tokens), 0n); assert.equal(BigInt(usage.output_tokens), 0n);
    assert.equal(BigInt(usage.settled_nano), 0n); assert.equal(usage.ledger_reference, null);
  } finally { await new Promise<void>(resolve => provider.close(() => resolve())); }
});

integration('a success reply whose usage cannot be metered is refused with nothing debited or recorded', async () => {
  const schema = `token_test_${randomUUID().replaceAll('-', '')}`, url = new URL(databaseURL!);
  url.searchParams.set('options', `-c search_path=${schema}`);
  const db = connectDatabase(url.toString()); await db.query(`CREATE SCHEMA ${schema}`); await migrate(db);
  const account = randomUUID();
  await db.query('INSERT INTO accounts(id) VALUES($1)', [account]);
  await db.query('INSERT INTO wallets(account_id) VALUES($1)', [account]);
  await transaction(db, sql => appendEntry(sql, account, `seed:${account}`, 'purchase', 10_000_000n, 0n));
  // Each relay gets the current response body; the test walks the malformed
  // usage shapes a misbehaving or hostile provider could produce.
  const bodies: unknown[] = [
    { choices: [] }, // usage missing entirely
    { choices: [], usage: null },
    { choices: [], usage: '12 tokens' },
    { choices: [], usage: [] },
    { choices: [], usage: { completion_tokens: 5 } }, // prompt count missing
    { choices: [], usage: { prompt_tokens: '12', completion_tokens: 5 } }, // string counts
    { choices: [], usage: { prompt_tokens: -1, completion_tokens: 5 } }, // negative
    { choices: [], usage: { prompt_tokens: 1.5, completion_tokens: 5 } }, // fractional
    { choices: [], usage: { prompt_tokens: 1e21, completion_tokens: 5 } }, // beyond the safe range
  ];
  let call = 0;
  const provider = createServer((_request, response) => {
    response.setHeader('content-type', 'application/json');
    response.end(JSON.stringify(bodies[call++]));
  });
  await new Promise<void>(resolve => provider.listen(0, '127.0.0.1', resolve));
  const port = (provider.address() as { port: number }).port;
  const proxy = new TokenProxy(db, { ...CONFIG, providerURL: `http://127.0.0.1:${port}/v1/chat/completions` });
  try {
    for (let index = 0; index < bodies.length; index++) {
      await assert.rejects(
        proxy.relay(account, { messages: [{ role: 'user', content: 'Hi' }] }),
        (error: ServiceError) => error.code === 'provider_error' && error.status === 502,
      );
    }
    const wallet = (await db.query('SELECT balance_nano FROM wallets WHERE account_id=$1', [account])).rows[0];
    assert.equal(BigInt(wallet.balance_nano), 10_000_000n);
    assert.equal((await db.query('SELECT count(*)::int AS n FROM ledger WHERE account_id=$1 AND kind=\'settle\'', [account])).rows[0].n, 0);
    assert.equal((await db.query('SELECT count(*)::int AS n FROM proxy_usage WHERE account_id=$1', [account])).rows[0].n, 0);
  } finally { await new Promise<void>(resolve => provider.close(() => resolve())); }
});

integration('relay rejects streaming requests before wallet admission, the provider call, or any debit', async () => {
  const schema = `token_test_${randomUUID().replaceAll('-', '')}`, url = new URL(databaseURL!);
  url.searchParams.set('options', `-c search_path=${schema}`);
  const db = connectDatabase(url.toString()); await db.query(`CREATE SCHEMA ${schema}`); await migrate(db);
  const account = randomUUID();
  await db.query('INSERT INTO accounts(id) VALUES($1)', [account]);
  await db.query('INSERT INTO wallets(account_id, balance_nano) VALUES($1,$2)', [account, '10000000']);
  // No provider server is started on purpose: the v1 contract refuses
  // `stream: true` before wallet admission, so a provider must never be
  // contacted.
  const proxy = new TokenProxy(db, { ...CONFIG, providerURL: 'http://127.0.0.1:9/v1/chat/completions' });
  await assert.rejects(
    proxy.relay(account, { model: 'stub', stream: true, messages: [{ role: 'user', content: 'Hi' }] }),
    (error: ServiceError) => error.code === 'streaming_unsupported_v1' && error.status === 400,
  );
  const wallet = (await db.query('SELECT balance_nano FROM wallets WHERE account_id=$1', [account])).rows[0];
  assert.equal(BigInt(wallet.balance_nano), 10_000_000n);
  // Ordering pin: with a zero balance the stream gate still wins — the caller
  // sees streaming_unsupported_v1 (400), never token_balance_exhausted (402).
  await db.query('UPDATE wallets SET balance_nano=0 WHERE account_id=$1', [account]);
  await assert.rejects(
    proxy.relay(account, { model: 'stub', stream: true, messages: [{ role: 'user', content: 'Hi' }] }),
    (error: ServiceError) => error.code === 'streaming_unsupported_v1' && error.status === 400,
  );
});

integration('per-account cap refuses a second concurrent call for one account while another proceeds, and frees on settle', async () => {
  const schema = `token_test_${randomUUID().replaceAll('-', '')}`, url = new URL(databaseURL!);
  url.searchParams.set('options', `-c search_path=${schema}`);
  const db = connectDatabase(url.toString()); await db.query(`CREATE SCHEMA ${schema}`); await migrate(db);
  const accountA = randomUUID(), accountB = randomUUID();
  const accounts = [accountA, accountB];
  for (const account of accounts) {
    await db.query('INSERT INTO accounts(id) VALUES($1)', [account]);
    await db.query('INSERT INTO wallets(account_id, balance_nano) VALUES($1,$2)', [account, '10000000']);
  }

  let calls = 0, release!: () => void, holdReached!: () => void;
  const gate = new Promise<void>(resolve => { release = resolve; });
  const reached = new Promise<void>(resolve => { holdReached = resolve; });
  const provider = createServer((request, response) => {
    calls++;
    let raw = '';
    request.on('data', chunk => { raw += chunk; });
    request.on('end', async () => {
      const held = (JSON.parse(raw) as { messages?: { content?: string }[] }).messages?.[0]?.content === 'hold';
      response.setHeader('content-type', 'application/json');
      const usage = { prompt_tokens: 1_000_000, completion_tokens: 1_000_000 };
      if (!held) { response.end(JSON.stringify({ choices: [], usage })); return; }
      // Hold the response open until the test releases it: the request has
      // reached the provider, so relay() has long taken the account's slot.
      holdReached();
      await gate;
      response.end(JSON.stringify({ choices: [], usage }));
    });
  });
  await new Promise<void>(resolve => provider.listen(0, '127.0.0.1', resolve));
  const port = (provider.address() as { port: number }).port;
  const proxy = new TokenProxy(db, { ...CONFIG, maxConcurrentPerAccount: 1, providerURL: `http://127.0.0.1:${port}/v1/chat/completions` });

  const held = proxy.relay(accountA, { messages: [{ role: 'user', content: 'hold' }] });
  await reached;
  // Same account at the cap: refused 429 account_busy before any wallet or
  // provider work (the provider call count does not move).
  await assert.rejects(
    proxy.relay(accountA, { messages: [{ role: 'user', content: 'quick' }] }),
    (error: ServiceError) => error.code === 'account_busy' && error.status === 429,
  );
  assert.equal(calls, 1);
  // A different account is unaffected by the first account's in-flight call.
  assert.equal((await proxy.relay(accountB, { messages: [{ role: 'user', content: 'quick' }] })).status, 200);
  assert.equal(calls, 2);
  // Settling the held call frees the slot: the next same-account call succeeds.
  release();
  assert.equal((await held).status, 200);
  assert.equal((await proxy.relay(accountA, { messages: [{ role: 'user', content: 'quick' }] })).status, 200);
  assert.equal(calls, 3);
  const paid = async (account: string) =>
    BigInt((await db.query('SELECT balance_nano FROM wallets WHERE account_id=$1', [account])).rows[0].balance_nano);
  assert.equal(await paid(accountA), 7_000_000n); // held + quick, 1_500_000 each
  assert.equal(await paid(accountB), 8_500_000n);
  await new Promise<void>(resolve => provider.close(() => resolve()));
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

integration('the tokens timeseries set aggregates daily calls, token counts and settled nanoUSD', async () => {
  const schema = `token_test_${randomUUID().replaceAll('-', '')}`, url = new URL(databaseURL!);
  url.searchParams.set('options', `-c search_path=${schema}`);
  const db = connectDatabase(url.toString()); await db.query(`CREATE SCHEMA ${schema}`); await migrate(db);
  // Expectations share the series' clock: the day window is anchored on the
  // database clock, never on the JS runner clock.
  const pgLastDays = async (count: number) => (await db.query(
    'SELECT to_char(d, \'YYYY-MM-DD\') AS day FROM generate_series(now()::date - ($1::int - 1), now()::date, interval \'1 day\') d',
    [count])).rows.map(row => row.day as string);
  const account = randomUUID();
  await db.query('INSERT INTO accounts(id) VALUES($1)', [account]);
  for (const [daysAgo, input, output, settled] of [[0, 1000, 2000, 3000], [0, 10, 20, 30], [1, 500, 600, 700]] as const) {
    await db.query(`INSERT INTO proxy_usage(id,account_id,rate_version,input_tokens,output_tokens,estimate_nano,cost_nano,settled_nano,created_at)
      VALUES(gen_random_uuid(),$1,'2026-09-minimax-m',$2,$3,0,$4,$5, now() - ($6::int * interval '1 day'))`,
    [account, input, output, input + output, settled, daysAgo]);
  }
  const series = await timeseries(db, 'tokens', 3);
  try {
    assert.equal(series.set, 'tokens');
    assert.equal(series.series.length, 4);
    const calls = series.series.find(entry => entry.name === 'proxy_calls')!;
    assert.deepEqual(calls.points.map(point => point.day), await pgLastDays(3));
    assert.equal(calls.points.at(-1)!.value, 2);
    assert.equal(calls.points.at(-2)!.value, 1);
    const inputTokens = series.series.find(entry => entry.name === 'input_tokens')!;
    assert.equal(inputTokens.points.at(-1)!.value, 1010);
    const settled = series.series.find(entry => entry.name === 'settled_nano')!;
    assert.equal(settled.points.at(-1)!.value, 3030);
    assert.equal(settled.points.at(-2)!.value, 700);
    assert.equal(settled.points.at(-3)!.value, 0);
  } finally { await db.end(); }
});
