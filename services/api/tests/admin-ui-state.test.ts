import { test } from 'node:test';
import assert from 'node:assert/strict';
import { normalizeBrowseFilters, browseFilterQuery } from '../admin-ui/features/browse-filters.js';
import { watchFailureStatus } from '../admin-ui/features/generation-watch.js';
import { dailyConfigIsDirty, dailyConfigRequest, dailyPackageIsCurrent, dailyPendingRequest, dailyPreviewText, dailyRequestIsCurrent, dailyRunStatus, dailyProviderUsageText } from '../admin-ui/features/daily-practice.js';

const columns = {
  email: { operators: ['eq', 'neq', 'contains', 'is-null', 'not-null'] },
  reference: { operators: ['eq', 'contains', 'is-null', 'not-null'] },
  deleted_at: { operators: ['gte', 'lte', 'is-null', 'not-null'] },
  is_guest: { operators: ['eq'] }
};

const headerFilter = (field: string, operator: string, value: string) => ({ field, value: { operator, value } });

test('daily admin config request requires an intentional dirty draft and a meaningful audit reason', () => {
  const saved = { revision: 4, read_enabled: false, new_attempts_enabled: false, generation_enabled: false,
    auto_publish_enabled: false, enabled_languages: ['en'], content_budget_nano_usd: '0',
    review_budget_nano_usd: '0', retention_days: 365 };
  const draft = { readEnabled: false, newAttemptsEnabled: false, generationEnabled: true,
    autoPublishEnabled: false, enabledLanguages: ['en'], contentBudgetNanoUsd: '120000', reviewBudgetNanoUsd: '80000', retentionDays: 365 };
  assert.equal(dailyConfigIsDirty(saved, { ...draft, generationEnabled: false,
    contentBudgetNanoUsd: '0', reviewBudgetNanoUsd: '0' }), false);
  assert.equal(dailyConfigIsDirty(saved, draft), true);
  assert.throws(() => dailyConfigRequest(saved, draft, 'too short'), /reason/);
  assert.deepEqual(dailyConfigRequest(saved, draft, 'Prepare controlled pilot'), {
    expectedRevision: 4,
    reason: 'Prepare controlled pilot',
    config: { schemaVersion: 1, expectedRevision: 4, readEnabled: false, newAttemptsEnabled: false,
      generationEnabled: true, autoPublishEnabled: false, enabledLanguages: ['en'],
      contentBudgetNanoUsd: '120000', reviewBudgetNanoUsd: '80000', retentionDays: 365 }
  });
});

test('daily admin reuses an idempotency key only for the exact unconfirmed operation and body', () => {
  let generated = 0;
  const createKey = () => `key-${++generated}`;
  const first = dailyPendingRequest(null, 'config', { revision: 4 }, createKey);
  assert.deepEqual(dailyPendingRequest(first, 'config', { revision: 4 }, createKey), first);
  assert.equal(dailyPendingRequest(first, 'config', { revision: 5 }, createKey).key, 'key-2');
  assert.equal(dailyPendingRequest(first, 'publish', { revision: 4 }, createKey).key, 'key-3');
  assert.equal(generated, 3);
});

test('daily provider usage history renders only phase, state, and token counts', () => {
  assert.equal(dailyProviderUsageText([{ phase: 'generate', state: 'succeeded', usage: { state: 'complete', tokensIn: 50, tokensOut: 12 } },
    { phase: 'judge', state: 'unknown', usage: { state: 'unknown', tokensIn: null, tokensOut: null, prompt: 'private' } }]),
  'generate: complete (in 50 / out 12)');
  assert.equal(dailyProviderUsageText([{ phase: 'review', state: 'succeeded', usage: { state: 'complete', tokensIn: -1, tokensOut: 3 } }]), '—');
});

test('daily admin ignores a late response after date or language selection changes', () => {
  const selected = { language: 'en', editorialDate: '2026-11-07', generationRunId: 'run-b' };
  assert.equal(dailyRequestIsCurrent(selected, { language: 'en', editorialDate: '2026-11-07', generationRunId: 'run-a' }), false);
  assert.equal(dailyRequestIsCurrent(selected, { language: 'en', editorialDate: '2026-11-07', generationRunId: 'run-b' }), true);
  assert.equal(dailyRequestIsCurrent(selected, { language: 'es', editorialDate: '2026-11-07', generationRunId: 'run-b' }), false);
});

