import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { Database } from '../src/db.js';
import { createApp } from '../src/app.js';
import { composeWeekly, levelTestLevels } from '../src/level-tests.js';
import { approvedWeeklyLevelTest, generateWeeklyLevelTest } from '../src/level-tests-llm.js';
import { identifySnapshot } from '../src/level-test-snapshot.js';

const NOW = new Date('2026-10-07T12:00:00Z');
const THRESHOLDS = { overall: 70, recognition: 60, reading: 50, listening: 50,
  writing: 50, grammar: 60, vocab: 60 };
const ENV = { LEVELTEST_LLM_PROVIDER: 'openai', LEVELTEST_LLM_API_KEY: 'fake-key',
  LEVELTEST_PUBLISH_MODE: 'auto', LEVELTEST_VERIFY: 'strict', LEVELTEST_VARIANTS: '2' };
const question = (kind = 'choice', seed = 'example') => ({ kind, prompt: `${seed}: She ___ a doctor.`,
  options: kind === 'choice' ? ['is', 'are', 'am', 'be'] : [], answers: ['is'],
  explain: 'She takes is.', unitID: 'en-foundations-u1' });
const transport = async (_config: unknown, messages: { role: string; content: string }[]) => {
  const user = messages.find(message => message.role === 'user')!.content;
  if (messages[0]!.content.includes('quality reviewer')) return {
    content: JSON.stringify({ items: (JSON.parse(user).items as { id: string }[]).map(item => ({
      id: item.id, verdict: 'ok', languageStatus: 'pass', reasonCode: 'answer_key', evidence: 'The key agrees with the supplied sentence.',
    })) }), tokensIn: 0, tokensOut: 0,
  };
  const section = user.includes('vocabulary and grammar') ? 'recognition'
    : user.includes('reading passages') ? 'reading'
    : user.includes('listening passages') ? 'listening' : 'writing';
  const body = section === 'reading' || section === 'listening'
    ? Array.from({ length: 6 }, (_, index) => ({ title: `Passage ${index}`, text: `${section} passage ${index}. She is working at the local school and has a meeting today. The teacher will travel by train to visit her family after work. She has bought the ticket and will leave tomorrow morning. Her sister is coming too, so they will have lunch together before the train leaves.`,
      items: Array.from({ length: 3 }, (_, questionIndex) => question('choice', `${section}-${index}-${questionIndex}`)) }))
    : Array.from({ length: section === 'writing' ? 8 : 24 }, (_, index) => question(
      section === 'writing' || (section === 'recognition' && index >= 16) ? 'gap' : 'choice', `${section}-${index}`));
  return { content: JSON.stringify(body), tokensIn: 0, tokensOut: 0 };
};

type WeekRow = { id: string; language_id: string; level: string; week_index: number;
  status: string; pool: unknown; variants: Record<string, unknown>[]; blueprint_version?: number; blueprint_hash?: string };

/** PostgreSQL is external: retain projection, insertion and retirement semantics;
    generation, composition, snapshots and HTTP handlers remain real. */
function storedWeeks(initial: WeekRow[] = []) {
  const rows = structuredClone(initial);
  const query = async (sql: string, values: unknown[] = []) => {
    if (['BEGIN', 'COMMIT', 'ROLLBACK'].includes(sql)) return { rows: [], rowCount: 0 };
    if (sql.includes('FROM llm_provider_configs') || sql.includes('FROM level_test_settings'))
      return { rows: [], rowCount: 0 };
    if (sql.startsWith('SELECT') && sql.includes('FROM level_test_weeks')) {
      const columns = sql.slice(7, sql.indexOf(' FROM')).trim().split(/,\s*/);
      const matches = rows.filter(row => row.language_id === values[0] && row.level === values[1]
        && row.week_index === values[2] && (!sql.includes("status='approved'") || row.status === 'approved'));
      return { rows: matches.map(row => Object.fromEntries(columns.map(column =>
        [column, structuredClone(row[column as keyof WeekRow])]))), rowCount: matches.length };
    }
    if (sql.startsWith('UPDATE level_test_weeks')) {
      for (const row of rows) if (row.language_id === values[0] && row.level === values[1]
        && row.week_index === values[2] && row.status !== 'retired') row.status = 'retired';
      return { rows: [], rowCount: 1 };
    }
    if (sql.startsWith('INSERT INTO level_test_weeks')) {
      rows.push({ id: values[0] as string, language_id: values[1] as string,
        level: values[2] as string, week_index: values[3] as number, status: values[4] as string,
        pool: JSON.parse(values[5] as string), variants: JSON.parse(values[6] as string),
        blueprint_version: values[18] as number, blueprint_hash: values[19] as string });
      return { rows: [], rowCount: 1 };
    }
    throw new Error(`Unexpected test SQL: ${sql}`);
  };
  const db = { query, connect: async () => ({ query, release() {} }) } as unknown as Database;
  return { db, rows };
}
const metadata = (exam: unknown) => exam as Record<string, unknown>;

