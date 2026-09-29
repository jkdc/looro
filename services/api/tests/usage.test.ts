import { test, before, after, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { connectDatabase, type Database } from '../src/db.js';
import { migrate } from '../src/migrate.js';
import { ingestUsageEvents, parseUsageBatch, pruneUsageEvents, USAGE_CATALOG, USAGE_EVENT_BATCH,
  USAGE_EVENT_NAMES } from '../src/usage.js';

const url = process.env.TEST_DATABASE_URL;
if (url && !new URL(url).pathname.endsWith('_test')) throw new Error('Use an isolated database ending in _test.');
const db = url ? connectDatabase(url) : null;
before(async () => { if (db) await migrate(db); });
beforeEach(async () => { if (db) await db.query('TRUNCATE usage_events'); });
after(async () => { if (db) await db.end(); });
const integration = (name: string, fn: () => Promise<void>) => test(name, { skip: !db && 'Set TEST_DATABASE_URL for PostgreSQL tests.' }, fn);
async function account() {
  const id = randomUUID();
  await db!.query('INSERT INTO accounts(id,email) VALUES($1,$2)', [id, 'usage.test@example.test']);
  return id;
}
const event = (overrides: Record<string, unknown> = {}, name = 'app_open') => ({
  id: randomUUID(), name, at: Date.now(), ...(name === 'app_open' ? {} : { props: {} }), ...overrides });

test('the batch parser accepts a representative batch from each catalog family', () => {
  const batch = parseUsageBatch({ platform: 'android', appVersion: '1.0 (42)', events: [
    event(),
    event({ props: { input: 'voice', provider: 'byok', engine: 'glm' } }, 'session_started'),
    event({ props: { durationBucket: 2, turnsBucket: 1, themeID: 'restaurant' } }, 'session_ended'),
    event({ props: { courseID: 'english-foundations', lessonID: 'greetings-1' } }, 'lesson_completed'),
    event({ props: { courseID: 'english-foundations', passed: true } }, 'assessment_completed'),
    event({ props: { mode: 'recall', scoreBucket: 4 } }, 'practice_completed'),
    event({ props: { source: 'talk' } }, 'word_lookup'),
    event({ props: { rate: '0.8' } }, 'tts_speed_changed'),
    event({ props: { surface: 'talk' } }, 'paywall_viewed')
  ] });
  assert.equal(batch.events.length, 9);
  assert.equal(batch.events[1]!.name, 'session_started');
  assert.deepEqual(batch.events[0]!.props, {});
  assert.equal(batch.events[3]!.props.courseID, 'english-foundations');
});

test('the batch parser rejects anything outside the closed catalog', () => {
  const good = event();
  for (const invalid of [
    null, 'x', [], { platform: 'android', appVersion: '1', events: [good], extra: 1 },
    { platform: 'web', appVersion: '1', events: [good] },
    { platform: 'android', appVersion: '', events: [good] },
    { platform: 'android', appVersion: '1\t', events: [good] },
    { platform: 'android', appVersion: 'x'.repeat(33), events: [good] },
    { platform: 'android', appVersion: '1', events: [] },
    { platform: 'android', appVersion: '1', events: Array.from({ length: USAGE_EVENT_BATCH + 1 }, () => event()) },
    { platform: 'android', appVersion: '1', events: [{ ...good, extra: 1 }] },
    { platform: 'android', appVersion: '1', events: [{ ...good, id: 'not-a-uuid' }] },
    { platform: 'android', appVersion: '1', events: [{ ...good, name: 'password_reset' }] },
    { platform: 'android', appVersion: '1', events: [{ ...good, at: 'now' }] },
    { platform: 'android', appVersion: '1', events: [{ ...good, at: 0 }] },
    { platform: 'android', appVersion: '1', events: [{ ...good, at: Date.now() + 49 * 3_600_000 }] },
    { platform: 'android', appVersion: '1', events: [event({ props: null })] },
    { platform: 'android', appVersion: '1', events: [event({ props: { search: 'hello' } })] },
    { platform: 'android', appVersion: '1', events: [event({ props: { input: 'voice', provider: 'byok', engine: 'chatgpt' } }, 'session_started')] },
    { platform: 'android', appVersion: '1', events: [event({ props: { provider: 'byok' } }, 'session_started')] },
    { platform: 'android', appVersion: '1', events: [event({ props: { durationBucket: 6 } }, 'session_ended')] },
    { platform: 'android', appVersion: '1', events: [event({ props: { durationBucket: 1.5 } }, 'session_ended')] },
    { platform: 'android', appVersion: '1', events: [event({ props: { themeID: 'Hello World' } }, 'theme_selected')] },
    { platform: 'android', appVersion: '1', events: [event({ props: { courseID: 'x'.repeat(65) } }, 'lesson_started')] },
    { platform: 'android', appVersion: '1', events: [event({ props: { passed: 'yes' } }, 'assessment_completed')] },
    { platform: 'android', appVersion: '1', events: [event({ props: { mode: 'recall' } }, 'practice_completed')] }
  ]) {
    assert.throws(() => parseUsageBatch(invalid));
  }
  assert.ok(Object.keys(USAGE_CATALOG).length >= 15);
  assert.deepEqual([...USAGE_EVENT_NAMES].sort(), Object.keys(USAGE_CATALOG).sort());
});

integration('the SQL name check and the TS catalog stay pinned together', async () => {
  const definition = (await db!.query(`SELECT pg_get_constraintdef(oid) AS def FROM pg_constraint
    WHERE conrelid='usage_events'::regclass AND conname='usage_events_name_check'`)).rows[0]!.def as string;
  const sqlNames = new Set(definition.match(/'([a-z_]+)'/g)!.map(token => token.slice(1, -1)));
  assert.deepEqual(new Set(USAGE_EVENT_NAMES), sqlNames);
});

integration('ingest stores the batch once and replays are idempotent', async () => {
  const id = await account();
  const batch = { platform: 'ios', appVersion: '2.0', events: [
    event(), event({ props: { input: 'typed', provider: 'hosted' } }, 'session_started'),
    event({ props: { source: 'words' } }, 'word_lookup')] };
  assert.deepEqual(await ingestUsageEvents(db!, id, batch), { accepted: 3 });
  assert.deepEqual(await ingestUsageEvents(db!, id, batch), { accepted: 3 });
  const rows = (await db!.query('SELECT * FROM usage_events ORDER BY client_at')).rows;
  assert.equal(rows.length, 3);
  assert.deepEqual(Object.keys(rows[0]).sort(), ['account_id', 'app_version', 'client_at', 'client_event_id',
    'created_at', 'id', 'name', 'platform', 'props']);
  assert.equal(rows[1]!.platform, 'ios');
  assert.deepEqual(rows[1]!.props, { input: 'typed', provider: 'hosted' });
  assert.equal(rows.every(row => row.account_id === id), true);
});

integration('a completed account deletion takes its usage events with it', async () => {
  const id = await account();
  await ingestUsageEvents(db!, id, { platform: 'android', appVersion: '1.0', events: [event()] });
  await db!.query('DELETE FROM accounts WHERE id=$1', [id]);
  assert.equal(Number((await db!.query('SELECT count(*) AS total FROM usage_events WHERE account_id=$1', [id])).rows[0].total), 0);
});

integration('stored events cannot be rewritten but the retention prune deletes old ones', async () => {
  const id = await account();
  await ingestUsageEvents(db!, id, { platform: 'android', appVersion: '1.0', events: [event()] });
  await assert.rejects(db!.query(`UPDATE usage_events SET props='{"rewritten":true}'`),
    error => String((error as Error).message).includes('usage events are immutable'));
  const old = randomUUID();
  await db!.query(`INSERT INTO usage_events(id,client_event_id,account_id,name,platform,app_version,client_at,created_at)
    VALUES($1,$2,$3,'app_open','android','1.0',now()-interval '181 days',now()-interval '181 days')`, [randomUUID(), old, id]);
  assert.equal(await pruneUsageEvents(db!), 1);
  const remaining = (await db!.query('SELECT client_event_id FROM usage_events')).rows;
  assert.equal(remaining.length, 1);
  assert.notEqual(remaining[0]!.client_event_id, old);
});
