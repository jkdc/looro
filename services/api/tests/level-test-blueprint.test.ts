import { test } from 'node:test';
import assert from 'node:assert/strict';
import { resolveExamBlueprint } from '../src/level-test-blueprint.js';

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
