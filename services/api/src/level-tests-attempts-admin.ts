import type { Database } from './db.js';
import { ServiceError } from './errors.js';

/** Admin read surface over student exam attempts (admin-exam-results).
    Language, level, week and source come from the immutable snapshot content,
    never from today's bank. Listings exclude answers and per-question review. */

export interface AttemptListQuery {
  account?: string; email?: string; language?: string; level?: string;
  state?: string; passed?: boolean;
  weekFrom?: number; weekTo?: number; from?: string; to?: string;
  sort: string; dir: 'asc' | 'desc'; limit: number; offset: number;
}

export interface AttemptMeta {
  accountId: string; attemptId: string; email: string | null; examId: string; source: string;
  languageID: string; level: string; weekId: number; state: string;
  startedAt: string; updatedAt: string; completedAt: string | null;
  overall: number | null; passed: boolean | null; sections: unknown;
}

const LANGUAGE_IDS = new Set(['en', 'es', 'fr', 'de', 'it', 'pt', 'nb', 'zh']);
const CEFR_LEVELS = new Set(['A1', 'A2', 'B1', 'B2', 'C1']);
const ATTEMPT_SORT: Record<string, string> = {
  started_at: 'a.started_at', updated_at: 'a.updated_at',
  state: 'a.state', overall: 'a.result->>\'overall\'',
};
const UUID = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i;

const str = (value: unknown, allow?: Set<string>, max = 64): string | undefined => {
  if (value === undefined) return undefined;
  if (typeof value !== 'string' || !value || value.length > max) throw new ServiceError('invalid_request');
  if (allow && !allow.has(value)) throw new ServiceError('invalid_request');
  return value;
};

const int = (value: unknown, min: number, max: number): number | undefined => {
  if (value === undefined) return undefined;
  if (typeof value !== 'string' || !/^-?\d+$/.test(value)) throw new ServiceError('invalid_request');
  const parsed = Number(value);
  if (!Number.isSafeInteger(parsed) || parsed < min || parsed > max) throw new ServiceError('invalid_request');
  return parsed;
};

const date = (value: unknown): string | undefined => {
  if (value === undefined) return undefined;
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value) || Number.isNaN(Date.parse(value)))
    throw new ServiceError('invalid_request');
  return value;
};

/** Parses admin results listing query parameters against strict whitelists. */
export function parseAttemptListQuery(query: Record<string, unknown>): AttemptListQuery {
  const sort = query.sort === undefined ? 'started_at' : String(query.sort);
  if (!ATTEMPT_SORT[sort]) throw new ServiceError('invalid_request');
  const dir = query.dir === undefined ? 'desc' : String(query.dir);
  if (dir !== 'asc' && dir !== 'desc') throw new ServiceError('invalid_request');
  const account = str(query.account, undefined, 64);
  if (account && !UUID.test(account)) throw new ServiceError('invalid_request');
  const passedRaw = str(query.passed, undefined, 5);
  if (passedRaw && passedRaw !== 'true' && passedRaw !== 'false') throw new ServiceError('invalid_request');
  return {
    account, email: str(query.email, undefined, 254), language: str(query.language, LANGUAGE_IDS, 8),
    level: str(query.level, CEFR_LEVELS, 4), state: str(query.state, new Set(['in_progress', 'completed']), 12),
    passed: passedRaw === undefined ? undefined : passedRaw === 'true',
    weekFrom: int(query.week_from, 0, 999999), weekTo: int(query.week_to, 0, 999999),
    from: date(query.from), to: date(query.to),
    sort, dir, limit: int(query.limit, 1, 100) ?? 50, offset: int(query.offset, 0, 100_000) ?? 0,
  };
}

const ATTEMPT_JOIN = `FROM level_test_attempts a
  JOIN level_test_attempt_snapshot_ids i ON i.account_id=a.account_id AND i.exam_id=a.exam_id
  JOIN level_test_attempt_snapshots s ON s.account_id=i.account_id AND s.content_id=i.content_id
  JOIN accounts acc ON acc.id=a.account_id AND acc.deleted_at IS NULL`;

