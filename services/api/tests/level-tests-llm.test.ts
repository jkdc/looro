import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createApp } from '../src/app.js';
import { connectDatabase, transaction, type Database } from '../src/db.js';
import { validateGeneratedPool } from '../src/level-tests-validate.js';
import { encryptSecret, decryptSecret, maskKey, resolveLlmConfig, LlmTransportError } from '../src/llm-config.js';
import { generateWeeklyLevelTest } from '../src/level-tests-llm.js';

const ENV_BASE: NodeJS.ProcessEnv = {
  LEVELTEST_LLM_MASTER_KEY: 'a'.repeat(64),
  LEVELTEST_LLM_PROVIDER: 'glm',
  LEVELTEST_LLM_MODEL: 'glm-5.3-flash',
  LEVELTEST_LLM_API_KEY: 'sk-test-key-1234',
  LEVELTEST_PUBLISH_MODE: 'auto',
  LEVELTEST_VERIFY: 'flag',
  LEVELTEST_VARIANTS: '3',
};

const databaseURL = process.env.TEST_DATABASE_URL;
const integration = (name: string, fn: (db: Database) => Promise<void>) =>
  test(name, { skip: !databaseURL && 'Set TEST_DATABASE_URL.' }, async () => {
    const db = connectDatabase(databaseURL!);
    try { await fn(db); } finally { await db.end(); }
  });

// ---- fixtures ----

const goodItem = { kind: 'choice', prompt: 'She ___ a doctor.', options: ['is', 'are', 'am', 'be'],
  answers: ['is'], explain: 'With she, use is.' };
const goodGap = { kind: 'gap', prompt: 'Write the missing word: I have ___ idea.', answers: ['an'],
  explain: 'An before a vowel sound.' };
const goodReading = { title: 'The new job', text: 'Last year Marta moved to Berlin for a new job, and the first months were not easy. She works at a small design studio near the main station, shares a bright flat with two colleagues, and spends her weekends exploring the city by bike. Slowly the language stopped feeling foreign.',
  items: [
    { kind: 'choice', prompt: 'Why did Marta move to Berlin?', options: ['For a new job', 'For her studies', 'For her family', 'For the weather'], answers: ['For a new job'], explain: 'She moved for a new job.' },
    { kind: 'choice', prompt: 'Where does she work?', options: ['A design studio', 'A bank', 'A school', 'A hospital'], answers: ['A design studio'], explain: 'The text says a design studio.' },
    { kind: 'choice', prompt: 'Who does she live with?', options: ['Two colleagues', 'Her sister', 'Alone', 'Her parents'], answers: ['Two colleagues'], explain: 'She shares a flat with two colleagues.' },
  ] };
const goodListening = { text: 'Hi Tom! Are you coming to the match on Sunday? I know the last one was a disaster, but this time we are going by train instead of car. The train leaves at nine from platform two, so please do not be late again like last month.', items: [
  { kind: 'choice', prompt: 'When does the train leave?', options: ['At nine', 'At ten', 'At eight', 'At eleven'], answers: ['At nine'], explain: 'The train leaves at nine.' },
  { kind: 'choice', prompt: 'Where does it leave from?', options: ['Platform two', 'Platform four', 'Platform one', 'Platform five'], answers: ['Platform two'], explain: 'Platform two.' } ] };

const goodPool = {
  recognition: Array.from({ length: 12 }, (_, index) => ({ ...goodItem, prompt: `She ___ doctor number ${index}.` })),
  writing: Array.from({ length: 3 }, (_, index) => ({ ...goodGap, prompt: `Write the missing word ${index}: I have ___ idea.` })),
  reading: [goodReading, { title: 'The weekend plan', text: 'This weekend we are visiting the market downtown. Lucas wants to buy a jacket, and I am looking for a present for my sister. After that we plan to have lunch near the river and walk along the old harbour before the sun goes down.', items: [
    { kind: 'choice', prompt: 'Where are they going this weekend?', options: ['The market', 'The beach', 'A museum', 'A concert'], answers: ['The market'], explain: 'They are visiting the market downtown.' },
    { kind: 'choice', prompt: 'What does Lucas want to buy?', options: ['A jacket', 'A book', 'Shoes', 'A bag'], answers: ['A jacket'], explain: 'Lucas wants a jacket.' },
    { kind: 'choice', prompt: 'Who is the present for?', options: ['His sister', 'His brother', 'His mother', 'Himself'], answers: ['His sister'], explain: 'A present for my sister.' } ] } ],
  listening: [goodListening, { text: 'I have been studying German for three years now. At first it was hard, but last summer I spent a month in Hamburg and everything changed: I could order food, ask for directions and even joke with my neighbours about my accent.', items: [
    { kind: 'choice', prompt: 'How long has the speaker studied German?', options: ['Three years', 'Three months', 'One year', 'Ten years'], answers: ['Three years'], explain: 'Three years.' },
    { kind: 'choice', prompt: 'Where did they spend a month?', options: ['Hamburg', 'Berlin', 'Munich', 'Vienna'], answers: ['Hamburg'], explain: 'A month in Hamburg.' } ] } ],
};

