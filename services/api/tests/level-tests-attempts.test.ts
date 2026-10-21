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
    await db.query('TRUNCATE level_test_attempts, level_test_attempt_snapshots, level_test_attempt_snapshot_ids, admin_actions, admin_sessions, admins, accounts CASCADE');
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
const seedAccount = async (email: string) => {
  const id = randomUUID();
  await db!.query('INSERT INTO accounts(id,email,is_guest) VALUES($1,$2,false)', [id, email]);
  return id;
};
const seedExam = async (accountID: string, language = 'en', level = 'A1', week = CURRENT, source = 'llm') => {
  const examID = randomUUID();
  const content = { languageID: language, level, weekId: week, version: 1, thresholds: { pass: 60 }, source,
    passages: [], readingPassageIds: [], listeningPassageIds: [],
    items: [{ id: 'i1', section: 'recognition', kind: 'choice', prompt: 'p', answers: ['a'] }] };
  const hash = digest(JSON.stringify(content));
  await db!.query(`INSERT INTO level_test_attempt_snapshots(account_id,content_id,content_hash,content_json)
    VALUES($1,$2,$3,$4)`, [accountID, `sha256:${hash}`, hash,
    JSON.stringify({ ...content, contentId: `sha256:${hash}`, contentHash: hash })]);
  await db!.query(`INSERT INTO level_test_attempt_snapshot_ids(account_id,exam_id,content_id,metadata_json)
    VALUES($1,$2,$3,$4)`, [accountID, examID, `sha256:${hash}`,
    JSON.stringify({ examId: examID, contentId: `sha256:${hash}`, contentHash: hash,
      contentRevision: 1, gradingVersion: 1, source })]);
  return examID;
};
const seedAttempt = async (accountID: string, examID: string,
  options: { state?: string; overall?: number; passed?: boolean; daysAgo?: number } = {}) => {
  const attemptID = randomUUID(), state = options.state ?? 'completed';
  const started = new Date(Date.now() - (options.daysAgo ?? 0) * 86_400_000);
  const result = state === 'completed'
    ? JSON.stringify({ overall: options.overall ?? 70, passed: options.passed ?? true,
      sections: [{ section: 'recognition', score: options.overall ?? 70 }] }) : null;
  await db!.query(`INSERT INTO level_test_attempts(account_id,attempt_id,exam_id,state,position,revision,
    started_at,updated_at,completed_at,answers,result,attempt_json)
    VALUES($1,$2,$3,$4,0,1,$5,$6,$7,'[]'::jsonb,$8::jsonb,'{}'::jsonb)`,
    [accountID, attemptID, examID, state, started, started, state === 'completed' ? started : null, result]);
  return attemptID;
};

integration('the listing shows attempts with student and content metadata, answers excluded', async () => {
  const account = await seedAccount('alice@example.test');
  const exam = await seedExam(account, 'en', 'A1', CURRENT - 1);
  const attempt = await seedAttempt(account, exam, { overall: 82, passed: true });
  await seedAttempt(account, exam, { state: 'in_progress' });
  const response = await app().inject({ method: 'GET', url: '/v1/admin/level-test-attempts', headers: await session() });
  assert.equal(response.statusCode, 200);
  assert.equal(response.headers['cache-control'], 'no-store');
  const body = response.json();
  assert.equal(body.total, 2);
  const completed = body.items.find((item: { attemptId: string }) => item.attemptId === attempt);
  assert.equal(completed.email, 'alice@example.test');
  assert.equal(completed.languageID, 'en');
  assert.equal(completed.level, 'A1');
  assert.equal(completed.weekId, CURRENT - 1);
  assert.equal(completed.source, 'llm');
  assert.equal(completed.overall, 82);
  assert.equal(completed.passed, true);
  assert.ok(!('answers' in completed) && !('questionReviews' in completed));
});

integration('the listing filters by account, email, language, level, state and passed', async () => {
  const alice = await seedAccount('alice@example.test'), bob = await seedAccount('bob@example.test');
  const examA = await seedExam(alice), examB = await seedExam(bob, 'es', 'B1');
  await seedAttempt(alice, examA, { overall: 40, passed: false });
  await seedAttempt(bob, examB, { overall: 90, passed: true });
  const get = async (query: string) => (await app().inject({ method: 'GET',
    url: `/v1/admin/level-test-attempts?${query}`, headers: await session() })).json();
  assert.equal((await get(`account=${alice}`)).total, 1);
  assert.equal((await get('email=alice')).total, 1);
  assert.equal((await get('language=es')).total, 1);
  assert.equal((await get('level=A1')).total, 1);
  assert.equal((await get('passed=true')).total, 1);
  assert.equal((await get('state=in_progress')).total, 0);
});

integration('the listing filters week and date ranges, sorts and paginates', async () => {
  const account = await seedAccount('carol@example.test');
  const exam = await seedExam(account, 'en', 'A1', CURRENT - 1);
  await seedAttempt(account, exam, { overall: 50, daysAgo: 5 });
  await seedAttempt(account, exam, { overall: 80, daysAgo: 1 });
  const get = async (query: string) => (await app().inject({ method: 'GET',
    url: `/v1/admin/level-test-attempts?${query}`, headers: await session() })).json();
  assert.equal((await get(`week_from=${CURRENT - 1}`)).total, 2);
  assert.equal((await get('from=2026-01-01')).total, 2);
  const byOverall = await get('sort=overall&dir=asc&limit=1');
  assert.equal(byOverall.items[0].overall, 50);
  assert.equal(byOverall.total, 2);
});

integration('the listing rejects malformed query values', async () => {
  for (const query of ['state=nope', 'passed=maybe', `account=${randomUUID()}x`, 'sort=hack', 'limit=0', 'offset=-5']) {
    const response = await app().inject({ method: 'GET', url: `/v1/admin/level-test-attempts?${query}`, headers: await session() });
    assert.equal(response.statusCode, 400, query);
    assert.equal(response.json().error?.code, 'invalid_request', query);
  }
});

integration('results are admin-only and page-origin gated', async () => {
  const noAuth = await app().inject({ method: 'GET', url: '/v1/admin/level-test-attempts', headers });
  assert.equal(noAuth.statusCode, 401);
  const foreign = await app().inject({ method: 'GET', url: '/v1/admin/level-test-attempts',
    headers: { ...headers, cookie: (await session()).cookie, origin: 'https://evil.example' } });
  assert.equal(foreign.statusCode, 403);
});
