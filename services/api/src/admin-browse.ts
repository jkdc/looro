import { ServiceError } from './errors.js';

// The browse engine exposes fixed operator tables to the panel. Every identifier
// (table, column, direction) comes from these declarations; client input only
// ever binds as a parameter value. Adding a table is adding a config entry.
type ColumnType = 'uuid' | 'text' | 'integer' | 'bigint' | 'boolean' | 'timestamptz' | 'jsonb';
type FilterOperator = 'eq' | 'neq' | 'contains' | 'gte' | 'lte' | 'in' | 'is-null' | 'not-null';

interface BrowseColumn { type: ColumnType; operators: FilterOperator[] }
export interface BrowseTable {
  table: string;
  columns: Record<string, BrowseColumn>;
  orderable: string[];
  defaultOrder: { column: string; direction: 'asc' | 'desc' };
  description: string;
}
const column = (type: ColumnType, operators: FilterOperator[]): BrowseColumn => ({ type, operators });
const textFilters: FilterOperator[] = ['eq', 'neq', 'contains', 'is-null', 'not-null'];
const uuidFilters: FilterOperator[] = ['eq', 'in', 'is-null', 'not-null'];
const timeFilters: FilterOperator[] = ['gte', 'lte', 'is-null', 'not-null'];
const numberFilters: FilterOperator[] = ['eq', 'gte', 'lte'];
const boolFilters: FilterOperator[] = ['eq'];

