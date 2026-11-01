import { test } from 'node:test';
import assert from 'node:assert/strict';
import { normalizeBrowseFilters, browseFilterQuery } from '../admin-ui/features/browse-filters.js';
import { watchFailureStatus } from '../admin-ui/features/generation-watch.js';

const columns = {
  email: { operators: ['eq', 'neq', 'contains', 'is-null', 'not-null'] },
  reference: { operators: ['eq', 'contains', 'is-null', 'not-null'] },
  deleted_at: { operators: ['gte', 'lte', 'is-null', 'not-null'] },
  is_guest: { operators: ['eq'] }
};

const headerFilter = (field: string, operator: string, value: string) => ({ field, value: { operator, value } });

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
