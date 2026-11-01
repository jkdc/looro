import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { connectDatabase, type Database } from '../src/db.js';
import { migrate } from '../src/migrate.js';
import { runDripForAccount, upsertSubscriptionFromStore } from '../src/subscriptions.js';
import { minuteBalance } from '../src/minutes.js';

const databaseURL = process.env.TEST_DATABASE_URL;
if (databaseURL && !new URL(databaseURL).pathname.endsWith('_test')) throw new Error('Dedicated test database required.');
const january = new Date('2026-01-31T23:59:59.999Z');
const february = new Date('2026-02-01T00:00:00.000Z');

async function fixture(run: (db: Database) => Promise<void>) {
  const schema = `drip_fast_${randomUUID().replaceAll('-', '')}`;
  const url = new URL(databaseURL!);
  url.searchParams.set('options', `-c search_path=${schema}`);
  const db = connectDatabase(url.toString());
  try {
    await db.query(`CREATE SCHEMA ${schema}`);
    await migrate(db);
    await run(db);
  } finally {
    await db.query(`DROP SCHEMA ${schema} CASCADE`);
    await db.end();
  }
}
const integration = (name: string, fn: (db: Database) => Promise<void>) =>
  test(name, { skip: !databaseURL && 'Dedicated PostgreSQL required.' }, () => fixture(fn));

async function account(db: Database, guest = false) {
  const id = randomUUID();
  await db.query('INSERT INTO accounts(id,is_guest) VALUES($1,$2)', [id, guest]);
  return id;
}
async function snapshot(db: Database, id: string) {
  return {
    balance: await minuteBalance(db, id),
    grants: (await db.query('SELECT id,period_key,minutes FROM subscription_grants WHERE account_id=$1 ORDER BY period_key', [id])).rows,
    entries: (await db.query('SELECT reference,balance_delta_ms,reserved_delta_ms FROM minute_entries WHERE account_id=$1 ORDER BY reference', [id])).rows,
  };
}

/** Observe the real explicit transaction acquisition; Pool.query remains bound
 * to its real pool, so its internal acquisition is not mistaken for BEGIN. */
function observeTransactions(db: Database) {
  let acquisitions = 0;
  const observed = new Proxy(db, {
    get(target, property) {
      if (property === 'connect') return async () => { acquisitions++; return target.connect(); };
      const value = Reflect.get(target, property, target);
      return typeof value === 'function' ? value.bind(target) : value;
    },
  });
  return { db: observed, acquisitions: () => acquisitions };
}

for (const [label, enabled, minutes] of [['disabled', false, 5], ['zero allowance', true, 0]] as const) {
  integration(`${label} free drip does not open a no-op transaction or change persisted credit`, async db => {
    const id = await account(db);
    await db.query('UPDATE subscription_config SET free_drip_enabled=$1,free_drip_minutes=$2', [enabled, minutes]);
    const before = await snapshot(db, id);
    const observed = observeTransactions(db);
    assert.equal(await runDripForAccount(observed.db, id, january), 0);
    assert.deepEqual(await snapshot(db, id), before);
    assert.equal(observed.acquisitions(), 0, 'No transaction should be acquired for a disabled/zero grant.');
  });
}

integration('an existing monthly free grant avoids a repeated transaction without changing credit', async db => {
  const id = await account(db);
  assert.equal(await runDripForAccount(db, id, january), 5);
  const before = await snapshot(db, id);
  const observed = observeTransactions(db);
  assert.equal(await runDripForAccount(observed.db, id, january), 0);
  assert.deepEqual(await snapshot(db, id), before);
  assert.equal(observed.acquisitions(), 0, 'Already claimed free minutes must not repeat a transaction.');
});

integration('concurrent initial grants remain unique and UTC rollover still grants a new period', async db => {
  const id = await account(db);
  const results = await Promise.all([runDripForAccount(db, id, january), runDripForAccount(db, id, january)]);
  assert.deepEqual(results.sort(), [0, 5]);
  assert.equal((await minuteBalance(db, id)).availableMilliseconds, 300000);
  assert.equal((await snapshot(db, id)).grants.length, 1);
  assert.equal((await snapshot(db, id)).entries.length, 1);
  assert.equal(await runDripForAccount(db, id, february), 5);
  assert.equal((await minuteBalance(db, id)).availableMilliseconds, 600000);
  assert.equal((await snapshot(db, id)).grants.length, 2);
  assert.equal((await snapshot(db, id)).entries.length, 2);
});

integration('a paid grant marker or another account does not suppress free eligibility', async db => {
  const first = await account(db), second = await account(db);
  await runDripForAccount(db, first, january);
  const paid = await upsertSubscriptionFromStore(db, {
    accountID: second, tier: 'standard', status: 'canceled', store: 'stripe', storeReference: `canceled-${second}`,
    periodStart: new Date('2026-01-01T00:00:00Z'), periodEnd: february,
  });
  await db.query('INSERT INTO subscription_grants(id,subscription_id,account_id,period_key,minutes) VALUES($1,$2,$3,$4,0)',
    [randomUUID(), paid.id, second, '2026-01']);
  assert.equal(await runDripForAccount(db, second, january), 5);
  assert.equal((await minuteBalance(db, second)).availableMilliseconds, 300000);
  assert.equal((await db.query('SELECT count(*) FROM subscription_grants WHERE account_id=$1 AND subscription_id IS NULL', [second])).rows[0].count, '1');
});

