import { test, before, after, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes, randomUUID } from 'node:crypto';
import { createApp } from '../src/app.js';
import { connectDatabase, type Database } from '../src/db.js';
import { digest } from '../src/auth.js';
import { migrate } from '../src/migrate.js';
import { PROGRESS_BODY_LIMIT, PROGRESS_PATH } from '../src/progress.js';

const url = process.env.TEST_DATABASE_URL;
if (url && !new URL(url).pathname.endsWith('_test')) throw new Error('Use an isolated database ending in _test.');
const db = url ? connectDatabase(url) : null;
before(async () => { if (db) await migrate(db); });
beforeEach(async () => { if (db) await db.query('TRUNCATE user_progress'); });
after(async () => { if (db) { try { await db.query('TRUNCATE user_progress'); } finally { await db.end(); } } });
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
    bearers.push({ bearer, guest: guest });
  }
});
const bearer = (index = 0) => `Bearer ${bearers[index]!.bearer}`;
const entry = (overrides: Record<string, unknown> = {}) => ({
  kind: 'lesson', itemID: 'en-foundations-u1-to-be', at: Date.now(), attempts: 1, bestScore: 0, language: 'en', ...overrides });

integration('the ledger requires an account bearer, guests included', async () => {
  const app = createApp({ db: db!, auth: {} });
  try {
    for (const headers of [{}, { authorization: 'Bearer nope' }, { authorization: 'Basic abc' }]) {
      const response = await app.inject({ method: 'POST', url: PROGRESS_PATH, headers, payload: { entries: [entry()] } });
      assert.equal(response.statusCode, 401);
      assert.deepEqual(response.json(), { error: { code: 'sign_in_required' } });
      const read = await app.inject({ method: 'GET', url: PROGRESS_PATH, headers });
      assert.equal(read.statusCode, 401);
    }
    assert.equal(Number((await db!.query('SELECT count(*) AS total FROM user_progress')).rows[0].total), 0);
  } finally { await app.close(); }
});

integration('a guest sync is stored and reading back returns only that account, never the bearer', async () => {
  const app = createApp({ db: db!, auth: {} });
  try {
    const payload = { entries: [entry(), entry({ kind: 'listening', itemID: 'cafe-morning', bestScore: 60 })] };
    const response = await app.inject({ method: 'POST', url: PROGRESS_PATH,
      headers: { authorization: bearer(1), 'content-type': 'application/json' }, payload });
    assert.equal(response.statusCode, 202);
    assert.deepEqual(response.json(), { merged: 2 });
    assert.equal(response.headers['cache-control'], 'no-store');
    assert.ok(!response.body.includes(bearer(1)));
    const read = await app.inject({ method: 'GET', url: PROGRESS_PATH, headers: { authorization: bearer(1) } });
    assert.equal(read.statusCode, 200);
    const rows: Array<{ kind: string; itemID: string; language: string; firstAt: number; lastAt: number; attempts: number; bestScore: number }> = read.json().progress;
    assert.equal(rows.length, 2);
    const listening = rows.find(row => row.kind === 'listening')!;
    assert.deepEqual(listening,
      { kind: 'listening', itemID: 'cafe-morning', language: 'en', firstAt: listening.firstAt,
        lastAt: listening.lastAt, attempts: 1, bestScore: 60 });
    const other = await app.inject({ method: 'GET', url: PROGRESS_PATH, headers: { authorization: bearer(0) } });
    assert.deepEqual(other.json().progress, []);
  } finally { await app.close(); }
});

integration('replayed syncs merge monotonically over HTTP', async () => {
  const app = createApp({ db: db!, auth: {} });
  try {
    const older = { entries: [entry({ at: Date.now() - 120_000, attempts: 1, bestScore: 20 })] };
    const newer = { entries: [entry({ at: Date.now(), attempts: 3, bestScore: 75 })] };
    for (const payload of [newer, older]) {
      const response = await app.inject({ method: 'POST', url: PROGRESS_PATH,
        headers: { authorization: bearer(0), 'content-type': 'application/json' }, payload });
      assert.equal(response.statusCode, 202);
    }
    const read = await app.inject({ method: 'GET', url: PROGRESS_PATH, headers: { authorization: bearer(0) } });
    const row = read.json().progress[0];
    assert.equal(row.attempts, 3);
    assert.equal(row.bestScore, 75);
  } finally { await app.close(); }
});

integration('off-catalog entries, oversized batches and oversized bodies fail closed', async () => {
  const app = createApp({ db: db!, auth: {} });
  try {
    const invalidBatches = [
      { entries: [entry({ kind: 'streak' })] },
      { entries: [entry({ itemID: 'Hola' })] },
      { entries: [entry({ bestScore: 101 })] },
      { entries: [entry({ language: 'klingon' })] },
      { entries: [] },
      { entries: [entry()], extra: 1 },
      {}
    ];
    for (const payload of invalidBatches) {
      const response = await app.inject({ method: 'POST', url: PROGRESS_PATH,
        headers: { authorization: bearer(0), 'content-type': 'application/json' }, payload });
      assert.equal(response.statusCode, 400);
      assert.deepEqual(response.json(), { error: { code: 'invalid_progress_batch' } });
    }
    assert.equal(Number((await db!.query('SELECT count(*) AS total FROM user_progress')).rows[0].total), 0);
    const oversized = await app.inject({ method: 'POST', url: PROGRESS_PATH,
      headers: { authorization: bearer(0), 'content-type': 'application/json' }, payload: 'x'.repeat(PROGRESS_BODY_LIMIT + 1) });
    assert.equal(oversized.statusCode, 413);
  } finally { await app.close(); }
});
