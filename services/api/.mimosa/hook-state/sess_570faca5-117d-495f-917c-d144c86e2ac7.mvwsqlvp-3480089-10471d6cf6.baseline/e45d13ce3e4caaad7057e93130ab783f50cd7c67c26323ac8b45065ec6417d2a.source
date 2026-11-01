/* Pure translation between Tabulator header filters and the browse API contract.
   Kept outside app.js so the unary-operator rules are covered by unit tests. */
'use strict';

export const BROWSE_UNARY_OPERATORS = new Set(['is-null', 'not-null']);

const operatorOf = entry => entry && typeof entry.value === 'object' && entry.value !== null
  ? entry.value.operator : undefined;
const operandOf = entry => entry && typeof entry.value === 'object' && entry.value !== null
  ? entry.value.value : undefined;

/** Active filters the operator accepts. Unary operators are valid with no
    operand; every other operator needs a non-empty value, otherwise the panel
    used to send `op:` and the server rejected the whole read. */
export function normalizeBrowseFilters(rawFilters, columns) {
  const filters = [];
  for (const entry of Array.isArray(rawFilters) ? rawFilters : []) {
    const field = entry?.field;
    const spec = field ? columns?.[field] : undefined;
    const operator = operatorOf(entry);
    if (!spec || !Array.isArray(spec.operators) || !spec.operators.includes(operator)) continue;
    const raw = operandOf(entry);
    if (BROWSE_UNARY_OPERATORS.has(operator)) {
      filters.push({ field, operator, value: '' });
      continue;
    }
    const value = raw === undefined || raw === null ? '' : String(raw).trim();
    if (!value) continue;
    filters.push({ field, operator, value });
  }
  return filters;
}

/** Query string entries for a validated filter list. Unary operators keep the
    trailing colon the server uses to split operator and operand. */
export function browseFilterQuery(filters, columns) {
  const query = new URLSearchParams();
  for (const { field, operator, value } of Array.isArray(filters) ? filters : []) {
    const spec = columns?.[field];
    if (!spec || !Array.isArray(spec.operators) || !spec.operators.includes(operator)) continue;
    query.set(`q_${field}`, BROWSE_UNARY_OPERATORS.has(operator) ? `${operator}:` : `${operator}:${value}`);
  }
  return query;
}
