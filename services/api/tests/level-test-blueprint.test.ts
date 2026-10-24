import { test } from 'node:test';
import assert from 'node:assert/strict';
import { resolveExamBlueprint } from '../src/level-test-blueprint.js';
import { buildSectionMessages, buildJudgeMessages, generationExamples, retryFailedSectionsOnce } from '../src/level-tests-llm.js';

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
