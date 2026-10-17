import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createApp } from '../src/app.js';
import type { Database } from '../src/db.js';
import { AuthAdmission } from '../src/auth-admission.js';
import { adminAuthConfig, adminCookieHeader } from '../src/admin-auth.js';
import { digest } from '../src/auth.js';
import { encryptSecret, maskKey, LlmTransportError } from '../src/llm-config.js';
import { readLlmConfigView, upsertLlmConfig } from '../src/llm-config-admin.js';
import { generateWeeklyLevelTest } from '../src/level-tests-llm.js';
import { identifySnapshot, type ExamSnapshot } from '../src/level-test-snapshot.js';

const NOW = new Date(), TOKEN = 'E'.repeat(43), ADMIN = '00000000-0000-4000-8000-000000000001';
const ENV = { LEVELTEST_LLM_PROVIDER: 'openai', LEVELTEST_LLM_API_KEY: 'fake-key',
  LEVELTEST_PUBLISH_MODE: 'auto', LEVELTEST_VERIFY: 'strict', LEVELTEST_VARIANTS: '2', LEVELTEST_LLM_MASTER_KEY: 'a'.repeat(64) };
const ADMIN_ENV = { ADMIN_HMAC_KEY: 'a'.repeat(64), ADMIN_PROXY_TOKEN: 'b'.repeat(64), ADMIN_PUBLIC_ORIGIN: 'https://admin.example' };
const headers = { cookie: adminCookieHeader(TOKEN), origin: ADMIN_ENV.ADMIN_PUBLIC_ORIGIN,
  'x-app-client-ip': '203.0.113.9', 'x-app-proxy-token': ADMIN_ENV.ADMIN_PROXY_TOKEN };
const item = (id: string, kind = 'choice') => ({ kind, prompt: `Question ${id}: she ___ a doctor.`,
  options: kind === 'choice' ? ['is', 'are', 'am'] : [], answers: ['is'], explain: 'With she, the verb is is.', unitID: 'en-foundations-u1' });
const paragraph = 'She is working at the local school and has a meeting today. The teacher will travel by train to visit her family after work. She has bought the ticket and will leave tomorrow morning.';
const pool = (count = 12) => ({
  recognition: Array.from({ length: count }, (_, i) => item(`recognition-${i}`)),
  writing: Array.from({ length: 3 }, (_, i) => item(`writing-${i}`, 'gap')),
  reading: Array.from({ length: 2 }, (_, p) => ({ title: `Reading ${p}`, text: `Reading story ${p}. ${paragraph}`,
    items: Array.from({ length: 3 }, (_, i) => item(`reading-${p}-${i}`)) })),
  listening: Array.from({ length: 2 }, (_, p) => ({ title: `Listening ${p}`, text: `Listening message ${p}. ${paragraph}`,
    items: Array.from({ length: 2 }, (_, i) => item(`listening-${p}-${i}`)) })),
});
type Verdict = { id: string; verdict: string; reason?: string };
const transport = (content = pool(), judge: (items: { id: string }[]) => unknown = items => ({
  items: items.map(item => ({ id: item.id, verdict: 'ok' })) })) =>
  async (_config: unknown, messages: { role: string; content: string }[]) => {
    const user = messages[1]!.content;
    const body = messages[0]!.content.includes('strict exam reviewer') ? judge(JSON.parse(user))
      : content[user.includes('vocabulary and grammar') ? 'recognition' : user.includes('reading passages')
        ? 'reading' : user.includes('listening passages') ? 'listening' : 'writing'];
    return { content: JSON.stringify(body), tokensIn: 1, tokensOut: 1 };
  };
type Row = Record<string, unknown> & { id: string; language_id: string; level: string; week_index: number;
  status: string; variants: Record<string, unknown>[] };
