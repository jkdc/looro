import { syntheticSkill, syntheticJudgeSkill } from './assessment-fixtures.js';
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createApp } from '../src/app.js';
import type { Database } from '../src/db.js';
import { AuthAdmission } from '../src/auth-admission.js';
import { adminAuthConfig, adminCookieHeader } from '../src/admin-auth.js';
import { digest } from '../src/auth.js';
import { encryptSecret, maskKey, LlmTransportError } from '../src/llm-config.js';
import { weekIndex } from '../src/level-tests.js';
import { readLlmConfigView, upsertLlmConfig } from '../src/llm-config-admin.js';
import { generateWeeklyLevelTest, examModelFor } from '../src/level-tests-llm.js';
import { parseLevelTestSchedulerConfig } from '../src/level-tests-scheduler.js';
import { identifySnapshot, type ExamSnapshot } from '../src/level-test-snapshot.js';
import type { GeneratedPool } from '../src/level-tests-validate.js';

const NOW = new Date(), TOKEN = 'E'.repeat(43), ADMIN = '00000000-0000-4000-8000-000000000001';
// Generation windows are defined relative to the clock, never to a frozen week.
const THIS_WEEK = weekIndex(NOW);
const ENV = { LEVELTEST_LLM_PROVIDER: 'openai', LEVELTEST_LLM_API_KEY: 'fake-key',
  LEVELTEST_PUBLISH_MODE: 'auto', LEVELTEST_VERIFY: 'strict', LEVELTEST_VARIANTS: '2', LEVELTEST_LLM_MASTER_KEY: 'a'.repeat(64) };
const ADMIN_ENV = { ADMIN_HMAC_KEY: 'a'.repeat(64), ADMIN_PROXY_TOKEN: 'b'.repeat(64), ADMIN_PUBLIC_ORIGIN: 'https://admin.example' };
const headers = { cookie: adminCookieHeader(TOKEN), origin: ADMIN_ENV.ADMIN_PUBLIC_ORIGIN,
  'x-app-client-ip': '203.0.113.9', 'x-app-proxy-token': ADMIN_ENV.ADMIN_PROXY_TOKEN };
const item = (id: string, kind = 'choice') => ({ assessedSkill: syntheticSkill(id.split('-')[0] as 'recognition'|'writing'|'reading'|'listening', Number(id.split('-').at(-1))), kind, prompt: `Question ${id}: she ___ a doctor.`,
  options: kind === 'choice' ? ['is', 'are', 'am', 'be'] : [], answers: ['is'], explain: 'With she, the verb is is.', unitID: 'en-foundations-u1' });
const paragraph = 'She is working at the local school and has a meeting today. The teacher will travel by train to visit her family after work. She has bought the ticket and will leave tomorrow morning. Her sister is coming too, so they will have lunch together before the train leaves.';
const pool = (extraRecognition = 0) => ({
  recognition: Array.from({ length: 24 + extraRecognition }, (_, i) => item(`recognition-${i}`, i < 16 + extraRecognition ? 'choice' : 'gap')),
  writing: Array.from({ length: 8 }, (_, i) => item(`writing-${i}`, 'gap')),
  reading: Array.from({ length: 6 }, (_, p) => ({ title: `Reading ${p}`, text: `Reading story ${p}. ${paragraph}`,
    items: Array.from({ length: 3 }, (_, i) => item(`reading-${p}-${i}`)) })),
  listening: Array.from({ length: 6 }, (_, p) => ({ title: `Listening ${p}`, text: `Listening message ${p}. ${paragraph}`,
    items: Array.from({ length: 3 }, (_, i) => item(`listening-${p}-${i}`)) })),
});
type Verdict = { id: string; verdict: string; reason?: string };
const sectionTransport = () => transport();
const transport = (content = pool(), judge: (items: { id: string }[]) => unknown = items => ({
  items: items.map(item => ({ id: item.id, verdict: 'ok' })) })) =>
  async (_config: unknown, messages: { role: string; content: string }[]) => {
    const user = messages[1]!.content;
    const body = messages[0]!.content.includes('quality reviewer') || messages[0]!.content.includes('strict exam reviewer')
      ? (() => { const result = judge(Array.isArray(JSON.parse(user)) ? JSON.parse(user) : JSON.parse(user).items) as { items: { id: string; verdict: string; reasonCode?: string; evidence?: string; languageStatus?: string }[] };
        return { items: result.items.map(entry => ({ ...syntheticJudgeSkill((JSON.parse(user).items ?? JSON.parse(user)).find((item:{id:string})=>item.id===entry.id)), ...entry, languageStatus: Object.hasOwn(entry, 'languageStatus') ? entry.languageStatus : 'pass', reasonCode: entry.reasonCode ?? 'answer_key',
          evidence: Object.hasOwn(entry, 'evidence') ? entry.evidence : 'The marked answer matches the supplied sentence.' })) }; })()
      : content[user.includes('vocabulary and grammar') ? 'recognition' : user.includes('reading passages')
        ? 'reading' : user.includes('listening passages') ? 'listening' : 'writing'];
    return { content: JSON.stringify(body), tokensIn: 1, tokensOut: 1 };
  };
type Row = Record<string, unknown> & { id: string; language_id: string; level: string; week_index: number;
  status: string; variants: Record<string, unknown>[] };