const stubReply = (body: unknown) => ({ content: JSON.stringify(body), tokensIn: 100, tokensOut: 200 });

/** Section-aware stub: routes based on the section name in the prompt. */
const sectionTransport = (overrides?: Partial<Record<string, unknown>>) =>
  async (_config: unknown, messages: { role: string; content: string }[]) => {
    const system = messages.find(m => m.role === 'system')?.content ?? '';
    const user = messages.find(m => m.role === 'user')?.content ?? '';
    if (system.includes('quality reviewer') || system.includes('strict exam reviewer')) {
      const parsed = JSON.parse(user) as { items?: { id: string }[] } | { id: string }[];
      const items = Array.isArray(parsed) ? parsed : parsed.items ?? [];
      return stubReply({ items: items.map(item => ({ id: item.id, verdict: 'ok', reasonCode: 'answer_key', evidence: 'The marked answer matches the sentence.' })) });
    }
    const count = (pattern: RegExp, fallback: number) => Number(pattern.exec(user)?.[1] ?? fallback);
    const item = (id: string, kind: 'choice' | 'gap' = 'choice') => kind === 'choice'
      ? { id, ...goodItem, prompt: `She ___ a doctor in sentence ${id}.` }
      : { id, ...goodGap, prompt: `Complete sentence ${id}: I have ___ idea.` };
    const passageText = (kind: 'reading' | 'listening', index: number) => {
      const range = /\((\d+)-(\d+) words each\)/.exec(user);
      const minimum = Number(range?.[1] ?? (kind === 'reading' ? 60 : 40));
      const target = Math.min(Number(range?.[2] ?? minimum + 10), minimum + 5);
      const seed = `${kind} case ${index} The local planning team met to review a community project compare the available evidence and explain its recommendations to residents`;
      const words = seed.split(' ');
      return Array.from({ length: target }, (_, position) => words[position % words.length]).join(' ');
    };
    const passageQuestions = (kind: 'reading' | 'listening', passage: number, questions: number) =>
      Array.from({ length: questions }, (_, index) => ({
        kind: 'choice', prompt: `What detail is mentioned in the ${kind} passage ${passage}, question ${index}?`,
        options: ['The team reviewed a project', 'The meeting was cancelled', 'The team changed its name', 'The residents left early'],
        answers: ['The team reviewed a project'], explain: 'The text says the team reviewed a project.'
      }));
    if (user.includes('vocabulary and grammar')) {
      const requested = count(/Write (\d+) vocabulary and grammar items/, goodPool.recognition.length);
      const mix = Object.fromEntries([...user.matchAll(/(\d+) (choice|gap)/g)].map(match => [match[2]!, Number(match[1])])) as Record<string, number>;
      return stubReply(overrides?.recognition ?? Array.from({ length: requested }, (_, index) =>
        item(`rec-${index}`, index < (mix.choice ?? requested) ? 'choice' : 'gap')));
    }
    if (user.includes('reading passages')) {
      const requested = count(/Write (\d+) distinct reading passages|Write (\d+) reading passages/, goodPool.reading.length);
      const questions = count(/Each passage has exactly (\d+) comprehension questions/, 3);
      return stubReply(overrides?.reading ?? Array.from({ length: requested }, (_, index) => ({
        title: `Reading ${index}`, text: passageText('reading', index), items: passageQuestions('reading', index, questions),
      })));
    }
    if (user.includes('listening passages')) {
      const requested = count(/Write (\d+) distinct listening passages|Write (\d+) listening passages/, goodPool.listening.length);
      const questions = count(/Each passage has exactly (\d+) comprehension questions/, 2);
      return stubReply(overrides?.listening ?? Array.from({ length: requested }, (_, index) => ({
        title: `Listening ${index}`, text: passageText('listening', index), items: passageQuestions('listening', index, questions),
      })));
    }
    const requested = count(/Write (\d+) short production items/, goodPool.writing.length);
    return stubReply(overrides?.writing ?? Array.from({ length: requested }, (_, index) => item(`wri-${index}`, 'gap')));
  };