const attemptWhere = (q: AttemptListQuery): { clause: string; values: unknown[] } => {
  const where: string[] = [], values: unknown[] = [];
  const push = (fragment: string, value: unknown) => { values.push(value); where.push(fragment.replace('$', `$${values.length}`)); };
  if (q.account) push('a.account_id = $', q.account);
  if (q.email) { values.push(`%${q.email}%`); where.push(`acc.email ILIKE $${values.length}`); }
  if (q.language) push(`s.content_json->>'languageID' = $`, q.language);
  if (q.level) push(`s.content_json->>'level' = $`, q.level);
  if (q.state) push('a.state = $', q.state);
  if (q.passed !== undefined) push(`(a.result->>'passed')::boolean = $`, q.passed);
  if (q.weekFrom !== undefined) push(`(s.content_json->>'weekId')::int >= $`, q.weekFrom);
  if (q.weekTo !== undefined) push(`(s.content_json->>'weekId')::int <= $`, q.weekTo);
  if (q.from !== undefined) push('a.started_at >= $::date', q.from);
  if (q.to !== undefined) push(`a.started_at < ($::date + interval '1 day')`, q.to);
  return { clause: where.length ? ` WHERE ${where.join(' AND ')}` : '', values };
};

const attemptItem = (row: Record<string, unknown>) => ({
  accountId: row.account_id as string, attemptId: row.attempt_id as string, email: row.email as string | null,
  examId: row.exam_id as string, source: row.source as string, languageID: row.language_id as string,
  level: row.level as string, weekId: row.week_id as number, state: row.state as string,
  startedAt: row.started_at instanceof Date ? row.started_at.toISOString() : String(row.started_at),
  updatedAt: row.updated_at instanceof Date ? row.updated_at.toISOString() : String(row.updated_at),
  completedAt: row.completed_at ? (row.completed_at instanceof Date ? row.completed_at.toISOString() : String(row.completed_at)) : null,
  overall: row.overall === null || row.overall === undefined ? null : Number(row.overall),
  passed: row.passed === null || row.passed === undefined ? null : row.passed === 'true',
  sections: row.sections ?? [],
});

/** Filtered, sorted, paginated listing of student attempts (metadata only). */
export async function listLevelTestAttempts(db: Database, q: AttemptListQuery):
  Promise<{ items: ReturnType<typeof attemptItem>[]; total: number; limit: number; offset: number }> {
  const { clause, values } = attemptWhere(q);
  const sort = `${ATTEMPT_SORT[q.sort]} ${q.dir.toUpperCase()} NULLS LAST`;
  const window = ` LIMIT ${q.limit} OFFSET ${q.offset}`;
  const [items, total] = await Promise.all([
    db.query(`SELECT a.account_id, a.attempt_id, acc.email, a.exam_id, s.content_json->>'source' AS source,
      s.content_json->>'languageID' AS language_id, s.content_json->>'level' AS level,
      (s.content_json->>'weekId')::int AS week_id, a.state, a.started_at, a.updated_at, a.completed_at,
      a.result->>'overall' AS overall, a.result->>'passed' AS passed, a.result->'sections' AS sections ${ATTEMPT_JOIN}${clause}
      ORDER BY ${sort}${window}`, values),
    db.query(`SELECT count(*)::int AS total ${ATTEMPT_JOIN}${clause}`, values),
  ]);
  return { items: items.rows.map(attemptItem), total: total.rows[0]?.total ?? 0, limit: q.limit, offset: q.offset };
}

/** Full attempt: answers plus the complete grading result. */
export async function levelTestAttemptDetail(db: Database, accountId: string, attemptId: string):
  Promise<Record<string, unknown> | undefined> {
  const row = (await db.query(`SELECT a.account_id, a.attempt_id, acc.email, a.exam_id,
    s.content_json->>'source' AS source, s.content_json->>'languageID' AS language_id,
    s.content_json->>'level' AS level, (s.content_json->>'weekId')::int AS week_id,
    a.state, a.started_at, a.updated_at, a.completed_at, a.answers, a.result ${ATTEMPT_JOIN}
    WHERE a.account_id=$1 AND a.attempt_id=$2`, [accountId, attemptId])).rows[0] as
    Record<string, unknown> | undefined;
  if (!row) return undefined;
  return { ...attemptItem(row), answers: row.answers, result: row.result };
}

