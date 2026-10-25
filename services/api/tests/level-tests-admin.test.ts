import { test, before, beforeEach, after } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { createApp } from '../src/app.js';
import { connectDatabase, type Database } from '../src/db.js';
import { migrate } from '../src/migrate.js';
import { AuthAdmission } from '../src/auth-admission.js';
import { addAdmin } from '../src/admins-admin.js';
import { adminAuthConfig, adminCookieHeader, type AdminAuthServices } from '../src/admin-auth.js';
import { digest } from '../src/auth.js';
import { weekIndex } from '../src/level-tests.js';
import { runLevelTestGenerationJob } from '../src/level-tests-generation-jobs.js';
import { approveGeneratedWeek } from '../src/level-tests-publication.js';

const hmacKey = 'a'.repeat(64), proxyToken = 'b'.repeat(64);
const url = process.env.TEST_DATABASE_URL;
if (url && !new URL(url).pathname.endsWith('_test')) throw new Error('Use an isolated database ending in _test.');
const db = url ? connectDatabase(url) : null;
before(async () => { if (db) await migrate(db); });
after(async () => { if (db) await db.end(); });
const integration = (name: string, fn: () => Promise<void>) => test(name, { skip: !db && 'Set TEST_DATABASE_URL for PostgreSQL tests.' }, fn);
beforeEach(async () => {
  cachedSession = undefined;
  if (db) {
    await db.query('TRUNCATE level_test_weeks, level_test_generation_jobs, admin_actions, admin_sessions, admins, accounts CASCADE');
    await db.query("DELETE FROM auth_rate_limits WHERE operation IN ('admin','admin-api')");
  }
});

const ADMIN_ENV = { ADMIN_HMAC_KEY: hmacKey, ADMIN_PROXY_TOKEN: proxyToken, ADMIN_PUBLIC_ORIGIN: 'https://admin.example' };
const app = () => createApp({ db: db!, auth: {}, admin: { services: { db: db!, config: adminAuthConfig(ADMIN_ENV)! } as AdminAuthServices,
  admission: new AuthAdmission(db!, { hmacKey, proxyToken, allowLocalLoopback: false }) } });
const headers = { origin: ADMIN_ENV.ADMIN_PUBLIC_ORIGIN, 'x-app-client-ip': '203.0.113.9', 'x-app-proxy-token': proxyToken };
let cachedSession: Promise<Record<string, string>> | undefined;
const session = () => cachedSession ??= (async () => {
  const admin = await addAdmin(db!, 'local', 'browser-admin');
  const token = 'E'.repeat(43);
  await db!.query(`INSERT INTO admin_sessions(id,admin_id,token_hash,expires_at) VALUES($1,$2,$3,now()+interval '1 hour')`,
    [randomUUID(), admin.id, digest(token)]);
  return { ...headers, cookie: adminCookieHeader(token) };
})();

const CURRENT = weekIndex(new Date());
const seed = async (rows: Partial<{ language: string; level: string; week: number; status: string; createdAt: Date; approved: boolean; origin: string }>[]) => {
  const ids: string[] = [];
  for (const row of rows) {
    const id = randomUUID();
    ids.push(id);
    const pool = { recognition: [], writing: [], readingPassages: [], listeningPassages: [] };
    const variants = [{ variant: 1, items: [], passages: [] }];
    await db!.query(`INSERT INTO level_test_weeks(id, language_id, level, week_index, status, pool, variants,
      generator, model, prompt_version, judge_version, judge_mode, review, tokens_in, tokens_out, created_at, origin)
      VALUES($1,$2,$3,$4,$5,$6,$7,'llm:opencode','glm-5.3-flash',1,1,'off','[]'::jsonb, 10, 20, $8, $9)`,
      [id, row.language ?? 'en', row.level ?? 'A1', row.week ?? CURRENT, row.status ?? 'draft',
        JSON.stringify(pool), JSON.stringify(variants), row.createdAt ?? new Date(), row.origin ?? 'unknown']);
    if (row.approved) await db!.query('UPDATE level_test_weeks SET approved_at=now(), approved_by=$2 WHERE id=$1', [id, id]);
  }
  return ids;
};