test('deterministic snapshots add stable identity without changing the existing exam contract', () => {
  const exam = composeWeekly('en', 'B1', NOW)!;
  const snapshot = metadata(exam);
  assert.equal(typeof snapshot.contentHash, 'string');
  assert.match(snapshot.contentHash as string, /^[a-f0-9]{64}$/);
  assert.equal(snapshot.contentId, `sha256:${snapshot.contentHash}`);
  assert.equal(snapshot.examId, snapshot.contentId);
  assert.equal(snapshot.source, 'deterministic');
  assert.equal(snapshot.schemaVersion, 1);
  assert.equal(snapshot.contentRevision, 1);
  assert.equal(snapshot.gradingVersion, 1);
  assert.equal(snapshot.variant, undefined);
  assert.deepEqual(exam, composeWeekly('en', 'B1', new Date('2026-10-11T23:59:59Z')));
  assert.equal(exam.weekId, 202641);
  assert.equal(exam.items.length, 25);
  assert.deepEqual(exam.thresholds, THRESHOLDS);
  assert.notEqual(snapshot.examId, metadata(composeWeekly('en', 'B1', new Date('2026-10-14'))).examId);
});

test('snapshot identity changes when accepted solutions change without a content version bump', () => {
  const bank = levelTestLevels('en').find(level => level.level === 'B1')!;
  const before = composeWeekly('en', 'B1', NOW)!;
  const selected = bank.pool.find(item => item.id === before.items[0]!.id)!;
  const answers = selected.answers;
  try {
    selected.answers = ['a corrected accepted solution'];
    const after = composeWeekly('en', 'B1', NOW)!;
    assert.notEqual(metadata(after).contentHash, metadata(before).contentHash);
    assert.notEqual(metadata(after).examId, metadata(before).examId);
    assert.equal(after.version, before.version);
  } finally { selected.answers = answers; }
});

test('new generated snapshots preserve types, unit references, passages and effective thresholds', async () => {
  const { db, rows } = storedWeeks();
  const outcome = await generateWeeklyLevelTest({ db, env: ENV, transport },
    { languageID: 'en', level: 'A1', now: NOW, origin: 'automatic_worker' });
  assert.equal(outcome.status, 'approved');
  assert.equal(rows[0]?.blueprint_version, 2);
  assert.match(rows[0]?.blueprint_hash ?? '', /^[a-f0-9]{64}$/);
  const exam = (await approvedWeeklyLevelTest(db, 'en', 'A1', 1, NOW))!;
  assert.deepEqual(exam.thresholds, THRESHOLDS);
  assert.equal(exam.items.length, 34);
  assert.equal(exam.items.filter(item => item.section === 'recognition' && item.kind === 'choice').length, 8);
  assert.equal(exam.items.filter(item => item.section === 'recognition' && item.kind === 'gap').length, 4);
  for (const [index, raw] of exam.items.entries()) {
    const item = metadata(raw);
    const gap = item.section === 'writing' || item.section === 'recognition' && index >= 8 && index < 12;
    assert.equal(item.kind, gap ? 'gap' : 'choice');
    assert.equal(item.unitID, 'en-foundations-u1');
  }
  const snapshot = metadata(exam);
  assert.equal(snapshot.generationId, rows[0]!.id);
  assert.equal(snapshot.examId, `${rows[0]!.id}:v1`);
  assert.equal(snapshot.contentId, `sha256:${snapshot.contentHash}`);
  assert.equal(snapshot.contentRevision, 1);
  assert.equal(snapshot.gradingVersion, 1);
  assert.equal(snapshot.source, 'llm');
  assert.equal(exam.variant, 1);
  for (const passage of exam.passages as { id: string; title: string; text: string }[]) {
    assert.equal(typeof passage.title, 'string');
    assert.ok(passage.text);
  }
  assert.deepEqual(exam, rows[0]!.variants[0]);
});

test('provider failure before usage is known does not report fabricated zero token totals', async () => {
  const { db } = storedWeeks();
  const result = await generateWeeklyLevelTest({ db, env: ENV,
    transport: async () => { throw new Error('provider unavailable'); } },
  { languageID: 'en', level: 'A1', now: NOW, origin: 'automatic_worker' });
  assert.equal(result.status, 'failed');
  assert.equal('tokensIn' in result, false);
  assert.equal('tokensOut' in result, false);
});

test('stored generated grading survives later bundled threshold edits', async () => {
  const { db } = storedWeeks();
  await generateWeeklyLevelTest({ db, env: ENV, transport }, { languageID: 'en', level: 'A1', now: NOW, origin: 'automatic_worker' });
  const before = (await approvedWeeklyLevelTest(db, 'en', 'A1', 1, NOW))!;
  const bank = levelTestLevels('en').find(level => level.level === 'A1')!;
  const thresholds = bank.thresholds;
  try {
    bank.thresholds = { overall: 99 };
    assert.deepEqual(await approvedWeeklyLevelTest(db, 'en', 'A1', 1, NOW), before);
    assert.deepEqual(before.thresholds, THRESHOLDS);
  } finally { bank.thresholds = thresholds; }
});