export const BROWSE_TABLES: Record<string, BrowseTable> = {
  accounts: {
    table: 'accounts',
    description: 'Cuentas registradas de la app, incluidos invitados y borrados lógicos.',
    columns: {
      id: column('uuid', uuidFilters), email: column('text', textFilters),
      created_at: column('timestamptz', timeFilters), deleted_at: column('timestamptz', timeFilters),
      is_guest: column('boolean', boolFilters)
    },
    orderable: ['created_at', 'email', 'id'], defaultOrder: { column: 'created_at', direction: 'desc' }
  },
  auth_sessions: {
    table: 'auth_sessions',
    description: 'Metadatos de sesiones de la app. Los hashes de token nunca se exponen.',
    columns: {
      id: column('uuid', uuidFilters), account_id: column('uuid', uuidFilters),
      created_at: column('timestamptz', timeFilters), expires_at: column('timestamptz', timeFilters),
      revoked_at: column('timestamptz', timeFilters)
    },
    orderable: ['created_at', 'expires_at'], defaultOrder: { column: 'created_at', direction: 'desc' }
  },
  wallets: {
    table: 'wallets',
    description: 'Carteras de dinero (nanoUSD) con reservas.',
    columns: {
      account_id: column('uuid', uuidFilters), balance_nano: column('bigint', numberFilters),
      reserved_nano: column('bigint', numberFilters), sandbox_balance_nano: column('bigint', numberFilters),
      cash_provenance_verified: column('boolean', boolFilters)
    },
    orderable: ['balance_nano', 'account_id'], defaultOrder: { column: 'balance_nano', direction: 'desc' }
  },
  entitlements: {
    table: 'entitlements',
    description: 'Desbloqueo BYOK y origen de la compra (token-billing).',
    columns: {
      account_id: column('uuid', uuidFilters), unlocked_at: column('timestamptz', []),
      unlock_source: column('text', []), created_at: column('timestamptz', [])
    },
    orderable: ['created_at', 'account_id'], defaultOrder: { column: 'created_at', direction: 'desc' }
  },
  rate_versions: {
    table: 'rate_versions',
    description: 'Versiones de tarifa nanoUSD por millón de tokens (entrada/salida).',
    columns: {
      id: column('text', []), nano_per_million_input: column('bigint', numberFilters),
      nano_per_million_output: column('bigint', numberFilters), valid_from: column('timestamptz', [])
    },
    orderable: ['valid_from'], defaultOrder: { column: 'valid_from', direction: 'desc' }
  },
  minute_wallets: {
    table: 'minute_wallets',
    description: 'Carteras de minutos de conversación (milisegundos).',
    columns: {
      account_id: column('uuid', uuidFilters), balance_ms: column('bigint', numberFilters),
      reserved_ms: column('bigint', numberFilters), sandbox_balance_ms: column('bigint', numberFilters),
      sandbox_reconciled: column('boolean', boolFilters)
    },
    orderable: ['balance_ms', 'account_id'], defaultOrder: { column: 'balance_ms', direction: 'desc' }
  },
  minute_entries: {
    table: 'minute_entries',
    description: 'Asientos inmutables del ledger de minutos.',
    columns: {
      id: column('uuid', uuidFilters), account_id: column('uuid', uuidFilters),
      reference: column('text', textFilters), kind: column('text', ['eq', 'in']),
      balance_delta_ms: column('bigint', numberFilters), reserved_delta_ms: column('bigint', numberFilters),
      created_at: column('timestamptz', timeFilters), funding_source: column('text', ['eq', 'is-null', 'not-null'])
    },
    orderable: ['created_at', 'kind', 'account_id'], defaultOrder: { column: 'created_at', direction: 'desc' }
  },
  hosted_sessions: {
    table: 'hosted_sessions',
    description: 'Sesiones de voz alojadas con su financiación y cierre.',
    columns: {
      id: column('uuid', uuidFilters), account_id: column('uuid', uuidFilters),
      state: column('text', ['eq']), created_at: column('timestamptz', timeFilters),
      deadline: column('timestamptz', timeFilters), close_requested_at: column('timestamptz', timeFilters),
      observed_ms: column('bigint', numberFilters), charged_ms: column('bigint', numberFilters),
      reserved_ms: column('bigint', numberFilters), provider_cost_nano: column('bigint', numberFilters),
      charged_nano: column('bigint', numberFilters), funding_mode: column('text', ['eq']),
      close_reason: column('text', ['eq', 'is-null', 'not-null']), limit_ms: column('integer', numberFilters)
    },
    orderable: ['created_at', 'state', 'account_id'], defaultOrder: { column: 'created_at', direction: 'desc' }
  },
  hosted_helper_requests: {
    table: 'hosted_helper_requests',
    description: 'Peticiones de helpers de enseñanza de las sesiones de voz.',
    columns: {
      request_id: column('uuid', uuidFilters), session_id: column('uuid', uuidFilters),
      purpose: column('text', ['eq']), state: column('text', ['eq']),
      hold_nano: column('bigint', numberFilters), cost_nano: column('bigint', numberFilters),
      input_tokens: column('bigint', numberFilters), output_tokens: column('bigint', numberFilters),
      search_calls: column('integer', numberFilters), created_at: column('timestamptz', timeFilters)
    },
    orderable: ['created_at', 'purpose'], defaultOrder: { column: 'created_at', direction: 'desc' }
  },
  minute_purchase_orders: {
    table: 'minute_purchase_orders',
    description: 'Órdenes de compra de minutos y valor IA por proveedor.',
    columns: {
      id: column('uuid', uuidFilters), account_id: column('uuid', uuidFilters),
      provider: column('text', ['eq']), environment: column('text', ['eq']),
      sku: column('text', ['eq']), currency: column('text', ['eq']),
      total_minor: column('bigint', numberFilters), entitlement_kind: column('text', ['eq']),
      created_at: column('timestamptz', timeFilters)
    },
    orderable: ['created_at', 'total_minor', 'account_id'], defaultOrder: { column: 'created_at', direction: 'desc' }
  },
  checkout_orders: {
    table: 'checkout_orders',
    description: 'Órdenes sandbox antiguas de checkout.',
    columns: {
      id: column('uuid', uuidFilters), account_id: column('uuid', uuidFilters),
      product: column('text', ['eq']), state: column('text', ['eq']),
      currency: column('text', ['eq']), total_minor: column('bigint', numberFilters),
      credit_nano: column('bigint', numberFilters), refunded_minor: column('bigint', numberFilters),
      created_at: column('timestamptz', timeFilters)
    },
    orderable: ['created_at', 'state'], defaultOrder: { column: 'created_at', direction: 'desc' }
  },
  access_requests: {
    table: 'access_requests',
    description: 'Entradas de la waitlist con su versión de consentimiento.',
    columns: {
      email: column('text', textFilters), requested_at: column('timestamptz', timeFilters),
      consent_version: column('text', ['eq']), source: column('text', ['eq'])
    },
    orderable: ['requested_at', 'email'], defaultOrder: { column: 'requested_at', direction: 'desc' }
  },
  usage_records: {
    table: 'usage_records',
    description: 'Contabilidad de uso del proveedor por sesión.',
    columns: {
      account_id: column('uuid', uuidFilters), device_reference: column('text', textFilters),
      observed_ms: column('bigint', numberFilters), input_tokens: column('bigint', numberFilters),
      cached_input_tokens: column('bigint', numberFilters), output_tokens: column('bigint', numberFilters),
      search_calls: column('bigint', numberFilters), finalized: column('boolean', boolFilters),
      deadline: column('timestamptz', timeFilters)
    },
    orderable: ['deadline', 'observed_ms'], defaultOrder: { column: 'deadline', direction: 'desc' }
  },
  usage_events: {
    table: 'usage_events',
    description: 'Métricas de uso sin contenido enviadas por las apps (catálogo cerrado, inmutables, 180 días).',
    columns: {
      id: column('uuid', uuidFilters), client_event_id: column('uuid', uuidFilters),
      account_id: column('uuid', uuidFilters), name: column('text', ['eq']),
      platform: column('text', ['eq']), app_version: column('text', textFilters),
      props: column('jsonb', []), client_at: column('timestamptz', timeFilters),
      created_at: column('timestamptz', timeFilters)
    },
    orderable: ['created_at', 'client_at', 'name'], defaultOrder: { column: 'created_at', direction: 'desc' }
  },
  user_progress: {
    table: 'user_progress',
    description: 'Progreso de aprendizaje por cuenta e idioma: lecciones, temas, ejercicios, listening y prácticas.',
    columns: {
      account_id: column('uuid', uuidFilters), kind: column('text', ['eq']), item_id: column('text', textFilters),
      language: column('text', ['eq']),
      first_at: column('timestamptz', timeFilters), last_at: column('timestamptz', timeFilters),
      attempts: column('integer', numberFilters), best_score: column('integer', numberFilters)
    },
    orderable: ['last_at', 'first_at', 'kind'], defaultOrder: { column: 'last_at', direction: 'desc' }
  },
  subscriptions: {
    table: 'subscriptions',
    description: 'Suscripciones por cuenta (estado y periodo de tienda).',
    columns: {
      id: column('uuid', uuidFilters), account_id: column('uuid', uuidFilters),
      tier: column('text', ['eq']), status: column('text', ['eq']), store: column('text', ['eq']),
      store_reference: column('text', textFilters), period_start: column('timestamptz', timeFilters),
      period_end: column('timestamptz', timeFilters), cancel_at_period_end: column('boolean', boolFilters),
      created_at: column('timestamptz', timeFilters)
    },
    orderable: ['created_at', 'period_end', 'status'], defaultOrder: { column: 'created_at', direction: 'desc' }
  },
  minute_campaigns: {
    table: 'minute_campaigns',
    description: 'Campañas de minutos auditadas.',
    columns: {
      id: column('uuid', uuidFilters), actor: column('text', textFilters),
      state: column('text', ['eq']), minutes_per_user: column('integer', numberFilters),
      recipient_count: column('integer', numberFilters), created_at: column('timestamptz', timeFilters),
      applied_at: column('timestamptz', timeFilters)
    },
    orderable: ['created_at', 'state'], defaultOrder: { column: 'created_at', direction: 'desc' }
  },
  ai_feedback_reports: {
    table: 'ai_feedback_reports',
    description: 'Informes de IA del alumnado (ofensivo/incorrecto/idioma/otro).',
    columns: {
      id: column('uuid', uuidFilters), language_id: column('text', ['eq']),
      reason: column('text', ['eq']), excerpt: column('text', ['contains']),
      created_at: column('timestamptz', timeFilters), expires_at: column('timestamptz', timeFilters)
    },
    orderable: ['created_at', 'reason'], defaultOrder: { column: 'created_at', direction: 'desc' }
  },
  admin_actions: {
    table: 'admin_actions',
    description: 'Auditoría inmutable de cada mutación hecha desde el panel.',
    columns: {
      id: column('uuid', uuidFilters), admin_id: column('uuid', uuidFilters),
      action: column('text', ['eq']), target: column('text', textFilters),
      reason: column('text', ['contains']), detail: column('jsonb', []),
      created_at: column('timestamptz', timeFilters)
    },
    orderable: ['created_at', 'action'], defaultOrder: { column: 'created_at', direction: 'desc' }
  }
};