integration('the listing returns metadata only, newest first, across all statuses', async () => {
  await seed([
    { language: 'en', level: 'A1', status: 'draft', createdAt: new Date('2026-10-20T10:00:00Z') },
    { language: 'es', level: 'B1', status: 'approved', createdAt: new Date('2026-10-19T10:00:00Z'), approved: true },
    { language: 'fr', level: 'A2', status: 'rejected', createdAt: new Date('2026-10-21T10:00:00Z') },
  ]);
  const response = await app().inject({ method: 'GET', url: '/v1/admin/level-tests/weeks', headers: await session() });
  assert.equal(response.statusCode, 200);
  assert.equal(response.headers['cache-control'], 'no-store');
  const body = response.json();
  assert.equal(body.total, 3);
  assert.equal(body.limit, 50);
  assert.equal(body.items[0].languageID, 'fr');
  assert.deepEqual(Object.keys(body.items[0]).sort(), ['approvedAt', 'approvedBy', 'createdAt', 'generator', 'id',
    'judgeMode', 'languageID', 'level', 'model', 'origin', 'status', 'tokensIn', 'tokensOut', 'variantCount', 'weekId']);
  assert.ok(!('pool' in body.items[0]) && !('variants' in body.items[0]));
});

integration('the listing applies status, language, level and week filters', async () => {
  await seed([
    { language: 'en', level: 'A1', status: 'draft' },
    { language: 'en', level: 'A1', week: CURRENT - 4, status: 'approved', approved: true },
    { language: 'es', level: 'A1', status: 'rejected' },
    { language: 'en', level: 'B1', status: 'draft' },
    { language: 'en', level: 'A1', week: CURRENT - 5, status: 'retired' },
  ]);
  const get = async (query: string) => (await app().inject({ method: 'GET',
    url: `/v1/admin/level-tests/weeks?${query}`, headers: await session() })).json();
  assert.equal((await get('status=approved')).total, 1);
  assert.equal((await get('language=en,es')).total, 5);
  assert.equal((await get('level=B1')).total, 1);
  assert.equal((await get(`week_from=${CURRENT - 1}`)).total, 3);
  assert.equal((await get(`week_from=${CURRENT - 5}&week_to=${CURRENT - 1}`)).total, 2);
});

integration('the listing filters by creation date range and sorts', async () => {
  await seed([
    { language: 'en', status: 'draft', createdAt: new Date('2026-10-01T08:00:00Z') },
    { language: 'es', status: 'draft', createdAt: new Date('2026-10-15T08:00:00Z') },
    { language: 'it', status: 'draft', createdAt: new Date('2026-09-30T08:00:00Z') },
  ]);
  const get = async (query: string) => (await app().inject({ method: 'GET',
    url: `/v1/admin/level-tests/weeks?${query}`, headers: await session() })).json();
  assert.equal((await get('created_from=2026-10-01')).total, 2);
  assert.equal((await get('created_from=2026-10-02&created_to=2026-10-20')).total, 1);
  const byLanguage = await get('sort=language_id&dir=asc');
  assert.deepEqual(byLanguage.items.map((row: { languageID: string }) => row.languageID), ['en', 'es', 'it']);
});

integration('the listing reports who triggered each generated week', async () => {
  await seed([
    { level: 'A1', status: 'draft' },
    { level: 'A2', status: 'draft', origin: 'user_operator' },
    { level: 'B1', status: 'draft', origin: 'automatic_worker' },
  ]);
  const response = await app().inject({ method: 'GET', url: '/v1/admin/level-tests/weeks?sort=level&dir=asc', headers: await session() });
  const body = response.json();
  assert.deepEqual(body.items.map((row: { origin: string }) => row.origin),
    ['unknown', 'user_operator', 'automatic_worker']);
});

integration('the listing paginates with a stable total', async () => {
  await seed([1, 2, 3].map(n => ({ language: 'en', level: `A${n}`, status: 'draft',
    createdAt: new Date(Date.UTC(2026, 9, n)) })));
  const response = await app().inject({ method: 'GET', url: '/v1/admin/level-tests/weeks?limit=1&offset=1', headers: await session() });
  const body = response.json();
  assert.equal(body.total, 3);
  assert.equal(body.items.length, 1);
  assert.equal(body.offset, 1);
});

integration('the listing rejects malformed query values', async () => {
  for (const query of ['offset=-1', 'limit=0', 'limit=101', 'week_from=abc', 'status=nope', 'sort=password_hash', 'dir=diagonal']) {
    const response = await app().inject({ method: 'GET', url: `/v1/admin/level-tests/weeks?${query}`, headers: await session() });
    assert.equal(response.statusCode, 400, query);
    assert.equal(response.json().error?.code, 'invalid_request', query);
  }
});

