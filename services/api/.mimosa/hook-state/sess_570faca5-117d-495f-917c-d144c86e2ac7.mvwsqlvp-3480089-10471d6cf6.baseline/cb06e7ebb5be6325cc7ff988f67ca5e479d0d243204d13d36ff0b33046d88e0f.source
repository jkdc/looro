import { test, before, after, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { connectDatabase, type Database } from '../src/db.js';
import { migrate } from '../src/migrate.js';
import { mergeProgress, parseProgressBatch, parseProgressV2Batch, PROGRESS_BATCH, PROGRESS_KINDS } from '../src/progress.js';

const url = process.env.TEST_DATABASE_URL;
if (url && !new URL(url).pathname.endsWith('_test')) throw new Error('Use an isolated database ending in _test.');
const db = url ? connectDatabase(url) : null;
before(async () => { if (db) await migrate(db); });
beforeEach(async () => { if (db) await db.query('TRUNCATE user_progress'); });
after(async () => { if (db) { try { await db.query('TRUNCATE user_progress'); } finally { await db.end(); } } });
const integration = (name: string, fn: () => Promise<void>) => test(name, { skip: !db && 'Set TEST_DATABASE_URL for PostgreSQL tests.' }, fn);
async function account() {
  const id = randomUUID();
  await db!.query('INSERT INTO accounts(id,email) VALUES($1,$2)', [id, 'progress.test@example.test']);
  return id;
}
const entry = (overrides: Record<string, unknown> = {}, kind = 'lesson') => ({
  kind, itemID: 'en-foundations-u1-to-be', at: Date.now(), attempts: 1, bestScore: 0, language: 'en', ...overrides });
const batch = (entries: unknown) => ({ entries });

test('the parser accepts representative entries from every progress family', () => {
  const parsed = parseProgressBatch(batch([
    entry(),
    entry({ itemID: 'restaurant' }, 'conversation_theme'),
    entry({ itemID: 'en-foundations-u1-to-be', bestScore: 80 }, 'exercise_set'),
    entry({ itemID: 'cafe-morning', bestScore: 60 }, 'listening'),
    entry({ itemID: 'hola', attempts: 12 }, 'recall_round'),
    entry({ itemID: 'ser-vs-estar', attempts: 3, bestScore: 100 }, 'words_practice'),
    entry({ itemID: 'en-foundations-u1', attempts: 2 }, 'assessment'),
    entry({ itemID: 'job_interview' }, 'scenario_practice')
  ]));
  assert.equal(parsed.entries.length, 8);
  assert.equal(parsed.entries[3]!.kind, 'listening');
  assert.equal(parsed.entries[4]!.attempts, 12);
  assert.equal(parsed.entries[7]!.kind, 'scenario_practice');
});

test('the parser rejects anything outside the closed ledger shape', () => {
  const good = entry();
  for (const invalid of [
    null, 'x', [], { entries: [good], extra: 1 }, batch([]),
    batch(Array.from({ length: PROGRESS_BATCH + 1 }, () => good)),
    batch([null]), batch([{ ...good, extra: 1 }]), batch([entry({ kind: 'streak' })]),
    batch([entry({ itemID: 'Hello World' })]), batch([entry({ itemID: 'x'.repeat(65) })]),
    batch([entry({ at: 'now' })]), batch([entry({ at: 0 })]),
    batch([entry({ at: Date.now() + 49 * 3_600_000 })]),
    batch([entry({ attempts: 0 })]), batch([entry({ attempts: 1.5 })]),
    batch([entry({ bestScore: 101 })]), batch([entry({ bestScore: -1 })]),
    batch([entry({ language: 'xx' })]),
    batch([Object.fromEntries(Object.entries(good).filter(([key]) => key !== 'language'))])
  ]) {
    assert.throws(() => parseProgressBatch(invalid));
  }
  assert.deepEqual([...PROGRESS_KINDS].sort(), ['assessment', 'conversation_theme', 'exercise_set',
    'lesson', 'listening', 'pronunciation', 'reading', 'recall_round', 'scenario_practice', 'words_practice']);
});

test('version two validates distinct first and last timestamps and its strict batch shape', () => {
  const firstAt = 1_700_000_000_000, lastAt = firstAt + 500;
  const parsed = parseProgressV2Batch({ version: 2, entries: [{ kind: 'lesson', itemID: 'lesson-one', language: 'en',
    firstAt, lastAt, attempts: 3, bestScore: 80 }] }, lastAt);
  assert.equal(parsed.entries[0]!.firstAt.getTime(), firstAt);
  assert.equal(parsed.entries[0]!.lastAt.getTime(), lastAt);
  for (const invalid of [
    { version: 1, entries: [{ kind: 'lesson', itemID: 'lesson-one', language: 'en', firstAt, lastAt, attempts: 1, bestScore: 0 }] },
    { version: 2, entries: [{ kind: 'lesson', itemID: 'lesson-one', language: 'en', at: lastAt, attempts: 1, bestScore: 0 }] },
    { version: 2, entries: [{ kind: 'lesson', itemID: 'lesson-one', language: 'en', firstAt: lastAt, lastAt: firstAt, attempts: 1, bestScore: 0 }] },
    { version: 2, extra: true, entries: [{ kind: 'lesson', itemID: 'lesson-one', language: 'en', firstAt, lastAt, attempts: 1, bestScore: 0 }] },
    { version: 2, entries: Array.from({ length: PROGRESS_BATCH + 1 }, () => ({ kind: 'lesson', itemID: 'lesson-one',
      language: 'en', firstAt, lastAt, attempts: 1, bestScore: 0 })) },
  ]) assert.throws(() => parseProgressV2Batch(invalid, lastAt));
});

integration('entries merge monotonically and replays change nothing', async () => {
  const id = await account();
  const now = Date.now();
  assert.deepEqual(await mergeProgress(db!, id, batch([entry({ at: now - 60_000, attempts: 2 }, 'lesson')])), { merged: 1 });
  // An older replay with lower counters must not regress the ledger.
  await mergeProgress(db!, id, batch([entry({ at: now - 120_000, attempts: 1, bestScore: 0 }, 'lesson')]));
  // A richer sync (more attempts, higher score, later touch) moves forward.
  await mergeProgress(db!, id, batch([entry({ at: now, attempts: 5, bestScore: 80 }, 'lesson')]));
  const rows = (await db!.query('SELECT * FROM user_progress')).rows;
  assert.equal(rows.length, 1);
  const row = rows[0]!;
  assert.equal(row.attempts, 5);
  assert.equal(row.best_score, 80);
  assert.equal(new Date(row.first_at).getTime(), now - 120_000);
  assert.equal(new Date(row.last_at).getTime(), now);
  await mergeProgress(db!, id, batch([entry({ at: now, attempts: 5, bestScore: 80 }, 'lesson')]));
  assert.equal(Number((await db!.query('SELECT count(*) AS total FROM user_progress')).rows[0].total), 1);
});

integration('kinds and items stay independent and account deletion cascades', async () => {
  const id = await account();
  await mergeProgress(db!, id, batch([
    entry({}, 'lesson'), entry({ itemID: 'restaurant' }, 'conversation_theme'), entry({ itemID: 'x' }, 'listening')]));
  const rows = (await db!.query('SELECT kind,item_id FROM user_progress ORDER BY kind')).rows;
  assert.equal(rows.length, 3);
  assert.deepEqual(rows.map(row => row.kind), ['conversation_theme', 'lesson', 'listening']);
  await db!.query('DELETE FROM accounts WHERE id=$1', [id]);
  assert.equal(Number((await db!.query('SELECT count(*) AS total FROM user_progress WHERE account_id=$1', [id])).rows[0].total), 0);
});

integration('the same item earned in two languages is two rows', async () => {
  const id = await account();
  await mergeProgress(db!, id, batch([
    entry({ kind: 'conversation_theme', itemID: 'restaurant', attempts: 2, bestScore: 0, language: 'en' }),
    entry({ kind: 'conversation_theme', itemID: 'restaurant', attempts: 5, bestScore: 0, language: 'fr' })]));
  const rows = (await db!.query('SELECT language,attempts FROM user_progress ORDER BY language')).rows;
  assert.deepEqual(rows, [{ language: 'en', attempts: 2 }, { language: 'fr', attempts: 5 }]);
  // The merge stays scoped per language: an en replay must not lift the fr row.
  await mergeProgress(db!, id, batch([
    entry({ kind: 'conversation_theme', itemID: 'restaurant', attempts: 9, bestScore: 0, language: 'en' })]));
  const after = (await db!.query('SELECT language,attempts FROM user_progress ORDER BY language')).rows;
  assert.deepEqual(after.map(row => row.attempts), [9, 5]);
});