/** Fake external SQL only; generation, settings, admission/auth and HTTP handlers are real. */
function database() {
  const state = { weeks: [] as Row[], settings: undefined as Record<string, unknown> | undefined,
    config: undefined as Record<string, unknown> | undefined, audit: [] as unknown[][] };
  let saved: typeof state | undefined;
  const query = async (sql: string, v: unknown[] = []) => {
    const result = (rows: unknown[] = []) => ({ rows: structuredClone(rows), rowCount: rows.length });
    if (sql === 'BEGIN') { saved = structuredClone(state); return result(); }
    if (sql === 'ROLLBACK') { Object.assign(state, saved); return result(); }
    if (sql === 'COMMIT') { saved = undefined; return result(); }
    if (sql.includes('pg_advisory_xact_lock')) return result();
    if (sql === 'SELECT now()') return result([{ now: NOW }]);
    if (sql.startsWith('INSERT INTO auth_rate_limits')) return result([{ hits: 1 }]);
    if (sql.includes('FROM admin_sessions')) return result(v[0] === digest(TOKEN) ? [{ admin_id: ADMIN, email: 'operator@example.test' }] : []);
    if (sql.startsWith('INSERT INTO admin_actions')) { state.audit.push(v); return result(); }
    if (sql.includes('FROM llm_provider_configs')) return result(state.config ? [state.config] : []);
    if (sql.includes('FROM level_test_settings')) return result(state.settings ? [state.settings] : []);
    if (sql.startsWith('UPDATE llm_provider_configs')) return result();
    if (sql.startsWith('INSERT INTO llm_provider_configs')) {
      state.config = { provider: v[0], label: v[1], base_url: v[2], model: v[3], api_key_encrypted: v[4] }; return result(); }
    if (sql.startsWith('INSERT INTO level_test_settings')) {
      state.settings = { publish_mode: v[0], verify_mode: v[1], variants: v[2] }; return result(); }
    if (sql.startsWith('SELECT') && sql.includes('FROM level_test_weeks')) return result(sql.includes('WHERE id=')
      ? state.weeks.filter(row => row.id === v[0]) : state.weeks.filter(row => row.language_id === v[0]
        && row.level === v[1] && row.week_index === v[2] && (!sql.includes("status='approved'") || row.status === 'approved')));
    if (sql.startsWith('INSERT INTO level_test_weeks')) {
      state.weeks.push({ id: v[0] as string, language_id: v[1] as string, level: v[2] as string,
        week_index: v[3] as number, status: v[4] as string, pool: JSON.parse(v[5] as string), variants: JSON.parse(v[6] as string),
        judge_mode: v[11], review: JSON.parse(v[12] as string) }); return result(); }
    if (sql.startsWith('UPDATE level_test_weeks') && sql.includes("status='approved'")) {
      const row = state.weeks.find(row => row.id === v[0]);
      if (row) { row.status = 'approved'; row.approved_by = v[1]; } return result(row ? [row] : []); }
    throw new Error(`Unexpected SQL: ${sql}`);
  };
  return { state, db: { query, connect: async () => ({ query, release() {} }) } as unknown as Database };
}
const generate = (db: Database, env = ENV, call = transport()) =>
  generateWeeklyLevelTest({ db, env, transport: call }, { languageID: 'en', level: 'A1', now: NOW });

test('an unknown provider outcome is not retried by the section loop', async () => {
  const { db } = database();
  let calls = 0;
  const outcome = await generate(db, ENV, async () => {
    calls += 1;
    throw new LlmTransportError('llm_outcome_unknown');
  });
  assert.equal(outcome.status, 'failed');
  assert.equal(calls, 1);
});

test('a scheduled generation preserves an existing draft unless force is explicit', async () => {
  const { db, state } = database();
  const draft = { id: 'draft-id', language_id: 'en', level: 'A1', week_index: 202642,
    status: 'draft', variants: [{ variant: 1, items: [], passages: [] }], pool: { recognition: [] } } as Row;
  state.weeks.push(draft);
  let calls = 0;
  const outcome = await generate(db, ENV, async () => {
    calls += 1;
    return { content: '{}', tokensIn: 0, tokensOut: 0 };
  });
  assert.equal(outcome.status, 'exists');
  assert.equal(calls, 0);
  assert.equal(state.weeks.length, 1);
  assert.equal(state.weeks[0]?.id, 'draft-id');
});