integration('guest and active subscription guards and the sequential global budget remain intact', async db => {
  const guest = await account(db, true), subscribed = await account(db);
  await upsertSubscriptionFromStore(db, {
    accountID: subscribed, tier: 'standard', status: 'active', store: 'stripe', storeReference: `active-${subscribed}`,
    periodStart: new Date('2026-01-01T00:00:00Z'), periodEnd: february,
  });
  assert.equal(await runDripForAccount(db, guest, january), 0);
  assert.equal(await runDripForAccount(db, subscribed, january), 0);
  assert.equal((await snapshot(db, guest)).entries.length, 0);
  assert.equal((await snapshot(db, subscribed)).grants.length, 0);
  await db.query('UPDATE subscription_config SET lifetime_drip_budget_minutes=5');
  const allowed = await account(db), refused = await account(db);
  assert.equal(await runDripForAccount(db, allowed, january), 5);
  assert.equal(await runDripForAccount(db, refused, january), 0);
  assert.equal((await minuteBalance(db, refused)).availableMilliseconds, 0);
  assert.equal((await snapshot(db, refused)).grants.length, 0);
});

integration('policy changes and removed grant markers are observed without a stale cache', async db => {
  const id = await account(db);
  await db.query('UPDATE subscription_config SET free_drip_enabled=false');
  assert.equal(await runDripForAccount(db, id, january), 0);
  await db.query('UPDATE subscription_config SET free_drip_enabled=true,free_drip_minutes=7');
  assert.equal(await runDripForAccount(db, id, january), 7);
  assert.equal((await minuteBalance(db, id)).availableMilliseconds, 420000);
  // Marker removal is not a public operation. Characterize the existing fallback:
  // stable financial references still prevent an extra credit after its recreation.
  await db.query('DELETE FROM subscription_grants WHERE account_id=$1', [id]);
  await runDripForAccount(db, id, january);
  assert.equal((await snapshot(db, id)).grants.length, 1);
  assert.equal((await snapshot(db, id)).entries.length, 1);
  assert.equal((await minuteBalance(db, id)).availableMilliseconds, 420000);
});

integration('missing configuration is still an error even when a grant already exists', async db => {
  const id = await account(db);
  await runDripForAccount(db, id, january);
  const before = await snapshot(db, id);
  await db.query('DELETE FROM subscription_config');
  await assert.rejects(runDripForAccount(db, id, january), (e: any) => e.code === 'subscription_config_missing');
  assert.deepEqual(await snapshot(db, id), before);
});

integration('a real grant lookup failure cannot create another financial entry', async db => {
  const id = await account(db);
  await runDripForAccount(db, id, january);
  const balance = await minuteBalance(db, id);
  await db.query('ALTER TABLE subscription_grants RENAME TO inaccessible_grants');
  await assert.rejects(runDripForAccount(db, id, january), (e: any) => e.code === '42P01');
  assert.deepEqual(await minuteBalance(db, id), balance);
  assert.equal((await db.query('SELECT count(*) FROM minute_entries WHERE account_id=$1', [id])).rows[0].count, '1');
});

integration('rollover caps and reserved balances retain the prior grant math', async db => {
  const { transaction } = await import('../src/db.js');
  const { appendMinuteEntry } = await import('../src/minutes.js');
  for (const [seed, reserved, expectedGrant, expectedAvailable] of [[15, 0, 0, 15], [15, 4, 4, 15]] as const) {
    const id = await account(db);
    await transaction(db, sql => appendMinuteEntry(sql, id, `seed-${id}`, 'purchase', seed * 60000, reserved * 60000));
    assert.equal(await runDripForAccount(db, id, january), expectedGrant);
    const balance = await minuteBalance(db, id);
    assert.equal(balance.availableMilliseconds, expectedAvailable * 60000);
    assert.equal(balance.reservedMilliseconds, reserved * 60000);
    assert.equal(await runDripForAccount(db, id, january), 0);
  }
});

integration('two synchronized initial lookup misses still create only one financial grant', async db => {
  const id = await account(db);
  let misses = 0;
  let release!: () => void;
  const barrier = new Promise<void>(resolve => { release = resolve; });
  const wrapped = new Proxy(db, {
    get(target, property) {
      // Both calls have reached the fallback after their eligibility read.
      // Pause before BEGIN, so neither can create a claim before the other miss.
      if (property === 'connect') return async () => {
        misses++;
        if (misses === 2) release();
        await barrier;
        return target.connect();
      };
      const value = Reflect.get(target, property, target);
      return typeof value === 'function' ? value.bind(target) : value;
    },
  });
  assert.deepEqual((await Promise.all([
    runDripForAccount(wrapped, id, january), runDripForAccount(wrapped, id, january),
  ])).sort(), [0, 5]);
  assert.equal(misses, 2);
  assert.equal((await snapshot(db, id)).grants.length, 1);
  assert.equal((await snapshot(db, id)).entries.length, 1);
  assert.equal((await minuteBalance(db, id)).availableMilliseconds, 300000);
});


