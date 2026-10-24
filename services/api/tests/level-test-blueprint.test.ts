import { test } from 'node:test';
import assert from 'node:assert/strict';
import { resolveExamBlueprint } from '../src/level-test-blueprint.js';
import { buildSectionMessages, buildJudgeMessages, generationExamples, retryFailedSectionsOnce,
  sectionGenerationSpec, composeVariants, excludeJudgeUntrustedItems } from '../src/level-tests-llm.js';
import { validateGeneratedPool, type GeneratedPool } from '../src/level-tests-validate.js';

test('English blueprint resolution prefers the exact language model over the wildcard', () => {
  const blueprint = resolveExamBlueprint('en', 'A1');

  assert.ok(blueprint);
  assert.equal(blueprint.languageID, 'en');
  assert.match(blueprint.canDoAnchor, /introduce themselves|basic questions/i);
  assert.deepEqual(blueprint.sections.reading?.words, [50, 90]);
});

test('English blueprints carry typed level-specific passage ranges', () => {
  const a1 = resolveExamBlueprint('en', 'A1');
  const c1 = resolveExamBlueprint('en', 'C1');

  assert.deepEqual(a1?.sections.reading?.words, [50, 90]);
  assert.deepEqual(c1?.sections.reading?.words, [100, 180]);
  assert.deepEqual(a1?.sections.listening?.words, [35, 70]);
  assert.deepEqual(c1?.sections.listening?.words, [60, 120]);
});

test('English validation rejects Spanish items even when no English markers occur', () => {
  const blueprint = resolveExamBlueprint('en', 'A1')!;
  const spanishPool = { recognition: [{ kind:'choice', prompt:'Ella trabaja en casa.', options:['trabaja','comen','vivimos'], answers:['trabaja'], explain:'La frase dice que ella trabaja.' }] };
  const result = validateGeneratedPool(spanishPool, blueprint.examShape, 'en', blueprint);
  assert.ok(result.errors.some(error => error.code === 'language'));
});

test('duplicate passage rejection remains English-only', () => {
  const passage = { title:'Reading', text:'This is the same reading text.', items:[] };
  const result = validateGeneratedPool({ reading:[passage, passage] },
    { recognition:0, writing:0, readingPassages:0, readingPerPassage:0, listeningPassages:0, listeningPerPassage:0 }, 'es');
  assert.ok(!result.errors.some(error => error.code === 'duplicate_passage'));
});

test('other languages retain their existing wildcard model as a fallback', () => {
  const spanish = resolveExamBlueprint('es', 'A1');

  assert.ok(spanish);
  assert.equal(spanish.languageID, '*');
  assert.equal(spanish.examShape.recognition, 12);
});

test('English section prompt uses blueprint anchor, level-specific length, register, and exclusions', () => {
  const a1 = resolveExamBlueprint('en', 'A1')!;
  const c1 = resolveExamBlueprint('en', 'C1')!;
  const a1Text = buildSectionMessages('reading', 'en', 'A1', 2, 3, 0, [], a1)
    .map(message => message.content).join('\n');
  const c1Text = buildSectionMessages('reading', 'en', 'C1', 2, 3, 0, [], c1)
    .map(message => message.content).join('\n');

  assert.match(a1Text, /50-90 words/);
  assert.match(a1Text, /introduce themselves/i);
  assert.match(a1Text, /everyday notices/i);
  assert.match(c1Text, /100-180 words/);
  assert.match(c1Text, /implicit meaning/i);
  assert.match(c1Text, /simple everyday-only material/i);
});

test('English judge prompt includes answer evidence, explanation, level fit, and the full passage', () => {
  const blueprint = resolveExamBlueprint('en', 'C1')!;
  const messages = buildJudgeMessages('reading', 'en', 'C1', [{
    id: 'rea-0-0', section: 'reading', kind: 'choice', prompt: 'Why did the panel change its recommendation?',
    options: ['The evidence changed', 'The budget doubled', 'A new law passed'], answers: ['The evidence changed'],
    explain: 'The final paragraph says the evidence changed.', passageText: 'After reviewing the new evidence, the panel revised its recommendation.'
  }], blueprint);

  assert.match(messages[0]?.content ?? '', /exactly one marked answer is correct/i);
  assert.match(messages[0]?.content ?? '', /supported by the supplied passage/i);
  assert.match(messages[0]?.content ?? '', /explanation.*accurate/i);
  assert.match(messages[0]?.content ?? '', /outside knowledge/i);
  assert.match(messages[1]?.content ?? '', /Can use English flexibly/i);
  assert.match(messages[1]?.content ?? '', /After reviewing the new evidence/);
});