const MAX_LIMIT = 100, MAX_OFFSET = 1_000_000;
const CAST: Record<ColumnType, string> = { uuid: '::uuid', text: '', integer: '::integer', bigint: '::bigint',
  boolean: '::boolean', timestamptz: '::timestamptz', jsonb: '::jsonb' };
const UUID_PATTERN = /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i;

// Values are validated per column type before reaching a cast, so a malformed
// value is a clean 400 instead of a database error.
function assertValueValid(type: ColumnType, value: string): void {
  if (type === 'uuid') { if (!UUID_PATTERN.test(value)) throw new ServiceError('invalid_request'); return; }
  if (type === 'boolean') { if (value !== 'true' && value !== 'false') throw new ServiceError('invalid_request'); return; }
  if (type === 'integer' || type === 'bigint') {
    if (!/^-?\d{1,19}$/.test(value)) throw new ServiceError('invalid_request');
    if (Number(value) > Number.MAX_SAFE_INTEGER && type === 'integer') throw new ServiceError('invalid_request');
    return;
  }
  if (type === 'timestamptz') {
    if (value.length > 32 || !Number.isFinite(Date.parse(value))) throw new ServiceError('invalid_request');
    return;
  }
}

export interface BrowseQuery { table: BrowseTable; where: string; values: unknown[]; order: string; limit: number; offset: number }
export interface BrowseQueryResult extends BrowseQuery { sql: string; countSql: string }