integration('the listing is admin-only and page-origin gated', async () => {
  const noAuth = await app().inject({ method: 'GET', url: '/v1/admin/level-tests/weeks', headers });
  assert.equal(noAuth.statusCode, 401);
  const foreignOrigin = await app().inject({ method: 'GET', url: '/v1/admin/level-tests/weeks',
    headers: { ...headers, cookie: (await session()).cookie, origin: 'https://evil.example' } });
  assert.equal(foreignOrigin.statusCode, 403);
});

integration('the week detail returns full content', async () => {
  const [week] = await seed([{ language: 'en', status: 'approved', approved: true }]);
  const response = await app().inject({ method: 'GET', url: `/v1/admin/level-tests/weeks/${week}`, headers: await session() });
  assert.equal(response.statusCode, 200);
  const body = response.json();
  assert.equal(body.languageID, 'en');
  assert.ok('pool' in body && Array.isArray(body.variants) && Array.isArray(body.review));
  // The review page header is one request: tokens, dates and versions travel
  // with the detail instead of a second round trip.
  assert.equal(typeof body.tokensIn, 'number');
  assert.equal(typeof body.tokensOut, 'number');
  assert.equal(typeof body.createdAt, 'string');
  assert.ok('promptVersion' in body && 'judgeVersion' in body);
  assert.ok('approvedAt' in body && 'approvedBy' in body);
});

integration('the week detail answers 404 for unknown or malformed ids', async () => {
  const unknown = await app().inject({ method: 'GET', url: `/v1/admin/level-tests/weeks/${randomUUID()}`, headers: await session() });
  assert.equal(unknown.statusCode, 404);
  assert.equal(unknown.json().error?.code, 'level_test_week_not_found');
  const malformed = await app().inject({ method: 'GET', url: '/v1/admin/level-tests/weeks/not-a-uuid', headers: await session() });
  assert.equal(malformed.statusCode, 400);
});

const post = async (path: string, payload: Record<string, unknown>, override: Record<string, string> = {}) =>
  app().inject({ method: 'POST', url: path, headers: { ...(await session()), ...override }, payload });