const SUMMARY_JOIN = `FROM level_test_attempts a
  JOIN level_test_attempt_snapshot_ids i ON i.account_id=a.account_id AND i.exam_id=a.exam_id
  JOIN level_test_attempt_snapshots s ON s.account_id=i.account_id AND s.content_id=i.content_id`;

const filterClause = (values: unknown[], filters: { language?: string; level?: string }): string => {
  if (filters.language) values.push(filters.language);
  if (filters.level) values.push(filters.level);
  return (filters.language ? ` AND s.content_json->>'languageID' = $1` : '') +
    (filters.level ? ` AND s.content_json->>'level' = $${values.length}` : '');
};

/** Aggregate KPIs over completed attempts, with week/level evolution breakdowns. */
export async function levelTestAttemptSummary(db: Database,
  filters: { language?: string; level?: string; group?: 'week' | 'level' }): Promise<Record<string, unknown>> {
  const values: unknown[] = [], clause = filterClause(values, filters);
  const completed = ` WHERE a.state='completed'${clause}`;
  const [total, aggregates] = await Promise.all([
    db.query(`SELECT count(*)::int AS total ${SUMMARY_JOIN} WHERE true${clause}`, values),
    db.query(`SELECT count(*)::int AS completed, COALESCE(SUM((a.result->>'passed')::boolean)::int, 0) AS passed,
      round(avg((a.result->>'overall')::numeric), 1) AS avg_overall ${SUMMARY_JOIN}${completed}`, values),
  ]);
  const groupColumn = filters.group === 'level' ? `s.content_json->>'level'` : `(s.content_json->>'weekId')::int`;
  const groups = (await db.query(`SELECT ${groupColumn} AS key, count(*)::int AS completed,
    COALESCE(SUM((a.result->>'passed')::boolean)::int, 0) AS passed,
    round(avg((a.result->>'overall')::numeric), 1) AS avg_overall ${SUMMARY_JOIN}${completed}
    GROUP BY ${groupColumn} ORDER BY key DESC`, values)).rows as Record<string, unknown>[];
  return {
    total: total.rows[0]?.total ?? 0, completed: Number(aggregates.rows[0]?.completed ?? 0),
    passed: Number(aggregates.rows[0]?.passed ?? 0),
    avgOverall: aggregates.rows[0]?.avg_overall === null || aggregates.rows[0]?.avg_overall === undefined
      ? null : Number(aggregates.rows[0].avg_overall),
    group: filters.group ?? 'week',
    groups: groups.map(row => ({ key: row.key, total: Number(row.completed), completed: Number(row.completed),
      passed: Number(row.passed), avgOverall: row.avg_overall === null ? null : Number(row.avg_overall) })),
  };
}

const CSV_COLUMNS = ['attempt_id', 'account_id', 'email', 'exam_id', 'source', 'language_id', 'level', 'week_id', 'state', 'started_at', 'completed_at', 'overall', 'passed'] as const;

const csvCell = (value: unknown): string => {
  const text = value === null || value === undefined ? '' : String(value);
  return /^[=+\-@]/.test(text) ? `'${text}` : text;
};

/** CSV of the filtered selection with formula-prefix escaping, capped at 10k rows. */
export async function attemptCsv(db: Database, q: AttemptListQuery): Promise<string | undefined> {
  const { clause, values } = attemptWhere(q);
  const total = (await db.query(`SELECT count(*)::int AS total ${ATTEMPT_JOIN}${clause}`, values)).rows[0]?.total ?? 0;
  if (total > 10_000) return undefined;
  const rows = (await db.query(`SELECT a.attempt_id, a.account_id, acc.email, a.exam_id,
    s.content_json->>'source' AS source, s.content_json->>'languageID' AS language_id,
    s.content_json->>'level' AS level, (s.content_json->>'weekId')::int AS week_id,
    a.state, a.started_at, a.completed_at, a.result->>'overall' AS overall, a.result->>'passed' AS passed
    ${ATTEMPT_JOIN}${clause} ORDER BY a.started_at DESC LIMIT ${total}`, values)).rows as Record<string, unknown>[];
  const escape = (text: string) => /[",\n]/.test(text) ? `"${text.replaceAll('"', '""')}"` : text;
  const lines = [CSV_COLUMNS.join(',')];
  for (const row of rows) lines.push(CSV_COLUMNS.map(column => escape(csvCell(row[column]))).join(','));
  return lines.join('\n') + '\n';
}