export function buildBrowseQuery(tableName: string, query: Record<string, unknown>): BrowseQueryResult {
  const config = BROWSE_TABLES[tableName];
  if (!config) throw new ServiceError('table_not_browsable', 404);
  const values: unknown[] = [];
  const predicates: string[] = [];
  for (const [key, raw] of Object.entries(query)) {
    if (!key.startsWith('q_')) continue;
    const columnName = key.slice(2);
    const spec = config.columns[columnName];
    if (!spec) throw new ServiceError('invalid_request');
    if (typeof raw !== 'string' || !raw) throw new ServiceError('invalid_request');
    const separator = raw.indexOf(':');
    const operator = separator < 0 ? '' : raw.slice(0, separator);
    const value = separator < 0 ? '' : raw.slice(separator + 1);
    if (!spec.operators.includes(operator as FilterOperator)) throw new ServiceError('invalid_request');
    const identifier = `"${columnName}"`;
    if (operator === 'is-null' || operator === 'not-null') {
      if (value) throw new ServiceError('invalid_request');
      predicates.push(operator === 'is-null' ? `${identifier} IS NULL` : `${identifier} IS NOT NULL`);
      continue;
    }
    if (!value || value.length > 512) throw new ServiceError('invalid_request');
    if (operator === 'eq' || operator === 'neq') {
      assertValueValid(spec.type, value);
      values.push(value);
      predicates.push(`${identifier} ${operator === 'eq' ? '=' : '<>'} $${values.length}${CAST[spec.type]}`);
    } else if (operator === 'contains') {
      // Escape LIKE metacharacters so the value stays a literal pattern.
      values.push(`%${value.replace(/[\\%_]/g, matched => `\\${matched}`)}%`);
      predicates.push(`${identifier}::text ILIKE $${values.length}`);
    } else if (operator === 'in') {
      const parts = value.split(',').map(part => part.trim()).filter(Boolean);
      if (!parts.length || parts.length > 100) throw new ServiceError('invalid_request');
      for (const part of parts) assertValueValid(spec.type, part);
      values.push(parts);
      predicates.push(`${identifier} = ANY($${values.length}${CAST[spec.type]}[])`);
    } else {
      assertValueValid(spec.type, value);
      values.push(value);
      predicates.push(`${identifier} ${operator} $${values.length}${CAST[spec.type]}`);
    }
  }
  const sortColumn = typeof query.sort === 'string' && query.sort ? query.sort : config.defaultOrder.column;
  if (!config.orderable.includes(sortColumn)) throw new ServiceError('invalid_request');
  const direction = typeof query.dir === 'string' && query.dir ? query.dir : config.defaultOrder.direction;
  if (direction !== 'asc' && direction !== 'desc') throw new ServiceError('invalid_request');
  const limit = parseCount(query.limit, 50, 1, MAX_LIMIT);
  const offset = parseCount(query.offset, 0, 0, MAX_OFFSET);
  const where = predicates.length ? ` WHERE ${predicates.join(' AND ')}` : '';
  const order = ` ORDER BY "${sortColumn}" ${direction}, "${config.orderable[0]}" ${direction === 'asc' ? 'desc' : 'asc'}`;
  const selectList = Object.keys(config.columns).map(name => `"${name}"`).join(', ');
  const base = `FROM ${config.table}${where}`;
  return {
    table: config, where, values, order, limit, offset,
    sql: `SELECT ${selectList} ${base}${order} LIMIT ${limit} OFFSET ${offset}`,
    countSql: `SELECT count(*) AS total ${base}`
  };
}

function parseCount(value: unknown, fallback: number, minimum: number, maximum: number): number {
  if (value === undefined || value === '') return fallback;
  const parsed = Number(value);
  if (!Number.isSafeInteger(parsed) || parsed < minimum || parsed > maximum) throw new ServiceError('invalid_request');
  return parsed;
}

export function browseSchema(): Record<string, unknown> {
  return Object.fromEntries(Object.entries(BROWSE_TABLES).map(([name, config]) => [name, {
    description: config.description,
    columns: Object.fromEntries(Object.entries(config.columns).map(([columnName, spec]) =>
      [columnName, { type: spec.type, operators: spec.operators }])),
    orderable: config.orderable, defaultOrder: config.defaultOrder
  }]));
}