for (const mutation of ['invalid answer', 'unknown kind', 'short reading block']) test(`structural gate with judge off: ${mutation}`, async () => {
  const { db, state } = database(), content = pool();
  if (mutation === 'invalid answer') content.recognition[0]!.answers = ['not an option'];
  if (mutation === 'unknown kind') content.recognition[0]!.kind = 'alien';
  if (mutation === 'short reading block') content.reading.forEach(p => p.items.pop());
  assert.equal((await generate(db, { ...ENV, LEVELTEST_VERIFY: 'off' }, transport(content))).status, 'failed');
  assert.equal(state.weeks.length, 0);
});
for (const failure of ['transport', 'incomplete', 'duplicate', 'unknown']) test(`strict judge ${failure} blocks publication`, async () => {
  const { db, state } = database();
  const outcome = await generate(db, ENV, transport(pool(), items => {
    if (failure === 'transport') throw new Error('fake judge unavailable');
    const verdicts = items.map(item => ({ id: item.id, verdict: 'ok' }));
    if (failure === 'incomplete') verdicts.pop();
    if (failure === 'duplicate') verdicts.push(verdicts[0]!);
    if (failure === 'unknown') verdicts[0]!.id = 'unrequested';
    return { items: verdicts };
  }));
  assert.equal(outcome.status, 'failed'); assert.equal(state.weeks.length, 0);
});
test('strict removes rejects, keeps ids/unit references and composes exactly 25 surviving items', async () => {
  const { db, state } = database();
  assert.equal((await generate(db, ENV, transport(pool(13), items => ({ items: items.map(item => ({
    id: item.id, verdict: item.id === 'rec-0' ? 'reject' : 'ok' })) })))).status, 'approved');
  const items = state.weeks[0]!.variants[0]!.items as Record<string, unknown>[];
  assert.equal(items.length, 25); assert.ok(!items.some(item => item.id === 'rec-0'));
  assert.ok(items.every(item => item.unitID === 'en-foundations-u1'));
});
for (const mode of ['strict', 'flag']) test(`${mode} rejects cannot publish an undersized exam`, async () => {
  const { db, state } = database();
  assert.equal((await generate(db, { ...ENV, LEVELTEST_VERIFY: mode }, transport(pool(), items => ({
    items: items.map(item => ({ id: item.id, verdict: item.id === 'rec-0' ? 'reject' : 'ok' })) })))).status, 'failed');
  assert.equal(state.weeks.length, 0);
});
test('flag judge failure is advisory and visible in review', async () => {
  const { db, state } = database();
  assert.equal((await generate(db, { ...ENV, LEVELTEST_VERIFY: 'flag' }, transport(pool(), () => { throw new Error('fake unavailable'); }))).status, 'approved');
  assert.ok((state.weeks[0]!.review as Verdict[]).some(v => v.verdict === 'flagged' && v.reason));
});
test('strict flags are advisory when every item has a valid verdict', async () => {
  const { db, state } = database();
  assert.equal((await generate(db, ENV, transport(pool(), items => ({ items: items.map(item => ({
    id: item.id, verdict: item.id === 'rec-0' ? 'flagged' : 'ok', reason: 'review wording' })) })))).status, 'approved');
  assert.ok((state.weeks[0]!.review as Verdict[]).some(v => v.verdict === 'flagged'));
});
test('reading and listening judges receive the exact source passage', async () => {
  const { db } = database();
  const outcome = await generate(db, ENV, transport(pool(), items => ({ items: items.map(item => ({
    id: item.id, verdict: /^(rea|lis)-/.test(item.id) &&
      !(item as { passageText?: string }).passageText?.includes(paragraph) ? 'reject' : 'ok',
  })) })));
  assert.equal(outcome.status, 'approved');
});
test('empty optional environment URL/model use provider defaults in GET and partial PUT', async () => {
  const { db } = database(), env = { ...ENV, LEVELTEST_LLM_API_KEY: '', LEVELTEST_LLM_URL: '', LEVELTEST_LLM_MODEL: '' };
  const view = await readLlmConfigView(db, env);
  assert.equal(view.baseUrl, 'https://api.openai.com/v1'); assert.ok(view.model);
  await upsertLlmConfig(db, env, { apiKey: 'fake-new-key' });
  assert.deepEqual(await readLlmConfigView(db, env), { ...view, keyHint: maskKey('fake-new-key') });
});
test('GET and partial settings PUT preserve DB values through strict to flag to off', async () => {
  const { db, state } = database(); state.settings = { publish_mode: 'auto', verify_mode: 'strict', variants: 5 };
  const env = { ...ENV, LEVELTEST_PUBLISH_MODE: 'review', LEVELTEST_VARIANTS: '1' };
  assert.equal((await readLlmConfigView(db, env)).variants, 5);
  for (const verifyMode of ['flag', 'off']) {
    await upsertLlmConfig(db, env, { verifyMode }); const v = await readLlmConfigView(db, env);
    assert.deepEqual([v.publishMode, v.verifyMode, v.variants], ['auto', verifyMode, 5]); }
});
test('partial model PUT preserves provider, URL and encrypted credential', async () => {
  const { db, state } = database(), encrypted = encryptSecret('fake-provider-key', ENV)!;
  state.config = { provider: 'glm', label: 'Existing label', base_url: 'https://provider.example/v1', model: 'old', api_key_encrypted: encrypted };
  await upsertLlmConfig(db, ENV, { model: 'new' }); const v = await readLlmConfigView(db, ENV);
  assert.deepEqual([v.provider, v.baseUrl, v.model], ['glm', 'https://provider.example/v1', 'new']);
  assert.equal(state.config!.api_key_encrypted, encrypted);
});
test('blank API key fields preserve the saved credential on provider updates', async () => {
  for (const apiKey of ['', '  \t ']) {
    const { db, state } = database(), encrypted = encryptSecret('saved-provider-key', ENV)!;
    state.config = { provider: 'openai', label: 'Saved label', base_url: 'https://provider.example/v1', model: 'old', api_key_encrypted: encrypted };
    await upsertLlmConfig(db, ENV, { model: 'new', apiKey });
    const view = await readLlmConfigView(db, ENV);
    assert.equal(view.model, 'new'); assert.equal(state.config!.api_key_encrypted, encrypted);
  }
});
test('provider switching cannot implicitly forward an environment credential', async () => {
  const { db, state } = database();
  await assert.rejects(upsertLlmConfig(db, ENV, { provider: 'glm' }));
  assert.equal(state.config, undefined);
});
test('invalid settings do not partly persist a combined provider update', async () => {
  const { db, state } = database(); state.config = { provider: 'openai', base_url: 'https://provider.example/v1', model: 'old', api_key_encrypted: encryptSecret('fake-key', ENV) };
  await assert.rejects(upsertLlmConfig(db, ENV, { provider: 'openai', model: 'new', verifyMode: 'invalid' }));
  assert.equal(state.config!.model, 'old');
});
function adminApp(db: Database) { const config = adminAuthConfig(ADMIN_ENV)!;
  return createApp({ db, auth: {}, admin: { services: { db, config }, admission: new AuthAdmission(db, config) } }); }