/** Fake external SQL only; generation, settings, admission/auth and HTTP handlers are real. */
function database() {
  const state: { weeks: Row[]; jobs: Record<string, unknown>[]; settings?: Record<string, unknown>; config?: Record<string, unknown>; audit: unknown[][] } = { weeks: [] as Row[], jobs: [], settings: undefined as Record<string, unknown> | undefined,
    config: undefined as Record<string, unknown> | undefined, audit: [] as unknown[][] };
  let saved: typeof state | undefined;
  const query = async (sql: string, v: unknown[] = []) => {
    const result = (rows: unknown[] = []) => ({ rows: structuredClone(rows), rowCount: rows.length });
    if (sql === 'BEGIN') { saved = structuredClone(state); return result(); }
    if (sql === 'ROLLBACK') { Object.assign(state, saved); return result(); }
    if (sql === 'COMMIT') { saved = undefined; return result(); }
    if (sql.includes('pg_advisory_xact_lock')) return result();
    if (sql.includes('pg_try_advisory_xact_lock')) return result([{ locked: true }]);
    if (sql === 'SELECT now()') return result([{ now: NOW }]);
    if (sql.startsWith('INSERT INTO auth_rate_limits')) return result([{ hits: 1 }]);
    if (sql.includes('FROM admin_sessions')) return result(v[0] === digest(TOKEN) ? [{ admin_id: ADMIN, email: 'operator@example.test' }] : []);
    if (sql.startsWith('INSERT INTO admin_actions')) { state.audit.push(v); return result(); }
    if (sql.includes('FROM level_test_generation_jobs')) return result(state.jobs);
    if (sql.includes('FROM llm_provider_configs')) return result(state.config ? [state.config] : []);
    if (sql.includes('FROM level_test_settings')) return result(state.settings ? [state.settings] : []);
    if (sql.startsWith('UPDATE llm_provider_configs')) return result();
    if (sql.startsWith('INSERT INTO llm_provider_configs')) {
      state.config = { provider: v[0], label: v[1], base_url: v[2], model: v[3], api_key_encrypted: v[4] }; return result(); }
    if (sql.startsWith('INSERT INTO level_test_settings')) {
      state.settings = { publish_mode: v[0], verify_mode: v[1], variants: v[2] }; return result(); }
    if (sql.includes("status IN ('draft','rejected')")) return result([...state.weeks]
      .filter(row => row.status === 'draft' || row.status === 'rejected')
      .sort((a, b) => (b.created_at as Date).getTime() - (a.created_at as Date).getTime())
      .slice(v[1] as number, (v[1] as number) + (v[0] as number)));
    if (sql.startsWith('SELECT') && sql.includes('FROM level_test_weeks')) return result(sql.includes('WHERE id=')
      ? state.weeks.filter(row => row.id === v[0]) : state.weeks.filter(row => row.language_id === v[0]
        && row.level === v[1] && row.week_index === v[2] && (!sql.includes("status='approved'") || row.status === 'approved')));
    if (sql.startsWith('INSERT INTO level_test_weeks')) {
      state.weeks.push({ id: v[0] as string, language_id: v[1] as string, level: v[2] as string,
        week_index: v[3] as number, status: v[4] as string, pool: JSON.parse(v[5] as string), variants: JSON.parse(v[6] as string),
        prompt_version: v[9], judge_version: v[10], judge_mode: v[11], review: JSON.parse(v[12] as string),
        tokens_in: v[13], tokens_out: v[14], blueprint_version: v[18], blueprint_hash: v[19],
        blueprint_contract: v[20] == null ? null : JSON.parse(v[20] as string) }); return result(); }
    if (sql.startsWith("UPDATE level_test_weeks") && sql.includes("status=" + String.fromCharCode(39) + "retired" + String.fromCharCode(39))) {
      for (const row of state.weeks.filter(row => row.language_id === v[0] && row.level === v[1]
        && row.week_index === v[2] && row.status !== "retired")) row.status = "retired";
      return result();
    }
    if (sql.startsWith('UPDATE level_test_weeks') && sql.includes("status='rejected'")) {
      const row = state.weeks.find(row => row.id === v[0]);
      if (row) row.status = 'rejected';
      return result(row ? [row] : []);
    }
    if (sql.startsWith('UPDATE level_test_weeks') && sql.includes("status='approved'")) {
      const row = state.weeks.find(row => row.id === v[0]);
      if (row) { row.status = 'approved'; row.approved_by = v[1]; } return result(row ? [row] : []); }
    throw new Error(`Unexpected SQL: ${sql}`);
  };
  return { state, db: { query, connect: async () => ({ query, release() {} }) } as unknown as Database };
}
const generate = (db: Database, env = ENV, call: NonNullable<Parameters<typeof generateWeeklyLevelTest>[0]['transport']> = transport()) =>
  generateWeeklyLevelTest({ db, env, transport: call }, { languageID: 'en', level: 'A1', now: NOW, origin: 'automatic_worker' });

test('English judge-off generation creates only a private draft even in auto mode', async () => {
  const { db, state } = database();
  const result = await generate(db, { ...ENV, LEVELTEST_VERIFY: 'off' });
  assert.equal(result.status, 'draft');
  assert.equal(state.weeks[0]?.status, 'draft');
});

test('English manual approval cannot bypass the judge by switching its mode off', async () => {
  const { approveGeneratedWeek } = await import('../src/level-tests-publication.js');
  const { db, state } = database();
  await generate(db, { ...ENV, LEVELTEST_PUBLISH_MODE: 'review' });
  state.weeks[0]!.judge_mode = 'off';
  await assert.rejects(approveGeneratedWeek(db, state.weeks[0]!.id, ADMIN, 'Reviewed content'),
    { code: 'level_test_not_approvable' });
  assert.equal(state.weeks[0]?.status, 'draft');
  assert.equal(state.audit.length, 0);
});

test('English flag-mode manual approval requires bounded evidence for every retained item', async () => {
  const { approveGeneratedWeek } = await import('../src/level-tests-publication.js');
  const { db, state } = database();
  await generate(db, { ...ENV, LEVELTEST_VERIFY: 'flag', LEVELTEST_PUBLISH_MODE: 'review' });
  (state.weeks[0]!.review as Record<string, unknown>[])[0]!.reason = '';
  await assert.rejects(approveGeneratedWeek(db, state.weeks[0]!.id, ADMIN, 'Reviewed content'),
    { code: 'level_test_not_approvable' });
  assert.equal(state.weeks[0]?.status, 'draft');
  assert.equal(state.audit.length, 0);
});

