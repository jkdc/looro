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
const seed = async (rows: Partial<{ language: string; level: string; week: number; status: string; createdAt: Date; approved: boolean }>[]) => {
  const ids: string[] = [];
  for (const row of rows) {
    const id = randomUUID();
    ids.push(id);
    const pool = { recognition: [], writing: [], readingPassages: [], listeningPassages: [] };
    const variants = [{ variant: 1, items: [], passages: [] }];
    await db!.query(`INSERT INTO level_test_weeks(id, language_id, level, week_index, status, pool, variants,
      generator, model, prompt_version, judge_version, judge_mode, review, tokens_in, tokens_out, created_at)
      VALUES($1,$2,$3,$4,$5,$6,$7,'llm:opencode','glm-5.3-flash',1,1,'off','[]'::jsonb, 10, 20, $8)`,
      [id, row.language ?? 'en', row.level ?? 'A1', row.week ?? CURRENT, row.status ?? 'draft',
        JSON.stringify(pool), JSON.stringify(variants), row.createdAt ?? new Date()]);
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
    'judgeMode', 'languageID', 'level', 'model', 'status', 'tokensIn', 'tokensOut', 'variantCount', 'weekId']);
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
  assert.deepEqual(response.json(), { started: true });
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

integration('the status endpoint exposes the last generation jobs', async () => {
  for (const level of ['A1', 'A2']) await runLevelTestGenerationJob(db!, {}, { languageID: 'en', level, weekIndex: CURRENT })
    .catch(() => undefined);
  const response = await app().inject({ method: 'GET', url: '/v1/admin/level-tests/status', headers: await session() });
  const body = response.json();
  assert.ok(Array.isArray(body.jobs));
  assert.equal(body.jobs.length, 2);
  assert.ok(body.jobs[0].languageID === 'en');
  assert.ok('latestJob' in body);
});