test('force regeneration changes generation identity and preserves retired content', async () => {
  const { db, rows } = storedWeeks();
  await generateWeeklyLevelTest({ db, env: ENV, transport }, { languageID: 'en', level: 'A1', now: NOW, origin: 'automatic_worker' });
  const first = structuredClone(rows[0]!);
  await generateWeeklyLevelTest({ db, env: ENV, transport },
    { languageID: 'en', level: 'A1', now: NOW, origin: 'automatic_worker', force: true });
  const second = (await approvedWeeklyLevelTest(db, 'en', 'A1', 1, NOW))!;
  assert.equal(rows[0]!.status, 'retired');
  assert.deepEqual(rows[0]!.variants, first.variants);
  assert.notEqual(metadata(second).examId, first.variants[0]!.examId);
  assert.equal(metadata(second).contentHash, first.variants[0]!.contentHash);
  const other = (await approvedWeeklyLevelTest(db, 'en', 'A1', 2, NOW))!;
  assert.notEqual(metadata(other).examId, metadata(second).examId);
});

test('current and hybrid retain legacy HTTP fields and pinned ETag revalidation', async () => {
  const { db } = storedWeeks();
  assert.equal((await generateWeeklyLevelTest({ db, env: ENV, transport },
    { languageID: 'en', level: 'A1', now: new Date(), origin: 'automatic_worker' })).status, 'approved');
  const app = createApp({ db, auth: {} });
  try {
    for (const url of ['/v1/level-tests/current?language=en&level=B1',
      '/v1/level-tests/hybrid?language=en&level=A1&variant=1',
      '/v1/level-tests/hybrid?language=es&level=B1&variant=1']) {
      const first = await app.inject({ method: 'GET', url });
      assert.equal(first.statusCode, 200);
      const body = first.json();
      assert.equal(typeof body.examId, 'string');
      assert.equal(body.source, url.includes('level=A1') ? 'llm' : 'deterministic');
      assert.equal(body.schemaVersion, 1);
      assert.equal(body.version, 1);
      assert.equal(body.items.length, url.includes('level=A1') ? 34 : 25);
      const again = await app.inject({ method: 'GET', url, headers: { 'if-none-match': first.headers.etag } });
      assert.equal(again.statusCode, 304);
    }
  } finally { await app.close(); }
});

test('snapshot hashing is idempotent across JSON round trips and ignores prior identity metadata', () => {
  const first = composeWeekly('en', 'B1', NOW)!;
  const restored = JSON.parse(JSON.stringify(first));
  restored.examId = 'another-publication';
  restored.generationId = 'another-generation';
  assert.equal(identifySnapshot(restored, 'deterministic').contentHash, first.contentHash);
});

test('object-key order is irrelevant but option, passage and threshold changes alter content identity', () => {
  const first = composeWeekly('en', 'B1', NOW)!;
  const reordered = JSON.parse(JSON.stringify(first));
  reordered.thresholds = Object.fromEntries(Object.entries(reordered.thresholds).reverse());
  assert.equal(identifySnapshot(reordered, 'deterministic').contentHash, first.contentHash);
  for (const mutate of [
    (exam: typeof first) => { exam.items.find(item => (item.options?.length ?? 0) > 1)!.options!.reverse(); },
    (exam: typeof first) => { exam.passages[0]!.text += ' Revised passage.'; },
    (exam: typeof first) => { exam.thresholds.overall = 80; },
  ]) {
    const changed = structuredClone(first);
    mutate(changed);
    assert.notEqual(identifySnapshot(changed, 'deterministic').contentHash, first.contentHash);
  }
});

test('legacy generated rows keep their original content without invented grading metadata', async () => {
  const variant = { variant: 1, items: [{ id: 'legacy', section: 'recognition', prompt: 'Old question',
    options: ['a', 'b', 'c'], answers: ['a'], explain: 'Old explanation' }],
    passages: [], readingPassageIds: [], listeningPassageIds: [] };
  const { db } = storedWeeks([{ id: '00000000-0000-4000-8000-000000000001', language_id: 'en',
    level: 'A1', week_index: 202641, status: 'approved', pool: {}, variants: [variant] }]);
  const legacy = (await approvedWeeklyLevelTest(db, 'en', 'A1', 1, NOW))!;
  assert.deepEqual(legacy.items, variant.items);
  assert.deepEqual(legacy.thresholds, {});
  assert.equal(legacy.legacy, true);
  assert.equal(legacy.examId, '00000000-0000-4000-8000-000000000001:v1');
  assert.equal(legacy.contentHash, undefined);
});