const judgeRejectingFirst = () => async (_config: unknown, messages: { role: string; content: string }[]) => {
  const systemMessage = messages.find(message => message.role === 'system')?.content ?? '';
  if (systemMessage.includes('quality reviewer') || systemMessage.includes('strict exam reviewer')) {
    const raw = JSON.parse(messages.find(message => message.role === 'user')?.content ?? '{}') as
      { items?: { id?: string }[] } | { id?: string }[];
    const items = Array.isArray(raw) ? raw : raw.items ?? [];
    return stubReply({ items: items.map((item, index) => ({ id: item.id ?? 'missing',
      verdict: index === 0 ? 'reject' : 'ok', reasonCode: index === 0 ? 'unsupported' : 'answer_key',
      evidence: index === 0 ? 'The key is not supported by the passage.' : 'The marked answer matches the sentence.' })) });
  }
  return sectionTransport()(_config, messages);
};

// ---- unit: crypto + resolution ----

test('AES-256-GCM key encryption round-trips and rejects tampering', () => {
  const encrypted = encryptSecret('sk-live-secret', ENV_BASE);
  assert.ok(encrypted);
  assert.equal(decryptSecret(encrypted, ENV_BASE), 'sk-live-secret');
  const tampered = Buffer.from(encrypted, 'base64');
  const lastIndex = tampered.length - 1;
  tampered[lastIndex] = (tampered[lastIndex] ?? 0) ^ 0xff;
  assert.equal(decryptSecret(tampered.toString('base64'), ENV_BASE), undefined);
  assert.equal(encryptSecret('x', {}), undefined);
  assert.equal(decryptSecret('x', {}), undefined);
  assert.equal(maskKey('sk-live-secret'), 'sk-l…cret');
});

test('config resolution prefers the DB config and falls back to env', async () => {
  const db = connectDatabase('postgresql://unused@127.0.0.1:1/unused');
  try {
    const fromEnv = await resolveLlmConfig(db, ENV_BASE);
    assert.equal(fromEnv?.provider, 'glm');
    assert.equal(fromEnv?.model, 'glm-5.3-flash');
    const noProvider = await resolveLlmConfig(db, { LEVELTEST_LLM_API_KEY: 'k' });
    assert.equal(noProvider, undefined);
  } finally { await db.end(); }
});

// ---- unit: gate 1 validator ----

test('gate 1 accepts a well-formed pool and rejects broken items', () => {
  const good = validateGeneratedPool(goodPool, { recognition: 12, writing: 3, readingPassages: 2,
    readingPerPassage: 3, listeningPassages: 2, listeningPerPassage: 2 }, 'en');
  assert.equal(good.ok, true);
  const broken = validateGeneratedPool({
    recognition: [
      { ...goodItem, answers: ['is', 'are'] },                       // two correct answers
      { ...goodItem, options: ['is', 'is', 'are', 'am'] },           // duplicated options
      { ...goodItem, answers: ['runs'] },                            // answer not among options
      { ...goodGap, explain: '' },                                   // missing explain
    ],
    writing: [], reading: [], listening: [],
  }, { recognition: 12, writing: 3, readingPassages: 2, readingPerPassage: 3,
    listeningPassages: 2, listeningPerPassage: 2 }, 'en');
  assert.equal(broken.ok, false);
  assert.ok(broken.errors.some(error => error.code === 'answer'));
  assert.ok(broken.errors.some(error => error.code === 'options'));
  assert.ok(broken.errors.some(error => error.code === 'explain'));
  assert.ok(broken.errors.some(error => error.section === 'reading' && error.code === 'minimum'));
});