for (const field of ['passage', 'explanation']) test(`English ${field} language cannot borrow evidence from English questions`, async () => {
  const { db, state } = database(), content = pool();
  if (field === 'passage') {
    for (const [index, passage] of [...content.reading, ...content.listening].entries())
      passage.text = `Texto ${index}. ` + 'La familia vive en una casa cerca de la escuela y trabaja todos los días. '.repeat(4);
  } else content.recognition[0]!.explain = 'La respuesta es correcta porque la frase describe el trabajo de ella.';
  const result = await generate(db, ENV, transport(content));
  assert.equal(result.status, 'failed');
  assert.equal(state.weeks.length, 0);
});

test('uncertain English names and short borrowed words remain judgeable rather than discarded', async () => {
  const { db, state } = database(), content = pool();
  content.recognition[0] = { ...content.recognition[0]!, prompt: 'Élodie: surname?',
    options: ['Dubois', 'Sato', 'Cruz', 'Li'], answers: ['Dubois'], explain: 'Dubois, not Élodie.' };
  content.recognition[1] = { ...content.recognition[1]!, prompt: 'Sushi: dish?',
    options: ['sushi', 'ramen', 'curry', 'tapas'], answers: ['sushi'], explain: 'Sushi, not ramen.' };
  assert.equal((await generate(db, ENV, transport(content))).status, 'approved');
  assert.ok((state.weeks[0]!.pool as GeneratedPool).recognition.some(item => item.prompt === 'Élodie: surname?'));
  assert.ok((state.weeks[0]!.pool as GeneratedPool).recognition.some(item => item.prompt === 'Sushi: dish?'));
});

test('individually uncertain city-name options are not pooled into false foreign-language evidence', async () => {
  const { db, state } = database(), content = pool();
  content.recognition[0] = { ...content.recognition[0]!, prompt: 'Which city is in California?',
    options: ['Los Angeles', 'Las Vegas', 'La Paz', 'El Paso'], answers: ['Los Angeles'],
    explain: 'Los Angeles is a city in California.' };
  const result = await generate(db, ENV, transport(content));
  assert.equal(result.status, 'approved');
  assert.ok((state.weeks[0]!.pool as GeneratedPool).recognition.some(item => item.prompt === 'Which city is in California?'));
});

test('non-English judge-off generation keeps its existing auto-publication and version behavior', async () => {
  const { db, state } = database(), content = pool();
  for (const [index, entry] of [...content.recognition, ...content.writing,
    ...content.reading.flatMap(p => p.items), ...content.listening.flatMap(p => p.items)].entries()) {
    entry.prompt = `Pregunta ${index}: ella ___ médica.`;
    entry.options = entry.kind === 'choice' ? ['es', 'son', 'estoy', 'eres'] : [];
    entry.answers = ['es']; entry.explain = 'Con ella usamos la forma es.';
  }
  for (const [index, passage] of [...content.reading, ...content.listening].entries())
    passage.text = `Historia ${index}. ` + 'La familia vive en una casa cerca de la escuela y trabaja todos los días. '.repeat(4);
  const result = await generateWeeklyLevelTest({ db, env: { ...ENV, LEVELTEST_VERIFY: 'off' }, transport: transport(content) },
    { languageID: 'es', level: 'A1', now: NOW, origin: 'automatic_worker' });
  assert.equal(result.status, 'approved');
  assert.equal(state.weeks[0]?.prompt_version, 2);
  assert.equal(state.weeks[0]?.judge_version, 2);
  assert.equal(state.weeks[0]?.blueprint_version, 1);
  assert.equal(state.weeks[0]?.blueprint_contract, null);
});

test('an ok judge verdict with unresolved language cannot publish an English exam', async () => {
  const { db, state } = database();
  const result = await generate(db, ENV, transport(pool(), items => ({ items: items.map(item => ({
    id: item.id, verdict: 'ok', languageStatus: 'unknown',
  })) })));
  assert.equal(result.status, 'failed');
  assert.equal(state.weeks.length, 0);
});

test('English draft approval uses its frozen contract even after the current blueprint changes', async () => {
  const { approveGeneratedWeek } = await import('../src/level-tests-publication.js');
  const { db, state } = database();
  await generate(db, { ...ENV, LEVELTEST_PUBLISH_MODE: 'review' });
  const current = examModelFor('A1', 'en')!, original = current.examShape.writing;
  current.examShape.writing += 1;
  try {
    const result = await approveGeneratedWeek(db, state.weeks[0]!.id, ADMIN, 'Reviewed content');
    assert.equal(result.approved, true);
    assert.equal(state.weeks[0]?.status, 'approved');
  } finally { current.examShape.writing = original; }
});

test('English legacy drafts without a complete frozen contract must be regenerated', async () => {
  const { approveGeneratedWeek } = await import('../src/level-tests-publication.js');
  const { db, state } = database();
  await generate(db, { ...ENV, LEVELTEST_PUBLISH_MODE: 'review' });
  delete state.weeks[0]!.blueprint_contract;
  await assert.rejects(approveGeneratedWeek(db, state.weeks[0]!.id, ADMIN, 'Reviewed content'),
    { code: 'level_test_contract_required' });
  assert.equal(state.weeks[0]?.status, 'draft');
  assert.equal(state.audit.length, 0);
});

test('English approval rejects a tampered frozen contract without approving or auditing', async () => {
  const { approveGeneratedWeek } = await import('../src/level-tests-publication.js');
  const { db, state } = database();
  await generate(db, { ...ENV, LEVELTEST_PUBLISH_MODE: 'review' });
  state.weeks[0]!.blueprint_hash = '0'.repeat(64);
  await assert.rejects(approveGeneratedWeek(db, state.weeks[0]!.id, ADMIN, 'Reviewed content'),
    { code: 'level_test_not_approvable' });
  assert.equal(state.weeks[0]?.status, 'draft');
  assert.equal(state.audit.length, 0);
});