test('daily admin status preserves explicit error and unknown states for recovery', () => {
  assert.equal(dailyRunStatus({ state: 'queued' }), 'queued');
  assert.equal(dailyRunStatus({ state: 'unknown' }), 'unknown');
  assert.equal(dailyRunStatus({ state: 'failed', errorCode: 'judge_timeout' }), 'failed: judge_timeout');
  assert.equal(dailyRunStatus({ state: 'anything' }), 'unknown');
});

test('daily admin preview serializes only the public preview and handles malformed details', () => {
  assert.equal(dailyPreviewText({ preview: { topic: { title: 'A greener city' } }, private_payload: { answer: 'secret' } }),
    '{\n  "topic": {\n    "title": "A greener city"\n  }\n}');
  assert.equal(dailyPreviewText({ preview: null, private_payload: { answer: 'secret' } }), 'Vista previa no disponible.');
});

test('daily admin ignores a package detail response after another package is selected', () => {
  assert.equal(dailyPackageIsCurrent('package-b', 'package-a'), false);
  assert.equal(dailyPackageIsCurrent('package-b', 'package-b'), true);
  assert.equal(dailyPackageIsCurrent(null, 'package-b'), false);
});

test('browse filters keep unary operators even when the operand is empty', () => {
  const filters = normalizeBrowseFilters([
    headerFilter('deleted_at', 'is-null', ''),
    headerFilter('email', 'not-null', '   ')
  ], columns);
  assert.deepEqual(filters, [
    { field: 'deleted_at', operator: 'is-null', value: '' },
    { field: 'email', operator: 'not-null', value: '' }
  ]);
});

test('browse filters drop operators whose operand is still empty', () => {
  const filters = normalizeBrowseFilters([
    headerFilter('email', 'contains', ''),
    headerFilter('email', 'eq', '   '),
    headerFilter('email', 'contains', ' student ')
  ], columns);
  assert.deepEqual(filters, [{ field: 'email', operator: 'contains', value: 'student' }]);
});

test('browse filters discard unknown fields and unsupported operators', () => {
  const filters = normalizeBrowseFilters([
    headerFilter('nope', 'eq', 'value'),
    headerFilter('is_guest', 'contains', 'true'),
    { field: 'email', value: 'legacy-shape' },
    headerFilter('email', 'eq', 'student@example.test')
  ], columns);
  assert.deepEqual(filters, [{ field: 'email', operator: 'eq', value: 'student@example.test' }]);
});

test('browse query serialises unary operators without an operand', () => {
  const query = browseFilterQuery([
    { field: 'deleted_at', operator: 'is-null', value: '' },
    { field: 'email', operator: 'not-null', value: '' },
    { field: 'reference', operator: 'contains', value: 'ana perez' }
  ], columns);
  assert.equal(query.get('q_deleted_at'), 'is-null:');
  assert.equal(query.get('q_email'), 'not-null:');
  assert.equal(query.get('q_reference'), 'contains:ana perez');
  assert.equal(query.toString(), 'q_deleted_at=is-null%3A&q_email=not-null%3A&q_reference=contains%3Aana+perez');
});

test('browse query skips filters that a stale panel could no longer validate', () => {
  const query = browseFilterQuery([
    { field: 'nope', operator: 'eq', value: 'value' },
    { field: 'is_guest', operator: 'contains', value: 'true' },
    { field: 'email', operator: 'eq', value: 'student@example.test' }
  ], columns);
  assert.equal(query.get('q_nope'), null);
  assert.equal(query.get('q_is_guest'), null);
  assert.equal(query.get('q_email'), 'eq:student@example.test');
});

test('watch failure status warns that the monitor lost confirmation', () => {
  const text = watchFailureStatus(null, 5_000);
  assert.match(text, /Sin confirmación del estado de generación/);
  assert.match(text, /Reintentando cada 5 s/);
  assert.match(text, /puede estar desactualizado/);
  assert.doesNotMatch(text, /En marcha/);
});

test('watch failure status keeps the last confirmed time visible', () => {
  const confirmedAt = Date.parse('2026-10-24T10:11:12Z');
  const text = watchFailureStatus(confirmedAt, 5_000, confirmedAt + 5_000);
  assert.match(text, /Sin confirmación del estado de generación desde las \d{2}:\d{2}:\d{2}/);
  assert.match(text, /Reintentando cada 5 s/);
  assert.match(text, /puede estar desactualizado/);
});