test('gate 1 reports null item members as structured errors instead of throwing', () => {
  const malformed = {
    ...goodPool,
    recognition: [null, ...goodPool.recognition.slice(1)],
  };

  const result = validateGeneratedPool(malformed, { recognition: 12, writing: 3, readingPassages: 2,
    readingPerPassage: 3, listeningPassages: 2, listeningPerPassage: 2 }, 'en');

  assert.equal(result.ok, false);
  assert.ok(result.errors.some(error => error.section === 'recognition[0]' && error.code === 'item'));
});

test('English writing section rejects recognition-only choice items', () => {
  const invalidWriting = Array.from({ length: 3 }, (_, index) => ({
    ...goodItem,
    prompt: `Choose the missing word for writing item ${index}.`,
  }));

  const result = validateGeneratedPool({ ...goodPool, writing: invalidWriting },
    { recognition: 12, writing: 3, readingPassages: 2, readingPerPassage: 3,
      listeningPassages: 2, listeningPerPassage: 2 }, 'en');

  assert.equal(result.ok, false);
  assert.ok(result.errors.some(error => error.section.startsWith('writing[') && error.code === 'kind'));
});

test('English pool rejects duplicate reading stimuli even when their questions differ', () => {
  const duplicateReading = {
    ...goodReading,
    items: goodReading.items.map((item, index) => ({
      ...item,
      prompt: `Different question ${index}: what does the passage mention?`,
    })),
  };

  const result = validateGeneratedPool({ ...goodPool, reading: [goodReading, duplicateReading] },
    { recognition: 12, writing: 3, readingPassages: 2, readingPerPassage: 3,
      listeningPassages: 2, listeningPerPassage: 2 }, 'en');

  assert.equal(result.ok, false);
  assert.ok(result.errors.some(error => error.section === 'reading[1]' && error.code === 'duplicate_passage'));
});

// ---- integration: generation + storage + serving ----

integration('generation persists approved variants and the endpoints serve them', async db => {
  const { migrate } = await import('../src/migrate.js');
  await migrate(db);
  // The DB persists across runs: clear the weeks under test so the
  // "before" 404 holds regardless of previous generations.
  await db.query("UPDATE level_test_weeks SET status='retired' WHERE language_id='en' AND level='A1' AND status <> 'retired'");
  const app = createApp({ db, auth: {} });
  try {
    const before = await app.inject({ method: 'GET', url: '/v1/level-tests/generated?language=en&level=A1' });
    assert.equal(before.statusCode, 404);
    assert.equal(before.json().error.code, 'level_test_not_generated');

    const outcome = await generateWeeklyLevelTest(
      { db, env: ENV_BASE, transport: sectionTransport() },
      { languageID: 'en', level: 'A1', origin: 'automatic_worker' });
    assert.equal(outcome.status, 'approved');
    assert.equal(outcome.variants, 3);

    const generated = await app.inject({ method: 'GET', url: '/v1/level-tests/generated?language=en&level=A1&variant=1' });
    assert.equal(generated.statusCode, 200);
    const body = generated.json();
    assert.equal(body.source ?? 'llm', 'llm');
    assert.equal(body.items.length, 34);
    assert.equal(body.variant, 1);
    const etag = generated.headers.etag;
    // Same pinned variant → ETag revalidation; a different variant → fresh 200.
    const cached = await app.inject({ method: 'GET', url: '/v1/level-tests/generated?language=en&level=A1&variant=1',
      headers: { 'if-none-match': etag } });
    assert.equal(cached.statusCode, 304);
    const other = await app.inject({ method: 'GET', url: '/v1/level-tests/generated?language=en&level=A1&variant=2' });
    assert.equal(other.statusCode, 200);
    assert.equal(other.json().variant, 2);
    assert.notEqual(other.headers.etag, etag);
    assert.notDeepEqual(other.json().items.map((item: { id: string }) => item.id).sort(),
      body.items.map((item: { id: string }) => item.id).sort());

    const hybrid = await app.inject({ method: 'GET', url: '/v1/level-tests/hybrid?language=en&level=A1' });
    assert.equal(hybrid.statusCode, 200);
    assert.equal(hybrid.json().source, 'llm');
    // Fallback target: retire any generated week for es/B1 so the hybrid
    // serves the deterministic composition for a level that HAS a pool.
    await db.query("UPDATE level_test_weeks SET status='retired' WHERE language_id='es' AND level='B1'");
    const hybridFallback = await app.inject({ method: 'GET', url: '/v1/level-tests/hybrid?language=es&level=B1' });
    assert.equal(hybridFallback.statusCode, 200);
    assert.equal(hybridFallback.json().source, 'deterministic');
    // A language with no pool at all stays 404 in both endpoints.
    const hybridNoPool = await app.inject({ method: 'GET', url: '/v1/level-tests/hybrid?language=fr&level=B1' });
    assert.equal(hybridNoPool.statusCode, 404);

    // es/B1 was retired by the fallback test above: approved filter → 404.
    const missing = await app.inject({ method: 'GET', url: '/v1/level-tests/generated?language=es&level=B1' });
    assert.equal(missing.statusCode, 404);
    assert.equal(missing.json().error.code, 'level_test_not_generated');
  } finally { await app.close(); }
});