test('generation freezes the complete blueprint before asynchronous provider work', async () => {
  const { db, state } = database(), current = examModelFor('A1', 'en')!;
  const original = current.examShape.writing;
  const reply = transport(); let changed = false;
  try {
    const result = await generate(db, ENV, async (config, messages) => {
      if (!changed) { changed = true; current.examShape.writing += 20; }
      return reply(config, messages);
    });
    assert.equal(result.status, 'approved');
    const frozen = state.weeks[0]!.blueprint_contract as typeof current;
    assert.equal(frozen.examShape.writing, 4);
    assert.equal(state.weeks[0]!.blueprint_version, 2);
  } finally { current.examShape.writing = original; }
});

test('JSONB key reordering does not invalidate a frozen English contract', async () => {
  const { approveGeneratedWeek } = await import('../src/level-tests-publication.js');
  const { db, state } = database();
  await generate(db, { ...ENV, LEVELTEST_PUBLISH_MODE: 'review' });
  const original = state.weeks[0]!.blueprint_contract;
  assert.ok(original);
  const reverseKeys = (value: unknown): unknown => Array.isArray(value) ? value.map(reverseKeys)
    : value && typeof value === 'object' ? Object.fromEntries(Object.entries(value).reverse().map(([key, entry]) => [key, reverseKeys(entry)])) : value;
  state.weeks[0]!.blueprint_contract = reverseKeys(original);
  assert.equal((await approveGeneratedWeek(db, state.weeks[0]!.id, ADMIN, 'Reviewed content')).approved, true);
});

test('a provider failure retains known usage from prior calls without reporting it as complete', async () => {
  const { db } = database(); let calls = 0;
  const reply = transport();
  const result = await generate(db, ENV, async (config, messages) => {
    if (++calls === 2) throw new LlmTransportError('llm_outcome_unknown');
    return { ...await reply(config, messages), tokensIn: 5, tokensOut: 7 };
  });
  assert.equal(result.status, 'failed');
  assert.equal(calls, 2);
  assert.equal(result.tokensIn, undefined);
  assert.deepEqual((result as unknown as { usage: unknown }).usage, { state: 'partial', tokensIn: 5, tokensOut: 7 });
});

for (const mode of ['complete', 'partial', 'unknown', 'zero'] as const) test(`generation distinguishes ${mode} provider usage`, async () => {
  const { db } = database(); const reply = transport(); let calls = 0;
  const result = await generate(db, ENV, async (config, messages) => {
    calls += 1;
    const value = await reply(config, messages);
    return { ...value, tokensIn: mode === 'unknown' || (mode === 'partial' && calls === 1) ? undefined : mode === 'zero' ? 0 : 2,
      tokensOut: mode === 'unknown' ? undefined : mode === 'zero' ? 0 : 3 };
  });
  assert.equal(result.status, 'approved');
  assert.equal(calls, 8);
  assert.deepEqual((result as unknown as { usage: unknown }).usage,
    mode === 'complete' ? { state: 'complete', tokensIn: 16, tokensOut: 24 } :
      mode === 'partial' ? { state: 'partial', tokensIn: 14, tokensOut: 24 } :
        mode === 'zero' ? { state: 'complete', tokensIn: 0, tokensOut: 0 } :
          { state: 'unknown', tokensIn: null, tokensOut: null });
});

test('admin generation status exposes partial known usage without converting missing input to zero', async () => {
  const { db, state } = database();
  state.jobs = [{ id: ADMIN, language_id: 'en', level: 'A1', week_index: THIS_WEEK, state: 'failed', attempts: 1,
    error_code: 'generation_failed', error_details: [], usage_state: 'partial', tokens_in: null, tokens_out: '12' }];
  const app = adminApp(db);
  try {
    const response = await app.inject({ method: 'GET', url: '/v1/admin/level-tests/status', headers });
    assert.equal(response.statusCode, 200);
    assert.deepEqual(response.json().latestJob.usage, { state: 'partial', tokensIn: null, tokensOut: 12 });
  } finally { await app.close(); }
});

test('missing provider usage remains unknown instead of a confirmed zero', async () => {
  const { db, state } = database();
  const reply = sectionTransport();
  const outcome = await generate(db, ENV, async (config, messages) => {
    const result = await reply(config, messages);
    return { content: result.content, tokensIn: undefined as unknown as number, tokensOut: undefined as unknown as number };
  });
  assert.equal(outcome.status, 'approved');
  assert.equal(outcome.tokensIn, undefined);
  assert.equal(outcome.tokensOut, undefined);
  assert.equal(state.weeks[0]!.tokens_in, null);
  assert.equal(state.weeks[0]!.tokens_out, null);
});

test('generation aggregates token usage across section and judge calls', async () => {
  const { db, state } = database();
  const outcome = await generate(db);
  assert.equal(outcome.status, 'approved');
  assert.ok((outcome.tokensIn ?? 0) > 0);
  assert.ok((outcome.tokensOut ?? 0) > 0);
  assert.equal(state.weeks[0]!.tokens_in, outcome.tokensIn);
  assert.equal(state.weeks[0]!.tokens_out, outcome.tokensOut);
});