test('English generation does not use arbitrary bundled learning items as unreviewed exam examples', () => {
  assert.deepEqual(generationExamples('en', 'B1', 'reading'), []);
  assert.equal(generationExamples('es', 'B1', 'recognition').length, 2);
});

test('section repair calls each affected section once and sends sanitized errors before retry', async () => {
  const calls: { section: string; feedback: string[] }[] = [];
  await retryFailedSectionsOnce([
    { section: 'recognition[3]', code: 'answer', detail: 'untrusted provider text must not be forwarded' },
    { section: 'reading[0]', code: 'passage', detail: 'another detail' },
    { section: 'recognition[5]', code: 'options', detail: 'duplicate option' },
  ], async (section, feedback) => { calls.push({ section, feedback }); });

  assert.equal(calls.length, 2);
  assert.deepEqual(calls.map(call => call.section), ['recognition', 'reading']);
  assert.deepEqual(calls[0]?.feedback, ['recognition[3].answer', 'recognition[5].options']);
  assert.ok(calls.every(call => !call.feedback.some(message => message.includes('untrusted'))));
});

test('section repair stops when the repair call outcome is unknown', async () => {
  let calls = 0;

  await assert.rejects(retryFailedSectionsOnce([
    { section: 'recognition[0]', code: 'answer', detail: 'invalid' },
    { section: 'reading[0]', code: 'passage', detail: 'invalid' },
  ], async () => {
    calls += 1;
    throw new Error('llm_outcome_unknown');
  }), /llm_outcome_unknown/);

  assert.equal(calls, 1);
});

test('English CEFR forms and candidate pools follow separate report pilot targets', () => {
  const targets = { A1: 34, A2: 39, B1: 44, B2: 54, C1: 58 };
  for (const [level, expected] of Object.entries(targets)) {
    const blueprint = resolveExamBlueprint('en', level)!;
    const shape = blueprint.examShape;
    const total = shape.recognition + shape.writing + shape.readingPassages * shape.readingPerPassage +
      shape.listeningPassages * shape.listeningPerPassage;
    assert.equal(total, expected, `${level} learner-facing form size`);
    assert.equal(blueprint.poolTarget.recognition, shape.recognition * 2);
    assert.equal(blueprint.poolTarget.writing, shape.writing * 2);
    assert.equal(blueprint.poolTarget.readingPassages, shape.readingPassages * 2);
    assert.equal(blueprint.poolTarget.listeningPassages, shape.listeningPassages * 2);
  }
});

test('English section generation derives candidate count while the form retains its own shape', () => {
  const blueprint = resolveExamBlueprint('en', 'A1')!;

  assert.deepEqual(sectionGenerationSpec('recognition', blueprint), { count: 24, perPassage: 0 });
  assert.deepEqual(sectionGenerationSpec('reading', blueprint), { count: 6, perPassage: 3 });
  assert.equal(blueprint.examShape.recognition, 12);
  assert.equal(blueprint.examShape.readingPassages, 3);
});

test('English validator refuses a form-sized pool when the candidate target is larger', () => {
  const blueprint = resolveExamBlueprint('en', 'A1')!;
  const item = (id: string, section: 'recognition' | 'writing' | 'reading' | 'listening', kind = 'choice') => ({
    id, section, kind, prompt: `What does the story say in question ${id}?`,
    options: kind === 'choice' ? ['The answer', 'A different answer', 'Another option', 'The last option'] : [],
    answers: [kind === 'choice' ? 'The answer' : 'was'], explain: 'The text confirms the answer.'
  });
  const text = (day: string) => Array.from({ length: 4 }, () =>
    `On ${day}, a student walks to the station with a friend and talks about the weather.`).join(' ');
  const pool: GeneratedPool = {
    recognition: Array.from({ length: 12 }, (_, i) => item(`rec-${i}`, 'recognition')) as GeneratedPool['recognition'],
    writing: Array.from({ length: 4 }, (_, i) => item(`wri-${i}`, 'writing', 'gap')) as GeneratedPool['writing'],
    readingPassages: ['Monday','Tuesday','Wednesday'].map((day, i) => ({ title: `Reading ${i}`, text: text(day),
      items: Array.from({ length: 3 }, (_, j) => item(`rea-${i}-${j}`, 'reading')) as GeneratedPool['readingPassages'][number]['items'] })),
    listeningPassages: ['Thursday','Friday','Saturday'].map((day, i) => ({ title: `Listening ${i}`, text: text(day),
      items: Array.from({ length: 3 }, (_, j) => item(`lis-${i}-${j}`, 'listening')) as GeneratedPool['listeningPassages'][number]['items'] })),
  };
  const result = validateGeneratedPool(pool, blueprint.examShape, 'en', blueprint);

  assert.equal(result.ok, false);
  assert.deepEqual(new Set(result.errors.filter(error => error.code === 'minimum').map(error => error.section)),
    new Set(['recognition', 'writing', 'reading', 'listening']));
});

