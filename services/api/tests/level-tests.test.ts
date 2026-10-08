import { test } from 'node:test';
import assert from 'node:assert/strict';
function assertEquals(actual: unknown, expected: unknown, message?: string): void { assert.deepEqual(actual, expected, message); }
function assertTrue(condition: unknown, message?: string): void { assert.ok(condition, message); }
import { createApp } from '../src/app.js';
import { connectDatabase, type Database } from '../src/db.js';
import { composeWeekly, expiresAt, levelTestLevels, weekIndex } from '../src/level-tests.js';

/** Golden parity with the Kotlin engine (LevelTestEngineTest's golden vector):
    both composers MUST produce the identical weekly exam, or app and server
    drift apart. B1, English, ISO week 202641. */
const GOLDEN_ITEMS = [
  'en-business-u1-negotiation-e3', 'en-care-u1-appointment-e3', 'en-care-u1-instructions-e2',
  'en-hosp-u1-complaints-e1', 'en-foundations-connections-first-conditional-e1', 'en-business-u1-followup-e3',
  'en-foundations-experiences-used-to-e3', 'en-foundations-change-phrasals-e2', 'en-foundations-telling-collocations-e3',
  'en-hosp-u1-checkin-e3', 'en-foundations-change-will-e1', 'en-foundations-done-phrasals-e2',
  'en-listen-job-change-q2#r', 'en-listen-job-change-q3#r', 'en-listen-job-change-q1#r',
  'en-listen-never-been-q2#r', 'en-listen-never-been-q3#r', 'en-listen-never-been-q1#r',
  'en-listen-monday-predictions-q1#l', 'en-listen-monday-predictions-q2#l',
  'en-listen-news-swap-q1#l', 'en-listen-news-swap-q2#l',
  'en-foundations-connections-suggestions-e4', 'en-foundations-telling-reacting-e4', 'en-hosp-u1-checkin-e4',
];
const GOLDEN_READING = ['en-listen-never-been', 'en-listen-job-change'];
const GOLDEN_LISTENING = ['en-listen-monday-predictions', 'en-listen-news-swap'];

test('golden parity: the server composes the same weekly exam as the app', () => {
  // ISO week 41 of 2026 = Mon 2026-10-05 .. Sun 2026-10-11.
  const now = new Date('2026-10-07T12:00:00Z');
  assertEquals(weekIndex(now), 202641);
  const composed = composeWeekly('en', 'B1', now);
  assert.ok(composed);
  assertEquals(composed.items.map(item => item.id), GOLDEN_ITEMS);
  assertEquals(composed.readingPassageIds, GOLDEN_READING);
  assertEquals(composed.listeningPassageIds, GOLDEN_LISTENING);
  assertEquals(composed.weekId, 202641);
  assertEquals(composed.expiresAt, '2026-10-12T00:00:00.000Z');
});

test('composition is deterministic, disjoint per exam and bounded per week', () => {
  const first = composeWeekly('es', 'B1', new Date('2026-10-07T12:00:00Z'));
  const again = composeWeekly('es', 'B1', new Date('2026-10-07T12:00:00Z'));
  assert.deepEqual(first?.items.map(item => item.id), again?.items.map(item => item.id));
  const next = composeWeekly('es', 'B1', new Date('2026-10-14T12:00:00Z'));
  const overlap = next?.items.filter(item => first?.items.some(prior => prior.id === item.id)).length ?? 99;
  assertTrue(overlap <= (first?.items.length ?? 0) / 2);
  // Disjointness is per exam: one week's reading never appears as listening
  // in the SAME exam (a different week may legitimately reuse the text).
  for (const exam of [first, next]) {
    const reading = new Set(exam?.readingPassageIds);
    for (const id of exam?.listeningPassageIds ?? []) assertTrue(!reading.has(id));
  }
  // Sections: 12 recognition, 2 passages × 3 reading, 2 × 2 listening, 3 writing.
  const counts: Record<string, number> = {};
  for (const item of first?.items ?? []) counts[item.section] = (counts[item.section] ?? 0) + 1;
  assert.equal(counts.recognition, 12);
  assert.equal(counts.reading, 6);
  assert.equal(counts.listening, 4);
  assert.equal(counts.writing, 3);
});

test('the HTTP route serves the weekly test with ETag revalidation and fails closed', async () => {
  const db = connectDatabase('postgresql://unused@127.0.0.1:1/unused');
  const app = createApp({ db, auth: {} });
  try {
    const first = await app.inject({ method: 'GET', url: '/v1/level-tests/current?language=en&level=B1' });
    assert.equal(first.statusCode, 200);
    const body = first.json();
    assert.equal(body.level, 'B1');
    assert.equal(body.items.length, 25);
    assert.ok(Array.isArray(body.passages) && body.passages.length > 0);
    assert.ok(typeof body.weekId === 'number' && typeof body.expiresAt === 'string');
    const etag = first.headers.etag;
    assert.ok(typeof etag === 'string' && etag.length > 2);
    const cached = await app.inject({ method: 'GET', url: '/v1/level-tests/current?language=en&level=B1',
      headers: { 'if-none-match': etag } });
    assert.equal(cached.statusCode, 304);

    const missing = await app.inject({ method: 'GET', url: '/v1/level-tests/current?language=en&level=C1' });
    assert.equal(missing.statusCode, 404);
    assert.equal(missing.json().error.code, 'level_test_unavailable');
    const bad = await app.inject({ method: 'GET', url: '/v1/level-tests/current' });
    assert.equal(bad.statusCode, 400);
    assert.equal(bad.json().error.code, 'invalid_request');
    const unknownLanguage = await app.inject({ method: 'GET', url: '/v1/level-tests/current?language=xx&level=A1' });
    assert.equal(unknownLanguage.statusCode, 404);
  } finally { await app.close(); await db.end(); }
});

test('levels ship only where the harvest meets the minimums', () => {
  assertEquals(levelTestLevels('en').map(level => level.level), ['A1', 'A2', 'B1']);
  assertEquals(levelTestLevels('es').map(level => level.level), ['A1', 'A2', 'B1']);
  assertEquals(levelTestLevels('fr'), []);
});