test('generation persists the bumped English prompt and judge contract versions', async () => {
  const { db, state } = database();
  assert.equal((await generate(db)).status, 'approved');
  assert.equal(state.weeks[0]!.prompt_version, 4);
  assert.equal(state.weeks[0]!.judge_version, 5);
});

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
  const draft = { id: 'draft-id', language_id: 'en', level: 'A1', week_index: THIS_WEEK,
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

test("rejected week blocks generation unless force retires the row", async () => {
  const { db, state } = database();
  state.weeks.push({ id: "rejected-id", language_id: "en", level: "A1", week_index: THIS_WEEK,
    status: "rejected", variants: [{ variant: 1, items: [], passages: [] }], pool: { recognition: [] } } as Row);
  let calls = 0;
  const call = async (...args: Parameters<ReturnType<typeof transport>>) => { calls += 1; return transport()(...args); };
  const blocked = await generate(db, ENV, call);
  assert.equal(blocked.status, "exists");
  assert.equal(calls, 0);
  assert.equal(state.weeks[0]!.status, "rejected");
  const forced = await generateWeeklyLevelTest({ db, env: ENV, transport: call },
    { languageID: "en", level: "A1", now: NOW, force: true, origin: "automatic_worker" });
  assert.equal(forced.status, "approved");
  assert.equal(state.weeks[0]!.status, "retired");
  assert.equal(state.weeks.length, 2);
  assert.equal(calls > 0, true);
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
for (const field of ['reasonCode', 'evidence', 'missingEvidence', 'oversizedEvidence']) test(`strict judge rejects invalid ${field}`, async () => {
  const { db, state } = database();
  const outcome = await generate(db, ENV, transport(pool(), items => ({ items: items.map((item, index) => ({
    id: item.id, verdict: 'ok', ...(index === 0 ? field === 'reasonCode' ? { reasonCode: 'freeform' }
      : field === 'missingEvidence' ? { evidence: undefined }
        : field === 'oversizedEvidence' ? { evidence: 'x'.repeat(241) } : { evidence: '        ' } : {}),
  })) })));
  assert.equal(outcome.status, 'failed'); assert.equal(state.weeks.length, 0);
});
test('strict removes rejects, keeps ids/unit references and composes the 34-item A1 form', async () => {
  const { db, state } = database();
  assert.equal((await generate(db, ENV, transport(pool(1), items => ({ items: items.map(item => ({
    id: item.id, verdict: item.id === 'recognition-0' ? 'reject' : 'ok' })) })))).status, 'approved');
  const items = state.weeks[0]!.variants[0]!.items as Record<string, unknown>[];
  assert.equal(items.length, 34); assert.ok(!items.some(item => item.id === 'recognition-0'));
  assert.ok(items.every(item => item.unitID === 'en-foundations-u1'));
});
for (const mode of ['strict', 'flag']) test(`${mode} rejects cannot publish an undersized exam`, async () => {
  const { db, state } = database();
  assert.equal((await generate(db, { ...ENV, LEVELTEST_VERIFY: mode }, transport(pool(), items => ({
    items: items.map(item => ({ id: item.id, verdict: item.id.startsWith('rec-') && Number(item.id.slice(4)) < 13 ? 'reject' : 'ok' })) })))).status, 'failed');
  assert.equal(state.weeks.length, 0);
});
test('English generation fails closed when judge filtering leaves identical variants', async () => {
  const { db, state } = database();
  const outcome = await generate(db, ENV, transport(pool(), items => ({ items: items.map(item => {
    const id = item.id;
    const index = Number(id.slice(4));
    const reject = id.startsWith('rec-') ? index < 8 || index >= 16 && index < 20
      : id.startsWith('wri-') ? Number(id.slice(4)) < 4
      : /^(rea|lis)-/.test(id) ? Number(id.split('-')[1]) < 3 : false;
    return { id, verdict: reject ? 'reject' : 'ok' };
  }) })));
  assert.equal(outcome.status, 'failed');
  assert.ok(outcome.errors?.includes('insufficient_variant_diversity'));
  assert.equal(state.weeks.length, 0);
});

test('English generation fails closed when item variants share reading and listening stimuli', async () => {
  const { db, state } = database();
  const outcome = await generate(db, ENV, transport(pool(), items => ({ items: items.map(item => ({
    id: item.id, verdict: /^(rea|lis)-[0-2]-0$/.test(item.id) ? 'reject' : 'ok',
  })) })));

  assert.equal(outcome.status, 'failed');
  assert.ok(outcome.errors?.includes('insufficient_variant_stimulus_diversity'));
  assert.equal(state.weeks.length, 0);
});

test('English flag judge failure cannot publish unreviewed items as clean content', async () => {
  const { db, state } = database();
  assert.equal((await generate(db, { ...ENV, LEVELTEST_VERIFY: 'flag' }, transport(pool(), () => { throw new Error('fake unavailable'); }))).status, 'failed');
  assert.equal(state.weeks.length, 0);
});
test('strict flags are advisory when every item has a valid verdict', async () => {
  const { db, state } = database();
  assert.equal((await generate(db, ENV, transport(pool(), items => ({ items: items.map(item => ({
    id: item.id, verdict: item.id === 'rec-0' ? 'flagged' : 'ok', reason: 'review wording' })) })))).status, 'approved');
  assert.ok((state.weeks[0]!.review as Verdict[]).some(v => v.verdict === 'flagged'));
  assert.ok(!(state.weeks[0]!.pool as { recognition: { id: string }[] }).recognition.some(v => v.id === 'rec-0'));
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
function adminApp(db: Database, extra: Record<string, unknown> = {}) { const config = adminAuthConfig(ADMIN_ENV)!;
  return createApp({ db, auth: {}, ...extra, admin: { services: { db, config }, admission: new AuthAdmission(db, config) } }); }
test('admin LLM views use independent app environment objects without mutating process.env', async () => {
  const { db } = database();
  const originalProvider = process.env.LEVELTEST_LLM_PROVIDER;
  const appA = adminApp(db, { environment: { ...ENV, LEVELTEST_LLM_PROVIDER: 'glm',
    LEVELTEST_LLM_URL: 'https://provider-a.example/v1', LEVELTEST_LLM_MODEL: 'glm-model' } });
  const appB = adminApp(db, { environment: { ...ENV, LEVELTEST_LLM_PROVIDER: 'openai',
    LEVELTEST_LLM_URL: 'https://provider-b.example/v1', LEVELTEST_LLM_MODEL: 'openai-model' } });
  try {
    const read = async (app: ReturnType<typeof adminApp>) => {
      const response = await app.inject({ method: 'GET', url: '/v1/admin/llm-config', headers });
      assert.equal(response.statusCode, 200);
      return response.json();
    };
    assert.deepEqual(await read(appA).then(({ provider, baseUrl, model }) => ({ provider, baseUrl, model })),
      { provider: 'glm', baseUrl: 'https://provider-a.example/v1', model: 'glm-model' });
    assert.deepEqual(await read(appB).then(({ provider, baseUrl, model }) => ({ provider, baseUrl, model })),
      { provider: 'openai', baseUrl: 'https://provider-b.example/v1', model: 'openai-model' });
    assert.equal(process.env.LEVELTEST_LLM_PROVIDER, originalProvider);
  } finally { await appA.close(); await appB.close(); }
});
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
test('draft rejection is authenticated, retained, audited once, and replay-safe', async () => {
  const { db, state } = database();
  assert.equal((await generate(db, { ...ENV, LEVELTEST_PUBLISH_MODE: 'review' })).status, 'draft');
  const url = `/v1/admin/level-tests/${state.weeks[0]!.id}/reject`, app = adminApp(db);
  try {
    assert.equal((await app.inject({ method: 'POST', url, headers: { ...headers, cookie: '' }, payload: { reason: 'Not acceptable' } })).statusCode, 401);
    assert.equal((await app.inject({ method: 'POST', url, headers: { ...headers, origin: 'https://evil.example' }, payload: { reason: 'Not acceptable' } })).statusCode, 403);
    for (let i = 0; i < 2; i++) assert.equal((await app.inject({ method: 'POST', url, headers, payload: { reason: 'Not acceptable' } })).statusCode, 200);
    assert.equal(state.weeks[0]!.status, 'rejected');
    assert.equal(state.audit.length, 1);
  } finally { await app.close(); }
});

test('admin draft list is bounded, private, paginated, and excludes account attempts', async () => {
  const { db, state } = database();
  state.weeks.push(
    { id: '00000000-0000-4000-8000-000000000011', language_id: 'en', level: 'A1', week_index: THIS_WEEK, status: 'draft', variants: [], pool: {}, created_at: NOW, attempts: [{ accountID: 'private' }] } as Row,
    { id: '00000000-0000-4000-8000-000000000012', language_id: 'es', level: 'A1', week_index: THIS_WEEK, status: 'rejected', variants: [], pool: {}, created_at: new Date(NOW.getTime()-1000) } as Row,
  );
  const app = adminApp(db);
  try {
    const url = '/v1/admin/level-tests/drafts?limit=1&offset=0';
    assert.equal((await app.inject({ method: 'GET', url, headers: { ...headers, cookie: '' } })).statusCode, 401);
    assert.equal((await app.inject({ method: 'GET', url, headers: { ...headers, origin: 'https://evil.example' } })).statusCode, 403);
    const response = await app.inject({ method: 'GET', url, headers });
    assert.equal(response.statusCode, 200);
    const body = response.json();
    assert.equal(body.items.length, 1);
    assert.equal(body.items[0].id, '00000000-0000-4000-8000-000000000011');
    assert.equal(JSON.stringify(body).includes('accountID'), false);
    assert.equal(body.limit, 1);
    const second = await app.inject({ method: 'GET', url: '/v1/admin/level-tests/drafts?limit=1&offset=1', headers });
    assert.equal(second.statusCode, 200);
    assert.equal(second.json().items[0].id, '00000000-0000-4000-8000-000000000012');
    assert.equal((await app.inject({ method: 'GET', url: '/v1/admin/level-tests/drafts?limit=101', headers })).statusCode, 400);
  } finally { await app.close(); }
});

test('admin generation status separates configured schedule from this process and redacts job fields', async () => {
  const { db, state } = database();
  const config = parseLevelTestSchedulerConfig({ LEVELTEST_GENERATE_ENABLED: 'true' });
  state.jobs = [{ language_id: 'en', level: 'A1', week_index: THIS_WEEK, state: 'unknown', attempts: 1,
    error_code: 'llm_outcome_unknown', started_at: NOW, finished_at: NOW, updated_at: NOW,
    prompt: 'secret prompt', api_key: 'secret key' }];
  const app = adminApp(db, { generationStatus: { config, isStarted: () => false } });
  try {
    const url = '/v1/admin/level-tests/status';
    assert.equal((await app.inject({ method: 'GET', url, headers: { ...headers, cookie: '' } })).statusCode, 401);
    assert.equal((await app.inject({ method: 'GET', url, headers: { ...headers, origin: 'https://evil.example' } })).statusCode, 403);
    const response = await app.inject({ method: 'GET', url, headers });
    assert.equal(response.statusCode, 200);
    const body = response.json();
    assert.equal(body.schedule.enabled, true);
    assert.equal(body.scheduler.activeInProcess, false);
    assert.equal(body.latestJob.status, 'unknown');
    assert.equal(JSON.stringify(body).includes('secret'), false);
    assert.equal(JSON.stringify(body).includes('prompt'), false);
  } finally { await app.close(); }
});

test('connection test requires persisted settings even when environment credentials exist', async () => {
  const { db } = database(); const app = adminApp(db); const originalFetch = globalThis.fetch; let calls = 0;
  globalThis.fetch = async () => { calls += 1; return new Response(null, { status: 200 }); };
  try {
    const response = await app.inject({ method: 'POST', url: '/v1/admin/llm-config/test', headers, payload: {} });
    assert.equal(response.statusCode, 404);
    assert.equal(calls, 0);
  } finally { globalThis.fetch = originalFetch; await app.close(); }
});

test('connection test times out and cancels the unread provider body', async () => {
  const { db, state } = database();
  state.config = { provider: 'openai', base_url: 'https://provider.example/v1', model: 'saved-model',
    api_key_encrypted: encryptSecret('saved-provider-key', ENV), is_active: true };
  const originalFetch = globalThis.fetch; let canceled = false;
  const originalError = console.error, failureLines: string[] = [];
  console.error = value => { failureLines.push(value); };
  const app = adminApp(db, { llmTestTimeoutMs: 5, environment: ENV });
  globalThis.fetch = async (_input, init) => await new Promise((_resolve, reject) =>
    init?.signal?.addEventListener('abort', () => reject(new Error('aborted')), { once: true }));
  try {
    const timeout = await app.inject({ method: 'POST', url: '/v1/admin/llm-config/test', headers, payload: {} });
    assert.equal(timeout.statusCode, 502, timeout.payload);
    assert.equal(timeout.json().error, 'llm_test_timeout');
    assert.equal(typeof timeout.headers['x-request-id'], 'string');
    assert.deepEqual(timeout.json(), { ok: false, error: 'llm_test_timeout' });
    const failure = failureLines.map(line => JSON.parse(line) as Record<string, unknown>).find(entry => entry.event === 'request_failed');
    assert.ok(failure);
    assert.equal(failure.request_id, timeout.headers['x-request-id']);
    assert.equal(state.audit.length, 1);
    assert.equal(state.audit[0]![2], 'llm_config.test');
    assert.equal(String(state.audit[0]![5]).includes('saved-provider-key'), false);
    globalThis.fetch = async () => new Response(new ReadableStream({ cancel() { canceled = true; } }), { status: 200 });
    const bounded = await app.inject({ method: 'POST', url: '/v1/admin/llm-config/test', headers, payload: {} });
    assert.equal(bounded.statusCode, 200);
    assert.equal(bounded.json().status, 200);
    assert.equal(canceled, true);
    assert.equal(state.audit.length, 2);
    assert.equal(String(state.audit[1]![5]).includes('saved-provider-key'), false);
    assert.equal(JSON.stringify(bounded.json()).includes('saved-provider-key'), false);
  } finally { globalThis.fetch = originalFetch; console.error = originalError; await app.close(); }
});

test('approval rejects tampered variants without publishing or auditing', async () => {
  const { db, state } = database(); await generate(db, { ...ENV, LEVELTEST_PUBLISH_MODE: 'review' });
  (state.weeks[0]!.variants[0]!.items as unknown[]).pop(); const app = adminApp(db);
  try { assert.equal((await app.inject({ method: 'POST', url: `/v1/admin/level-tests/${state.weeks[0]!.id}/approve`, headers,
    payload: { reason: 'Reviewed content' } })).statusCode, 409);
    assert.equal(state.weeks[0]!.status, 'draft'); assert.equal(state.audit.length, 0);
  } finally { await app.close(); }
});

test('manual approval accepts a judged pool that meets served form quotas after excluding one candidate', async () => {
  const { db, state } = database();
  const outcome = await generate(db, { ...ENV, LEVELTEST_PUBLISH_MODE: 'review', LEVELTEST_VERIFY: 'strict' },
    transport(pool(), items => ({ items: items.map(item => ({ id: item.id,
      verdict: item.id === 'rec-0' ? 'reject' : 'ok' })) })));
  assert.equal(outcome.status, 'draft');
  assert.equal((state.weeks[0]!.pool as GeneratedPool).recognition.filter(item => item.kind === 'choice').length, 15);
  const app = adminApp(db);
  try {
    const response = await app.inject({ method: 'POST', url: `/v1/admin/level-tests/${state.weeks[0]!.id}/approve`,
      headers, payload: { reason: 'Reviewed content' } });
    assert.equal(response.statusCode, 200, response.payload);
    assert.equal(state.weeks[0]!.status, 'approved');
  } finally { await app.close(); }
});

test('manual approval rejects a rehashed English form with an invalid recognition mix', async () => {
  const { db, state } = database();
  await generate(db, { ...ENV, LEVELTEST_PUBLISH_MODE: 'review' });
  const row = state.weeks[0]!, exam = row.variants[0] as unknown as ExamSnapshot;
  const choices = (row.pool as GeneratedPool).recognition.filter(item => item.kind === 'choice').slice(0, 12);
  exam.items = [...exam.items.filter(item => item.section !== 'recognition'), ...choices];
  row.variants[0] = identifySnapshot(exam, 'llm', row.id) as unknown as Record<string, unknown>;
  const app = adminApp(db);
  try {
    assert.equal((await app.inject({ method: 'POST', url: `/v1/admin/level-tests/${row.id}/approve`,
      headers, payload: { reason: 'Reviewed content' } })).statusCode, 409);
    assert.equal(row.status, 'draft'); assert.equal(state.audit.length, 0);
  } finally { await app.close(); }
});

for (const section of ['reading', 'listening'] as const) test(`manual approval rejects variants sharing only their ${section} stimuli`, async () => {
  const { db, state } = database();
  await generate(db, { ...ENV, LEVELTEST_PUBLISH_MODE: 'review' });
  const row = state.weeks[0]!, first = row.variants[0] as unknown as ExamSnapshot;
  const second = structuredClone(row.variants[1]) as unknown as ExamSnapshot;
  const selectedIDs = section === 'reading' ? [...first.readingPassageIds] : [...first.listeningPassageIds];
  if (section === 'reading') second.readingPassageIds = selectedIDs;
  else second.listeningPassageIds = selectedIDs;
  second.items = [...second.items.filter(item => item.section !== section), ...first.items.filter(item => item.section === section)];
  second.passages = [...second.passages.filter(passage => !passage.id.startsWith(`llm-${section}-`)),
    ...first.passages.filter(passage => selectedIDs.includes(passage.id))];
  assert.notDeepEqual(second.items.map(item => item.id).sort(), first.items.map(item => item.id).sort(),
    'keep the item-set diversity check valid so this test reaches the stimulus-set check');
  row.variants[1] = identifySnapshot(second, 'llm', row.id) as unknown as Record<string, unknown>;
  const app = adminApp(db);
  try {
    assert.equal((await app.inject({ method: 'POST', url: `/v1/admin/level-tests/${row.id}/approve`,
      headers, payload: { reason: 'Reviewed content' } })).statusCode, 409);
    assert.equal(row.status, 'draft'); assert.equal(state.audit.length, 0);
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

for (const tag of [undefined,'grammar:unknown','reading:gist']) test(`generation refuses invalid primary tag ${tag}`, async()=>{
  const {db,state}=database(), content=pool();
  Object.assign(content.recognition[0]!,{assessedSkill:tag});
  const result=await generate(db,ENV,transport(content));
  assert.equal(result.status,'failed');
  assert.equal(state.weeks.length,0);
});

test('semantic skill evidence is mandatory, bounded and bound to each expected tag', async()=>{
  for (const invalid of [{skillEvidence:undefined},{skillEvidence:'x'},{skillEvidence:'x'.repeat(241)},{assessedSkill:'reading:gist'}]) {
    const {db,state}=database();
    const base=transport();
    const result=await generate(db,ENV,async(config,messages)=>{
      const reply=await base(config,messages);
      if (messages[0]!.content.includes('quality reviewer')) {
        const parsed=JSON.parse(reply.content);Object.assign(parsed.items[0],invalid);
        return {...reply,content:JSON.stringify(parsed)};
      }
      return reply;
    });
    assert.equal(result.status,'failed');assert.equal(state.weeks.length,0);
  }
});

test('manual approval cannot bypass coverage by relabeling pool and matching judge evidence',async()=>{
  const {approveGeneratedWeek}=await import('../src/level-tests-publication.js');
  const {db,state}=database();
  assert.equal((await generate(db,{...ENV,LEVELTEST_PUBLISH_MODE:'review'})).status,'draft');
  const row=state.weeks[0]!;
  const saved=row.pool as GeneratedPool;
  const tag=examModelFor('A1','en')!.assessment!.sections.recognition.requiredSkills[0]!;
  saved.recognition.forEach(item=>{item.assessedSkill=tag;});
  (row.review as {id:string;assessedSkill?:string}[]).forEach(verdict=>{if(saved.recognition.some(item=>item.id===verdict.id)) verdict.assessedSkill=tag;});
  await assert.rejects(approveGeneratedWeek(db,row.id,ADMIN,'Reviewed content'),{code:'level_test_not_approvable'});
  assert.equal(row.status,'draft');assert.equal(state.audit.length,0);
});

test('manual approval requires semantic evidence and retains historical no-assessment contracts',async()=>{
  const {approveGeneratedWeek}=await import('../src/level-tests-publication.js');
  const {freezeBlueprint}=await import('../src/level-test-frozen-blueprint.js');
  const {db,state}=database();
  await generate(db,{...ENV,LEVELTEST_PUBLISH_MODE:'review'});
  const row=state.weeks[0]!;
  const review=row.review as {skillEvidence?:string;assessedSkill?:string}[];
  review.forEach(item=>{delete item.skillEvidence;delete item.assessedSkill;});
  await assert.rejects(approveGeneratedWeek(db,row.id,ADMIN,'Reviewed content'),{code:'level_test_not_approvable'});
  const retained=state.weeks[0]!;
  const legacy=structuredClone(retained.blueprint_contract as NonNullable<ReturnType<typeof examModelFor>>);
  delete legacy.assessment;
  const frozen=freezeBlueprint(legacy);
  retained.blueprint_contract=frozen.contract;retained.blueprint_hash=frozen.hash;
  assert.equal((await approveGeneratedWeek(db,row.id,ADMIN,'Reviewed historical contract')).approved,true);
});

test('manual approval verifies each rehashed form against frozen coverage, not just candidate coverage',async()=>{
  const {approveGeneratedWeek}=await import('../src/level-tests-publication.js');
  const {db,state}=database();
  await generate(db,{...ENV,LEVELTEST_PUBLISH_MODE:'review'});
  const row=state.weeks[0]!, candidate=row.pool as GeneratedPool;
  const form=row.variants[0] as unknown as ExamSnapshot;
  const byID=new Map(candidate.recognition.map(item=>[item.id,item]));
  const missing=examModelFor('A1','en')!.assessment!.sections.recognition.requiredSkills[0]!;
  const used=new Set(form.items.map(item=>item.id));
  form.items=form.items.map(item=>{
    if(item.section!=='recognition'||byID.get(item.id)?.assessedSkill!==missing) return item;
    const replacement=candidate.recognition.find(other=>!used.has(other.id)&&other.kind===item.kind&&other.assessedSkill!==missing)!;
    assert.ok(replacement);used.add(replacement.id);
    const {assessedSkill,...publicItem}=replacement;
    return publicItem;
  });
  row.variants[0]=identifySnapshot(form,'llm',row.id) as unknown as Record<string,unknown>;
  await assert.rejects(approveGeneratedWeek(db,row.id,ADMIN,'Reviewed content'),{code:'level_test_not_approvable'});
  assert.equal(state.weeks[0]!.status,'draft');assert.equal(state.audit.length,0);
});

test('approval uses frozen matrix after current matrix changes',async()=>{
  const {approveGeneratedWeek}=await import('../src/level-tests-publication.js');
  const {db,state}=database();await generate(db,{...ENV,LEVELTEST_PUBLISH_MODE:'review'});
  const model=examModelFor('A1','en')!, original=structuredClone(model.assessment);
  try {
    model.assessment!.sections.recognition.requiredSkills=['grammar:future-not-in-frozen-contract'];
    assert.equal((await approveGeneratedWeek(db,state.weeks[0]!.id,ADMIN,'Reviewed frozen coverage')).approved,true);
  } finally { model.assessment=original; }
});

test('manual approval rejects leaked internal assessed tags even in rehashed snapshots',async()=>{
  const {approveGeneratedWeek}=await import('../src/level-tests-publication.js');
  const {db,state}=database();await generate(db,{...ENV,LEVELTEST_PUBLISH_MODE:'review'});
  const row=state.weeks[0]!, form=row.variants[0] as unknown as ExamSnapshot;
  Object.assign(form.items[0]!,{assessedSkill:'grammar:verb-be'});
  row.variants[0]=identifySnapshot(form,'llm',row.id) as unknown as Record<string,unknown>;
  await assert.rejects(approveGeneratedWeek(db,row.id,ADMIN,'Reviewed content'),{code:'level_test_not_approvable'});
  assert.equal(state.audit.length,0);
});