test('authenticated settings save/reload reflects persisted values and retains omitted fields', async () => {
  const { db, state } = database(); state.settings = { publish_mode: 'auto', verify_mode: 'strict', variants: 5 };
  const app = adminApp(db);
  try {
    for (const verifyMode of ['flag', 'off']) {
      assert.equal((await app.inject({ method: 'PUT', url: '/v1/admin/llm-config', headers, payload: { verifyMode } })).statusCode, 200);
      const response = await app.inject({ method: 'GET', url: '/v1/admin/llm-config', headers });
      assert.equal(response.statusCode, 200);
      const view = response.json(); assert.deepEqual([view.publishMode, view.verifyMode, view.variants], ['auto', verifyMode, 5]);
    }
  } finally { await app.close(); }
});
test('approval cannot upgrade an incomplete strict draft', async () => {
  const { db, state } = database(); await generate(db, { ...ENV, LEVELTEST_PUBLISH_MODE: 'review' });
  (state.weeks[0]!.review as unknown[]).pop(); const app = adminApp(db);
  try {
    assert.equal((await app.inject({ method: 'POST', url: `/v1/admin/level-tests/${state.weeks[0]!.id}/approve`,
      headers, payload: { reason: 'Reviewed content' } })).statusCode, 409);
    assert.equal(state.weeks[0]!.status, 'draft'); assert.equal(state.audit.length, 0);
  } finally { await app.close(); }
});
test('draft is private until authenticated generation-level approval; replay audits once', async () => {
  const { db, state } = database(); assert.equal((await generate(db, { ...ENV, LEVELTEST_PUBLISH_MODE: 'review' })).status, 'draft');
  const url = `/v1/admin/level-tests/${state.weeks[0]!.id}/approve`, app = adminApp(db);
  try {
    const publicUrl = '/v1/level-tests/generated?language=en&level=A1&variant=1';
    assert.equal((await app.inject({ method: 'GET', url: publicUrl })).statusCode, 404);
    assert.equal((await app.inject({ method: 'POST', url, headers: { ...headers, cookie: '' }, payload: { reason: 'Reviewed content' } })).statusCode, 401);
    assert.equal((await app.inject({ method: 'POST', url, headers: { ...headers, origin: 'https://evil.example' }, payload: { reason: 'Reviewed content' } })).statusCode, 403);
    for (let i = 0; i < 2; i++) assert.equal((await app.inject({ method: 'POST', url, headers, payload: { reason: 'Reviewed content' } })).statusCode, 200);
    assert.equal(state.weeks[0]!.approved_by, ADMIN); assert.equal(state.audit.length, 1);
    assert.equal((await app.inject({ method: 'GET', url: publicUrl })).statusCode, 200);
  } finally { await app.close(); }
});
test('approval rejects tampered variants without publishing or auditing', async () => {
  const { db, state } = database(); await generate(db, { ...ENV, LEVELTEST_PUBLISH_MODE: 'review' });
  (state.weeks[0]!.variants[0]!.items as unknown[]).pop(); const app = adminApp(db);
  try { assert.equal((await app.inject({ method: 'POST', url: `/v1/admin/level-tests/${state.weeks[0]!.id}/approve`, headers,
    payload: { reason: 'Reviewed content' } })).statusCode, 409);
    assert.equal(state.weeks[0]!.status, 'draft'); assert.equal(state.audit.length, 0);
  } finally { await app.close(); }
});

for (const corruption of ['section', 'passage', 'variant']) test(`approval checks ${corruption} against the validated pool even with a matching hash`, async () => {
  const { db, state } = database(); await generate(db, { ...ENV, LEVELTEST_PUBLISH_MODE: 'review' });
  const row = state.weeks[0]!, exam = row.variants[0] as unknown as ExamSnapshot;
  if (corruption === 'section') {
    exam.items.find(item => item.section === 'recognition')!.section = 'writing';
    exam.items.find(item => item.section === 'writing' && item.id.startsWith('wri'))!.section = 'recognition';
  } else if (corruption === 'passage') exam.passages[0]!.text += ' Modified stimulus.';
  else exam.variant = -1;
  row.variants[0] = identifySnapshot(exam, 'llm', row.id) as unknown as Record<string, unknown>;
  const app = adminApp(db);
  try { assert.equal((await app.inject({ method: 'POST', url: `/v1/admin/level-tests/${row.id}/approve`,
    headers, payload: { reason: 'Reviewed content' } })).statusCode, 409);
    assert.equal(state.weeks[0]!.status, 'draft');
  } finally { await app.close(); }
});
