import { test, before, after, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes, randomUUID } from 'node:crypto';
import { createApp } from '../src/app.js';
import { connectDatabase, type Database } from '../src/db.js';
import { digest } from '../src/auth.js';
import { migrate } from '../src/migrate.js';
import { USAGE_EVENTS_BODY_LIMIT, USAGE_EVENTS_PATH } from '../src/usage.js';

const url = process.env.TEST_DATABASE_URL;
if (url && !new URL(url).pathname.endsWith('_test')) throw new Error('Use an isolated database ending in _test.');
const db = url ? connectDatabase(url) : null;
before(async () => { if (db) await migrate(db); });
beforeEach(async () => { if (db) await db.query('TRUNCATE usage_events'); });
after(async () => { if (db) { try { await db.query('TRUNCATE usage_events'); } finally { await db.end(); } } });
const integration = (name: string, fn: () => Promise<void>) => test(name, { skip: !db && 'Set TEST_DATABASE_URL for PostgreSQL tests.' }, fn);

/** A registered account and a guest, each with a live bearer session. */
const bearers: Array<{ bearer: string; guest: boolean }> = [];
before(async () => {
  if (!db) return;
  for (const guest of [false, true]) {
    const account = randomUUID(), bearer = randomBytes(32).toString('base64url');
    await db.query('INSERT INTO accounts(id,email,is_guest) VALUES($1,$2,$3)', [account, null, guest]);
    await db.query(`INSERT INTO auth_sessions(id,account_id,token_hash,expires_at)
      VALUES($1,$2,$3,now()+interval '1 hour')`, [randomUUID(), account, digest(bearer)]);
    bearers.push({ bearer, guest });
  }
});
const bearer = (index = 0) => `Bearer ${bearers[index]!.bearer}`;
const body = (events: unknown, platform = 'android') =>
  ({ platform, appVersion: '1.0 (99)', events });
const event = (name = 'app_open', props?: Record<string, unknown>) => ({
  id: randomUUID(), name, at: Date.now(), ...(props === undefined ? {} : { props }) });

integration('ingestion requires an account bearer, guests included', async () => {
  const app = createApp({ db: db!, auth: {} });
  try {
    for (const headers of [{}, { authorization: 'Bearer nope' }, { authorization: 'Basic abc' }]) {
      const response = await app.inject({ method: 'POST', url: USAGE_EVENTS_PATH, headers, payload: body([event()]) });
      assert.equal(response.statusCode, 401);
      assert.deepEqual(response.json(), { error: { code: 'sign_in_required' } });
    }
    assert.equal(Number((await db!.query('SELECT count(*) AS total FROM usage_events')).rows[0].total), 0);
  } finally { await app.close(); }
});

integration('a guest batch is accepted and stored without echoing identity or content', async () => {
  const app = createApp({ db: db!, auth: {} });
  try {
    const events = [event(), event('session_started', { input: 'voice', provider: 'hosted' }),
      event('word_lookup', { source: 'talk' })];
    const response = await app.inject({ method: 'POST', url: USAGE_EVENTS_PATH,
      headers: { authorization: bearer(1), 'content-type': 'application/json' }, payload: body(events, 'ios') });
    assert.equal(response.statusCode, 202);
    assert.deepEqual(response.json(), { accepted: 3 });
    assert.equal(response.headers['cache-control'], 'no-store');
    assert.ok(!response.body.includes('voice') && !response.body.includes(bearer(1)));
    const rows = (await db!.query('SELECT platform, app_version, name, props FROM usage_events ORDER BY name')).rows;
    assert.equal(rows.length, 3);
    assert.equal(rows.every(row => row.platform === 'ios' && row.app_version === '1.0 (99)'), true);
    assert.deepEqual(rows.find(row => row.name === 'word_lookup')!.props, { source: 'talk' });
  } finally { await app.close(); }
});

integration('a replayed batch stays idempotent over HTTP', async () => {
  const app = createApp({ db: db!, auth: {} });
  try {
    const events = [event('replay_used'), event('lesson_completed', { courseID: 'english-foundations', lessonID: 'l1' })];
    for (const expected of [202, 202]) {
      const response = await app.inject({ method: 'POST', url: USAGE_EVENTS_PATH,
        headers: { authorization: bearer(0) }, payload: body(events) });
      assert.equal(response.statusCode, expected);
      assert.deepEqual(response.json(), { accepted: 2 });
    }
    assert.equal(Number((await db!.query('SELECT count(*) AS total FROM usage_events')).rows[0].total), 2);
  } finally { await app.close(); }
});

integration('off-catalog names, oversized batches and oversized bodies fail closed', async () => {
  const app = createApp({ db: db!, auth: {} });
  try {
    const invalidBatches = [
      body([event('password_reset')]),
      body([...Array.from({ length: 101 }, () => event())]),
      { platform: 'android', appVersion: '1', events: [{ ...event(), at: -1 }] },
      { platform: 'android', appVersion: '1', events: [event('word_lookup', { word: 'hola' })] }
    ];
    for (const invalid of invalidBatches) {
      const response = await app.inject({ method: 'POST', url: USAGE_EVENTS_PATH,
        headers: { authorization: bearer(0) }, payload: invalid });
      assert.equal(response.statusCode, 400);
      assert.deepEqual(response.json(), { error: { code: 'invalid_usage_batch' } });
    }
    assert.equal(Number((await db!.query('SELECT count(*) AS total FROM usage_events')).rows[0].total), 0);
    const oversized = await app.inject({ method: 'POST', url: USAGE_EVENTS_PATH,
      headers: { authorization: bearer(0), 'content-type': 'application/json' }, payload: 'x'.repeat(USAGE_EVENTS_BODY_LIMIT + 1) });
    assert.equal(oversized.statusCode, 413);
    const malformed = await app.inject({ method: 'POST', url: USAGE_EVENTS_PATH,
      headers: { authorization: bearer(0), 'content-type': 'application/json' }, payload: '{"events":' });
    assert.equal(malformed.statusCode, 400);
  } finally { await app.close(); }
});