integration('strict judge excludes a genuinely rejected English item before serving the longer form', async db => {
  const outcome = await generateWeeklyLevelTest(
    { db, env: { ...ENV_BASE, LEVELTEST_VERIFY: 'strict' }, transport: judgeRejectingFirst() },
    { languageID: 'en', level: 'A1', force: true, origin: 'automatic_worker' });
  assert.equal(outcome.status, 'approved');
  const history = await db.query(`SELECT status, pool, variants, review FROM level_test_weeks WHERE language_id='en' AND level='A1' ORDER BY created_at DESC`);
  assert.ok(history.rows.length >= 1);
  const row = history.rows[0] as { pool: { recognition: { id: string }[] }; variants: { items: { id: string }[] }[];
    review: { id: string; verdict: string }[] };
  const rejected = row.review.find(entry => entry.verdict === 'reject');
  assert.ok(rejected);
  assert.ok(!row.pool.recognition.some(item => item.id === rejected.id));
  assert.equal(row.variants[0]?.items.length, 34);
  assert.ok(!row.variants.some(variant => variant.items.some(item => item.id === rejected.id)));
});



integration('a generated week records who triggered the generation', async db => {
  const manual = await generateWeeklyLevelTest(
    { db, env: ENV_BASE, transport: sectionTransport() },
    { languageID: 'en', level: 'A2', force: true, origin: 'user_operator' });
  assert.equal(manual.status, 'approved', manual.errors?.join('; '));
  const automatic = await generateWeeklyLevelTest(
    { db, env: ENV_BASE, transport: sectionTransport() },
    { languageID: 'es', level: 'A2', force: true, origin: 'automatic_worker' });
  assert.equal(automatic.status, 'approved', automatic.errors?.join('; '));
  const rows = (await db.query(`SELECT language_id, origin FROM level_test_weeks
    WHERE level='A2' AND status='approved' AND language_id IN ('en','es') ORDER BY language_id`)).rows;
  assert.deepEqual(rows.map(row => [row.language_id, row.origin]),
    [['en', 'user_operator'], ['es', 'automatic_worker']]);
});

integration('unknown judge transport outcome stops generation in flag and strict modes', async db => {
  for (const verifyMode of ['flag', 'strict'] as const) {
    let calls = 0;
    const outcome = await generateWeeklyLevelTest({
      db,
      env: { ...ENV_BASE, LEVELTEST_VERIFY: verifyMode },
      transport: async (_config, messages) => {
        calls += 1;
        const system = messages.find(message => message.role === 'system')?.content ?? '';
        if (system.includes('strict exam reviewer')) throw new LlmTransportError('llm_outcome_unknown');
        return sectionTransport()(_config, messages);
      },
    }, { languageID: 'fr', level: 'A1', weekIndex: 998001, origin: 'automatic_worker' });
    assert.equal(outcome.status, 'failed');
    assert.ok(outcome.errors?.some(error => error.includes('llm_outcome_unknown')), outcome.errors?.join('; '));
    assert.equal(calls, 5);
    assert.equal((await db.query(`SELECT 1 FROM level_test_weeks WHERE language_id='fr' AND level='A1' AND week_index=998001`)).rowCount, 0);
  }
});