test('English composition returns longer forms and distinct deterministic variants', () => {
  const blueprint = resolveExamBlueprint('en', 'A1')!;
  const item = (id: string, section: 'recognition' | 'writing' | 'reading' | 'listening') => ({
    id, section, kind: 'choice', prompt: `Prompt for ${id}`, options: ['one','two','three','four'],
    answers: ['one'], explain: 'The text supports this answer.'
  });
  const pool: GeneratedPool = {
    recognition: Array.from({ length: 24 }, (_, i) => item(`rec-${i}`, 'recognition')) as GeneratedPool['recognition'],
    writing: Array.from({ length: 8 }, (_, i) => ({ ...item(`wri-${i}`, 'writing'), kind: 'gap' })) as GeneratedPool['writing'],
    readingPassages: Array.from({ length: 6 }, (_, i) => ({ title: `Reading ${i}`, text: `Reading stimulus ${i}`,
      items: Array.from({ length: 3 }, (_, j) => item(`rea-${i}-${j}`, 'reading')) as GeneratedPool['readingPassages'][number]['items'] })),
    listeningPassages: Array.from({ length: 6 }, (_, i) => ({ title: `Listening ${i}`, text: `Listening stimulus ${i}`,
      items: Array.from({ length: 3 }, (_, j) => item(`lis-${i}-${j}`, 'listening')) as GeneratedPool['listeningPassages'][number]['items'] })),
  };
  const variants = composeVariants(pool, blueprint, 0, 3);

  assert.deepEqual(variants.map(variant => variant.items.length), [34, 34, 34]);
  assert.equal(new Set(variants.map(variant => variant.items.map(candidate => candidate.id).sort().join(','))).size, 3);
  assert.equal(new Set(variants.map(variant => variant.readingPassageIds.join(','))).size, 3);
});

test('English composition excludes rejected and flagged judge items without mutating the source pool', () => {
  const item = (id: string, section: 'recognition' | 'writing' | 'reading' | 'listening') => ({
    id, section, kind: 'choice', prompt: `What does the text say about ${id}?`, options: ['The answer','A second answer','A third answer'],
    answers: ['The answer'], explain: 'The passage supports the answer.'
  });
  const pool: GeneratedPool = {
    recognition: [item('rec-1','recognition'), item('rec-2','recognition')] as GeneratedPool['recognition'],
    writing: [item('wri-1','writing')] as GeneratedPool['writing'],
    readingPassages: [{ title:'Reading', text:'The report explains this answer in context.', items:[item('rea-1','reading'),item('rea-2','reading')] as GeneratedPool['readingPassages'][number]['items'] }],
    listeningPassages: [{ title:'Listening', text:'The speaker explains the answer clearly.', items:[item('lis-1','listening')] as GeneratedPool['listeningPassages'][number]['items'] }],
  };

  const filtered = excludeJudgeUntrustedItems(pool, [
    { id: 'rec-2', verdict: 'flagged' }, { id: 'rea-2', verdict: 'reject' },
  ], true);

  assert.deepEqual(filtered.recognition.map(candidate => candidate.id), ['rec-1']);
  assert.equal(filtered.readingPassages.length, 0); // Never publish a partial stimulus after excluding one question.
  assert.equal(pool.recognition.length, 2);
  assert.equal(pool.readingPassages[0]?.items.length, 2);
});