for (const state of ['guest', 'trialing', 'active'] as const) {
  integration(`${state} fallback preserves persisted credit without issuing a grant`, async db => {
    const id = await account(db, state === 'guest');
    if (state !== 'guest') await upsertSubscriptionFromStore(db, {
      accountID: id, tier: 'standard', status: state, store: 'stripe', storeReference: `${state}-${id}`,
      periodStart: new Date('2026-01-01T00:00:00Z'), periodEnd: new Date('2030-01-01T00:00:00Z'),
    });
    const before = await snapshot(db, id);
    const observed = observeTransactions(db);
    assert.equal(await runDripForAccount(observed.db, id, january), 0);
    assert.deepEqual(await snapshot(db, id), before);
    assert.equal(observed.acquisitions(), 1, 'The simpler precheck retains the original guarded fallback for excluded accounts.');
  });
}

integration('past-due status does not suppress a free grant', async db => {
  const id = await account(db);
  await upsertSubscriptionFromStore(db, {
    accountID: id, tier: 'standard', status: 'past_due', store: 'stripe', storeReference: `past-due-${id}`,
    periodStart: new Date('2026-01-01T00:00:00Z'), periodEnd: new Date('2030-01-01T00:00:00Z'),
  });
  assert.equal(await runDripForAccount(db, id, january), 5);
  assert.equal((await minuteBalance(db, id)).availableMilliseconds, 300000);
  assert.equal((await snapshot(db, id)).grants.length, 1);
  assert.equal((await snapshot(db, id)).entries.length, 1);
});

integration('eligibility lost after the precheck is revalidated before any credit write', async db => {
  for (const reason of ['guest', 'subscription'] as const) {
    const id = await account(db);
    const before = await snapshot(db, id);
    let acquired = false;
    const wrapped = new Proxy(db, {
      get(target, property) {
        if (property === 'connect') return async () => {
          acquired = true;
          // Change real persisted eligibility after the read, before BEGIN.
          // The production transaction must still run its original guards.
          if (reason === 'guest') await db.query('UPDATE accounts SET is_guest=true WHERE id=$1', [id]);
          else await upsertSubscriptionFromStore(db, {
            accountID: id, tier: 'standard', status: 'active', store: 'stripe', storeReference: `concurrent-${id}`,
            periodStart: new Date('2026-01-01T00:00:00Z'), periodEnd: new Date('2030-01-01T00:00:00Z'),
          });
          return target.connect();
        };
        const value = Reflect.get(target, property, target);
        return typeof value === 'function' ? value.bind(target) : value;
      },
    });
    assert.equal(await runDripForAccount(wrapped, id, january), 0);
    assert.equal(acquired, true, 'The candidate must have reached the guarded fallback.');
    assert.deepEqual(await snapshot(db, id), before);
  }
});

integration('a fresh eligibility read grants after registration or loss of paid status', async db => {
  for (const transition of ['registration', 'past_due', 'canceled'] as const) {
    const id = await account(db, transition === 'registration');
    let subscriptionID: string | undefined;
    if (transition !== 'registration') {
      const sub = await upsertSubscriptionFromStore(db, {
        accountID: id, tier: 'standard', status: 'active', store: 'stripe', storeReference: `transition-${id}`,
        periodStart: new Date('2026-01-01T00:00:00Z'), periodEnd: new Date('2030-01-01T00:00:00Z'),
      });
      subscriptionID = sub.id;
    }
    const before = await snapshot(db, id);
    assert.equal(await runDripForAccount(db, id, january), 0);
    assert.deepEqual(await snapshot(db, id), before);
    if (transition === 'registration') await db.query('UPDATE accounts SET is_guest=false WHERE id=$1', [id]);
    else await db.query('UPDATE subscriptions SET status=$2 WHERE id=$1', [subscriptionID, transition]);
    assert.equal(await runDripForAccount(db, id, january), 5);
    assert.equal((await minuteBalance(db, id)).availableMilliseconds, 300000);
    assert.equal((await snapshot(db, id)).grants.length, 1);
    assert.equal((await snapshot(db, id)).entries.length, 1);
  }
});

integration('a missing account keeps its foreign-key error without persisting credit', async db => {
  const id = randomUUID();
  // minuteBalance deliberately rejects missing accounts; inspect persisted rows
  // directly so this probe reaches the production grant and its rollback.
  const persisted = async () => (await db.query(`SELECT
    (SELECT jsonb_agg(t) FROM subscription_grants t) AS grants,
    (SELECT jsonb_agg(t) FROM minute_entries t) AS entries,
    (SELECT jsonb_agg(t) FROM minute_wallets t) AS wallets`)).rows;
  const before = await persisted();
  await assert.rejects(runDripForAccount(db, id, january), (e: any) => e.code === '23503');
  assert.deepEqual(await persisted(), before);
});
