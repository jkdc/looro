import { test, before, after, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { connectDatabase, type Database } from '../src/db.js';
import { migrate } from '../src/migrate.js';
import { timeseries, usageOverview } from '../src/admin-metrics.js';

const url = process.env.TEST_DATABASE_URL;
if (url && !new URL(url).pathname.endsWith('_test')) throw new Error('Use an isolated database ending in _test.');
const db = url ? connectDatabase(url) : null;
before(async () => { if (db) await migrate(db); });
beforeEach(async () => { if (db) await db.query('TRUNCATE usage_events'); });
after(async () => { if (db) await db.end(); });
const integration = (name: string, fn: () => Promise<void>) => test(name, { skip: !db && 'Set TEST_DATABASE_URL for PostgreSQL tests.' }, fn);
// Expectations share the series' clock: admin-metrics anchors its day window
// on the database (now()::date in the session timezone), so deriving them from
// the JS clock diverges whenever the two clocks or timezones disagree.
const pgDaysAgoISO = async (days: number) =>
  (await db!.query('SELECT to_char(now()::date - $1::int, \'YYYY-MM-DD\') AS day', [days])).rows[0].day as string;
const pgLastDays = async (count: number) => (await db!.query(
  'SELECT to_char(d, \'YYYY-MM-DD\') AS day FROM generate_series(now()::date - ($1::int - 1), now()::date, interval \'1 day\') d',
  [count])).rows.map(row => row.day as string);

async function accountWithEvents(createdAtDaysAgo: number, eventDaysAgo: number[]): Promise<string> {
  const id = randomUUID();
  await db!.query(`INSERT INTO accounts(id,email,created_at) VALUES($1,$2, now() - ($3::int * interval '1 day'))`,
    [id, `${id}@example.test`, createdAtDaysAgo]);
  for (const days of eventDaysAgo) {
    await db!.query(`INSERT INTO usage_events(id,client_event_id,account_id,name,platform,app_version,client_at,created_at)
      VALUES(gen_random_uuid(),$1,$2,'app_open','android','1.0',
        now() - ($3::int * interval '1 day'), now() - ($3::int * interval '1 day'))`,
    [randomUUID(), id, days]);
  }
  return id;
}

integration('the usage timeseries set renders distinct daily actives per metric', async () => {
  const a = await accountWithEvents(1, [1]), b = await accountWithEvents(0, [0]);
  await db!.query(`INSERT INTO usage_events(id,client_event_id,account_id,name,platform,app_version,client_at,created_at)
    VALUES(gen_random_uuid(),$1,$2,'lesson_completed','ios','2.0', now(), now())`, [randomUUID(), a]);
  const series = await timeseries(db!, 'usage', 7);
  assert.equal(series.set, 'usage');
  assert.equal(series.series.length, 6);
  const dau = series.series.find(entry => entry.name === 'dau')!;
  assert.deepEqual(dau.points.map(point => point.day), await pgLastDays(7));
  assert.equal(dau.points[5]!.value, 1); // yesterday: only A
  assert.equal(dau.points[6]!.value, 2); // today: B's open + A's lesson completion
  const lessons = series.series.find(entry => entry.name === 'lessons')!;
  assert.equal(lessons.points[6]!.value, 1);
  for (const id of [a, b]) {
    await db!.query('DELETE FROM usage_events WHERE account_id=$1', [id]);
    await db!.query('DELETE FROM accounts WHERE id=$1', [id]);
  }
});

integration('usage overview derives KPIs, top features and signup-cohort retention', async () => {
  // A: signed up 3 days ago, active at signup, on day 1 and today -> D1/D7 retained.
  const a = await accountWithEvents(3, [3, 2, 0]);
  // B: signed up 2 days ago, active only at signup -> no D1 retention.
  const b = await accountWithEvents(2, [2]);
  const overview = await usageOverview(db!, 30);
  assert.ok(overview.dau >= 1 && overview.wau >= 2 && overview.mau >= 2);
  assert.equal(overview.events7d, 4);
  assert.equal(overview.topFeatures[0]!.name, 'app_open');
  assert.equal(overview.topFeatures[0]!.count, 4);
  // The database is shared with load-test rows, so cohort sizes are not hermetic:
  // assert what this test controls — A's retention shows up, windows nest, and
  // every row stays internally consistent.
  const cohortDay = await pgDaysAgoISO(3);
  const cohortA = overview.retention.find(row => row.day === cohortDay)!;
  assert.ok(cohortA.size >= 1 && cohortA.d1 >= 1 && cohortA.d7 >= 1 && cohortA.d30 >= 1);
  for (const row of overview.retention) {
    assert.ok(row.d1 <= row.d7 && row.d7 <= row.d30 && row.d30 <= row.size, JSON.stringify(row));
  }
  // The day window clamps: sub-7-day requests are refused, keeping D30 honest.
  assert.equal((await usageOverview(db!, 3)).retention.length, overview.retention.length);
  await db!.query('DELETE FROM usage_events WHERE account_id=$1', [a]);
  await db!.query('DELETE FROM usage_events WHERE account_id=$1', [b]);
  await db!.query('DELETE FROM accounts WHERE id=$1', [a]);
  await db!.query('DELETE FROM accounts WHERE id=$1', [b]);
});