integration('regenerate accepts the current unpublished week and runs a job', async () => {
  await seed([{ language: 'en', level: 'A1', status: 'draft' }]);
  const response = await post('/v1/admin/level-tests/regenerate',
    { languageID: 'en', level: 'A1', reason: 'rebuilding weak listening pool' });
  assert.equal(response.statusCode, 202);
  assert.equal(response.json().started, true);
  assert.match(response.json().runCorrelationId, /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
  await new Promise(resolve => setTimeout(resolve, 300));
  const jobs = (await db!.query(`SELECT state FROM level_test_generation_jobs
    WHERE language_id='en' AND level='A1' AND week_index=$1`, [CURRENT])).rows;
  assert.ok(jobs.length >= 1);
  const audit = (await db!.query("SELECT detail FROM admin_actions WHERE action='level_test.regenerate'")).rows;
  assert.equal(audit.length, 1);
});

integration('regenerate refuses an approved key with 409 and no audit', async () => {
  await seed([{ language: 'es', level: 'B1', status: 'approved', approved: true }]);
  const response = await post('/v1/admin/level-tests/regenerate',
    { languageID: 'es', level: 'B1', reason: 'trying an approved week' });
  assert.equal(response.statusCode, 409);
  assert.equal(response.json().error?.code, 'level_test_week_not_regenerable');
  assert.equal((await db!.query("SELECT count(*)::int AS n FROM admin_actions WHERE action='level_test.regenerate'")).rows[0].n, 0);
});

integration('regenerate refuses past weeks, short reasons and unknown keys', async () => {
  for (const [payload, code] of [
    [{ languageID: 'en', level: 'A1', weekIndex: CURRENT - 3, reason: 'regenerate a past week' }, 'invalid_request'],
    [{ languageID: 'en', level: 'A1', reason: 'ab' }, 'invalid_request'],
    [{ languageID: 'zh', level: 'C2', reason: 'unknown combination' }, 'invalid_request'],
    [{ languageID: 'en', level: 'A1' }, 'invalid_request'],
  ] as [Record<string, unknown>, string][]) {
    const response = await post('/v1/admin/level-tests/regenerate', payload);
    assert.equal(response.statusCode, 400, JSON.stringify(payload));
    assert.equal(response.json().error?.code, code);
  }
});

integration('regenerate is csrf-gated and admin-only', async () => {
  const foreign = await post('/v1/admin/level-tests/regenerate', { languageID: 'en', level: 'A1', reason: 'origin check' },
    { origin: 'https://evil.example' });
  assert.equal(foreign.statusCode, 403);
  const unauth = await app().inject({ method: 'POST', url: '/v1/admin/level-tests/regenerate',
    headers, payload: { languageID: 'en', level: 'A1', reason: 'no session' } });
  assert.equal(unauth.statusCode, 401);
});

integration('generation launch starts missing keys and skips approved ones', async () => {
  await seed([{ language: 'en', level: 'A1', status: 'approved', approved: true }]);
  const response = await post('/v1/admin/level-tests/generate',
    { languageID: 'en', levels: 'all', reason: 'lanzamiento semanal de todos los niveles' });
  assert.equal(response.statusCode, 202);
  const body = response.json();
  assert.equal(body.started, true);
  assert.equal(body.keys.find((key: { level: string }) => key.level === 'A1')?.status, 'skipped_approved');
  assert.equal(body.keys.filter((key: { status: string }) => key.status === 'started').length, 4);
  for (let i = 0; i < 20; i++) {
    const jobs = (await db!.query("SELECT count(*)::int AS n FROM level_test_generation_jobs WHERE language_id='en'")).rows[0].n;
    if (jobs >= 4) break;
    await new Promise(resolve => setTimeout(resolve, 150));
  }
  const jobs = (await db!.query("SELECT level FROM level_test_generation_jobs WHERE language_id='en' ORDER BY level")).rows;
  assert.deepEqual(jobs.map(job => job.level), ['A2', 'B1', 'B2', 'C1']);
  const audit = (await db!.query("SELECT count(*)::int AS n FROM admin_actions WHERE action='level_test.generate'")).rows[0].n;
  assert.equal(audit, 1);
});

integration('generation launch skips weeks that already exist instead of reporting them as generated', async () => {
  await seed([
    { language: 'en', level: 'A1', status: 'draft' },
    { language: 'en', level: 'A2', status: 'rejected' },
  ]);
  const response = await post('/v1/admin/level-tests/generate',
    { languageID: 'en', levels: ['A1', 'A2', 'B1'], reason: 'lanzamiento con exámenes ya existentes' });
  assert.equal(response.statusCode, 202);
  const body = response.json();
  assert.equal(body.keys.find((key: { level: string }) => key.level === 'A1')?.status, 'skipped_existing');
  assert.equal(body.keys.find((key: { level: string }) => key.level === 'A2')?.status, 'skipped_existing');
  assert.equal(body.keys.find((key: { level: string }) => key.level === 'B1')?.status, 'started');
  for (let i = 0; i < 20; i++) {
    const rows = (await db!.query("SELECT level FROM level_test_generation_jobs WHERE language_id='en'")).rows;
    if (rows.some(row => row.level === 'B1')) break;
    await new Promise(resolve => setTimeout(resolve, 150));
  }
  // No fake "finished" job may appear for the skipped weeks: the operator would
  // read it as a completed generation that never happened.
  const jobs = (await db!.query("SELECT level FROM level_test_generation_jobs WHERE language_id='en' ORDER BY level")).rows;
  assert.deepEqual(jobs.map(row => row.level), ['B1']);
});

integration('generation launch accepts a single level', async () => {
  const response = await post('/v1/admin/level-tests/generate',
    { languageID: 'es', levels: ['B1'], reason: 'single level launch' });
  assert.equal(response.statusCode, 202);
  const key = response.json().keys[0];
  assert.deepEqual({ languageID: key.languageID, level: key.level, status: key.status },
    { languageID: 'es', level: 'B1', status: 'started' });
  assert.match(key.runCorrelationId, /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
  assert.equal('runId' in key, false, 'the 202 must not claim a run ID before its row is persisted');
  let run: { id: string; attempts: number } | undefined;
  for (let i = 0; i < 20; i++) {
    run = (await db!.query('SELECT id, attempts FROM level_test_generation_jobs WHERE id=$1', [key.runCorrelationId])).rows[0];
    if (run) break;
    await new Promise(resolve => setTimeout(resolve, 50));
  }
  assert.deepEqual(run, { id: key.runCorrelationId, attempts: 1 }, 'the exact correlation must become the persisted run UUID');
});

integration('generation launch validates language, levels, week and reason', async () => {
  for (const payload of [
    { languageID: 'xx', levels: 'all', reason: 'invalid language code' },
    { languageID: 'en', levels: ['Z9'], reason: 'invalid cefr level' },
    { languageID: 'en', levels: 'all', reason: 'ab' },
    { languageID: 'en', levels: 'all', weekIndex: CURRENT - 3, reason: 'past week launch' },
  ]) {
    const response = await post('/v1/admin/level-tests/generate', payload);
    assert.equal(response.statusCode, 400, JSON.stringify(payload));
    assert.equal(response.json().error?.code, 'invalid_request');
  }
  const unauth = await app().inject({ method: 'POST', url: '/v1/admin/level-tests/generate',
    headers, payload: { languageID: 'en', reason: 'no session here' } });
  assert.equal(unauth.statusCode, 401);
});

integration('approval is refused while a regeneration holds the generation lock', async () => {  const [id] = await seed([{ language: 'en', level: 'A1', status: 'draft' }]);
  const holder = connectDatabase(url!);
  const key = `level-test-generation:en:A1:${CURRENT}`;
  await holder.query('SELECT pg_advisory_lock(hashtextextended($1, 0))', [key]);
  try {
    await assert.rejects(
      approveGeneratedWeek(db!, id!, '00000000-0000-4000-8000-000000000009', 'approve while regenerating'),
      (error: { code?: string }) => error.code === 'level_test_regeneration_in_progress');
  } finally {
    await holder.query('SELECT pg_advisory_unlock(hashtextextended($1, 0))', [key]);
    await holder.end();
  }
  // With the lock released the guard no longer interferes (the empty pool
  // draft is simply not approvable).
  await assert.rejects(
    approveGeneratedWeek(db!, id!, '00000000-0000-4000-8000-000000000009', 'approve after lock released'),
    (error: { code?: string }) => error.code === 'level_test_contract_required');
});

integration('the status endpoint exposes the last generation jobs', async () => {
  for (const level of ['A1', 'A2']) await runLevelTestGenerationJob(db!, {}, { languageID: 'en', level, weekIndex: CURRENT, origin: 'user_operator' })
    .catch(() => undefined);
  const response = await app().inject({ method: 'GET', url: '/v1/admin/level-tests/status', headers: await session() });
  const body = response.json();
  assert.ok(Array.isArray(body.jobs));
  assert.equal(body.jobs.length, 2);
  assert.ok(body.jobs[0].languageID === 'en');
  assert.ok('latestJob' in body);
  // A red job must explain itself: the coarse code plus the sanitized reasons.
  const failed = body.jobs.find((job: { status: string }) => job.status === 'failed');
  assert.equal(failed.errorCode, 'generation_failed');
  assert.ok(Array.isArray(failed.errors) && failed.errors.length === 1);
  assert.match(failed.errors[0], /^[a-z0-9_.,()%:\- ]+$/);
});

integration('an accepted but unregistered generation remains pending rather than matching an older terminal job', async () => {
  const runWeek = CURRENT;
  const lockKey = `level-test-generation:en:A1:${CURRENT}`;
  const holder = await db!.connect();
  const oldRunID = randomUUID();
  try {
    await db!.query(`INSERT INTO level_test_generation_jobs(id,language_id,level,week_index,state,attempts,error_code,started_at,finished_at,updated_at)
      VALUES($1,'en','A1',$2,'failed',3,'generation_failed',now()-interval '2 hours',now()-interval '1 hour',now()-interval '1 hour')`,
    [oldRunID, runWeek]);
    await holder.query('SELECT pg_advisory_lock(hashtextextended($1,0))', [lockKey]);
    const response = await post('/v1/admin/level-tests/generate',
      { languageID: 'en', levels: ['A1'], reason: 'correlate the accepted request' });
    assert.equal(response.statusCode, 202);
    const key = response.json().keys[0];
    assert.notEqual(key.runCorrelationId, oldRunID);
    const status = await app().inject({ method: 'GET',
      url: `/v1/admin/level-tests/status?runIds=${encodeURIComponent(key.runCorrelationId)}`, headers: await session() });
    assert.equal(status.statusCode, 200);
    assert.deepEqual(status.json().trackedRuns, [{ correlationId: key.runCorrelationId, registration: 'pending' }]);
    assert.equal((await db!.query('SELECT 1 FROM level_test_generation_jobs WHERE id=$1', [key.runCorrelationId])).rowCount, 0);
  } finally {
    await holder.query('SELECT pg_advisory_unlock(hashtextextended($1,0))', [lockKey]);
    holder.release();
  }
});

integration('exact generation status resolves a persisted run after it leaves the ten-job recent window', async () => {
  const runID = randomUUID(), targetWeek = CURRENT - 2;
  await db!.query(`INSERT INTO level_test_generation_jobs(id,language_id,level,week_index,state,attempts,error_code,started_at,finished_at,updated_at)
    VALUES($1,'en','A1',$2,'failed',7,'generation_failed',now()-interval '2 days',now()-interval '1 day',now()-interval '1 day')`,
  [runID, targetWeek]);
  for (let i = 0; i < 11; i++) {
    await db!.query(`INSERT INTO level_test_generation_jobs(language_id,level,week_index,state,attempts,started_at,finished_at,updated_at)
      VALUES('es','B2',$1,'finished',$2,now(),now(),now()+interval '1 day')`, [CURRENT + i, i + 1]);
  }
  const response = await app().inject({ method: 'GET',
    url: `/v1/admin/level-tests/status?runIds=${encodeURIComponent(runID)}`, headers: await session() });
  assert.equal(response.statusCode, 200);
  const body = response.json();
  assert.equal(body.jobs.some((job: { id?: string }) => job.id === runID), false);
  assert.ok(Array.isArray(body.trackedRuns), 'exact lookups must be returned separately from the recent jobs window');
  const tracked = body.trackedRuns[0];
  assert.equal(tracked.correlationId, runID);
  assert.equal(tracked.registration, 'registered');
  assert.deepEqual({ runId: tracked.run.runId, attemptOrdinal: tracked.run.attemptOrdinal,
    languageID: tracked.run.languageID, level: tracked.run.level,
    weekId: tracked.run.weekId, status: tracked.run.status,
    errorCode: tracked.run.errorCode, errors: tracked.run.errors }, {
    runId: runID, attemptOrdinal: 7, languageID: 'en', level: 'A1', weekId: targetWeek, status: 'failed',
    errorCode: 'generation_failed', errors: [],
  });
  assert.ok(Date.parse(tracked.run.startedAt));
  assert.ok(Date.parse(tracked.run.finishedAt));
  assert.ok(Date.parse(tracked.run.updatedAt));
});

integration('regeneration after a prior terminal run reports the new persisted attempt only', async () => {
  await seed([{ language: 'en', level: 'A2', status: 'rejected' }]);
  const oldRunID = randomUUID();
  await db!.query(`INSERT INTO level_test_generation_jobs(id,language_id,level,week_index,state,attempts,error_code,started_at,finished_at,updated_at)
    VALUES($1,'en','A2',$2,'failed',1,'generation_failed',now()-interval '2 hours',now()-interval '1 hour',now()-interval '1 hour')`,
  [oldRunID, CURRENT]);
  const response = await post('/v1/admin/level-tests/regenerate',
    { languageID: 'en', level: 'A2', reason: 'retry after previous failure' });
  assert.equal(response.statusCode, 202);
  const correlationId = response.json().runCorrelationId;
  assert.match(correlationId, /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
  assert.notEqual(correlationId, oldRunID);
  let status: any;
  for (let i = 0; i < 20; i++) {
    const result = await app().inject({ method: 'GET',
      url: `/v1/admin/level-tests/status?runIds=${encodeURIComponent(correlationId)}`, headers: await session() });
    status = result.json().trackedRuns?.[0];
    if (status?.registration === 'registered') break;
    await new Promise(resolve => setTimeout(resolve, 50));
  }
  assert.equal(status?.run?.runId, correlationId);
  assert.equal(status?.run?.attemptOrdinal, 2);
  assert.notEqual(status?.run?.runId, oldRunID);
});

integration('concurrent same-key launch correlations never resolve to another request run', async () => {
  const responses = await Promise.all([1, 2].map(() => post('/v1/admin/level-tests/generate',
    { languageID: 'en', levels: ['C1'], reason: 'concurrent same-key request' })));
  assert.deepEqual(responses.map(response => response.statusCode), [202, 202]);
  const ids = responses.map(response => response.json().keys[0].runCorrelationId);
  assert.notEqual(ids[0], ids[1]);
  const result = await app().inject({ method: 'GET', url: `/v1/admin/level-tests/status?runIds=${ids.join(',')}`,
    headers: await session() });
  assert.equal(result.statusCode, 200);
  const runs = result.json().trackedRuns;
  assert.deepEqual(runs.map((entry: any) => entry.correlationId), ids);
  for (const entry of runs) {
    if (entry.registration === 'registered') assert.equal(entry.run.runId, entry.correlationId);
    else assert.deepEqual(entry, { correlationId: entry.correlationId, registration: 'pending' });
  }
});

integration('retiring an approved week stores the operator reason and audits once', async () => {
  const [id] = await seed([{ language: 'en', level: 'B2', status: 'approved', approved: true }]);
  const response = await post(`/v1/admin/level-tests/${id}/retire`, { reason: 'Contenido invalidado tras revisión' });
  assert.equal(response.statusCode, 200);
  assert.deepEqual(response.json(), { generationId: id, retired: true, alreadyRetired: false });
  const admin = (await db!.query('SELECT id FROM admins')).rows[0].id as string;
  const row = (await db!.query(`SELECT status, retired_at, retired_by, retired_reason
    FROM level_test_weeks WHERE id=$1`, [id])).rows[0];
  assert.equal(row.status, 'retired');
  assert.ok(row.retired_at instanceof Date);
  assert.equal(row.retired_by, admin);
  assert.equal(row.retired_reason, 'Contenido invalidado tras revisión');
  const audit = (await db!.query("SELECT reason FROM admin_actions WHERE action='level_test.retire'")).rows;
  assert.equal(audit.length, 1);
  assert.equal(audit[0].reason, 'Contenido invalidado tras revisión');
  // The review page reads the motive from the week itself, not only from audit.
  const detail = (await app().inject({ method: 'GET', url: `/v1/admin/level-tests/weeks/${id}`,
    headers: await session() })).json();
  assert.equal(detail.retiredReason, 'Contenido invalidado tras revisión');
  assert.equal(detail.retiredBy, admin);
  // Replay is safe and never audits a second retirement.
  const replay = await post(`/v1/admin/level-tests/${id}/retire`, { reason: 'Retirar otra vez' });
  assert.equal(replay.statusCode, 200);
  assert.equal(replay.json().alreadyRetired, true);
  assert.equal((await db!.query("SELECT count(*)::int AS n FROM admin_actions WHERE action='level_test.retire'")).rows[0].n, 1);
});

integration('retire refuses drafts, unknown ids and short reasons', async () => {
  const [draft] = await seed([{ language: 'en', level: 'A1', status: 'draft' }]);
  const conflict = await post(`/v1/admin/level-tests/${draft}/retire`, { reason: 'not approved yet' });
  assert.equal(conflict.statusCode, 409);
  assert.equal(conflict.json().error?.code, 'level_test_not_retirable');
  const unknown = await post(`/v1/admin/level-tests/${randomUUID()}/retire`, { reason: 'unknown week' });
  assert.equal(unknown.statusCode, 404);
  assert.equal(unknown.json().error?.code, 'level_test_not_found');
  const short = await post(`/v1/admin/level-tests/${draft}/retire`, { reason: 'ab' });
  assert.equal(short.statusCode, 400);
  assert.equal(short.json().error?.code, 'admin_reason_required');
  assert.equal((await db!.query("SELECT count(*)::int AS n FROM admin_actions WHERE action='level_test.retire'")).rows[0].n, 0);
});

integration('retire is csrf-gated and admin-only', async () => {
  const [id] = await seed([{ language: 'es', level: 'A2', status: 'approved', approved: true }]);
  const url = `/v1/admin/level-tests/${id}/retire`;
  const foreign = await post(url, { reason: 'origin check' }, { origin: 'https://evil.example' });
  assert.equal(foreign.statusCode, 403);
  const unauth = await app().inject({ method: 'POST', url, headers, payload: { reason: 'no session here' } });
  assert.equal(unauth.statusCode, 401);
});
