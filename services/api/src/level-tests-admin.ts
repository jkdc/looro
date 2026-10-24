import type { Database } from './db.js';
import { randomUUID } from 'node:crypto';
import { ServiceError } from './errors.js';
import { weekIndex } from './level-tests.js';
import { runLevelTestGenerationJob } from './level-tests-generation-jobs.js';
import type { LevelTestSchedulerConfig } from './level-tests-scheduler.js';

/** Admin read surface over generated level-test weeks (admin-exam-management).
    Listings carry metadata only: pool, variants and review stay behind the
    detail endpoint so list payloads never grow with exam content. */

const LANGUAGE_IDS = new Set(['en', 'es', 'fr', 'de', 'it', 'pt', 'nb', 'zh']);
const CEFR_LEVELS = new Set(['A1', 'A2', 'B1', 'B2', 'C1']);

/** Regeneration guard: current week only, and never over an approved key. */
export async function validateRegenerateRequest(db: Database, languageID: string, level: string,
  weekId: number): Promise<void> {
  if (!LANGUAGE_IDS.has(languageID) || !CEFR_LEVELS.has(level)) throw new ServiceError('invalid_request');
  if (weekId !== weekIndex(new Date())) throw new ServiceError('invalid_request');
  const approved = await db.query(`SELECT 1 FROM level_test_weeks WHERE language_id=$1 AND level=$2
    AND week_index=$3 AND status='approved'`, [languageID, level, weekId]);
  if (approved.rowCount) throw new ServiceError('level_test_week_not_regenerable', 409);
}

export interface GenerationLaunchKey { languageID: string; level: string;
  status: 'started' | 'skipped_approved' | 'skipped_existing'; runCorrelationId?: string }

/** Plans an on-demand launch: current week only. Approved keys are protected,
    and any other existing week is left to the explicit regenerate path: the
    runner would answer `exists` without generating, which the panel would
    report as a completed run that never happened. */
export async function planGenerationLaunch(db: Database, languageID: string, levels: string[],
  weekId: number): Promise<GenerationLaunchKey[]> {
  if (!LANGUAGE_IDS.has(languageID) || weekId !== weekIndex(new Date())) throw new ServiceError('invalid_request');
  if (!levels.length || levels.some(level => !CEFR_LEVELS.has(level))) throw new ServiceError('invalid_request');
  const existing = await db.query(`SELECT level, status FROM level_test_weeks WHERE language_id=$1
    AND week_index=$2 AND level = ANY($3)`, [languageID, weekId, levels]);
  const byLevel = new Map(existing.rows.map(row => [row.level as string, row.status as string]));
  return levels.map(level => {
    const current = byLevel.get(level);
    return {
      languageID, level,
      status: current === 'approved' ? 'skipped_approved' as const
        : current ? 'skipped_existing' as const : 'started' as const,
      ...(current ? {} : { runCorrelationId: randomUUID() }),
    };
  });
}

/** Runs the planned keys sequentially in the background. Each job records its
    own state (exists → finished, failure → failed) in the jobs table; the
    advisory lock already serializes against CLI, scheduler and regeneration. */
export function runGenerationLaunch(db: Database, env: NodeJS.ProcessEnv,
  keys: GenerationLaunchKey[], weekId: number): void {
  void (async () => {
    for (const key of keys) {
      if (key.status !== 'started') continue;
      try {
        await runLevelTestGenerationJob(db, env, { languageID: key.languageID, level: key.level, weekIndex: weekId,
          origin: 'user_operator', manual: true }, key.runCorrelationId);
      } catch { /* the job runner records failures itself */ }
    }
  })();
}

export interface WeekListQuery {
  status?: string[]; language?: string[]; level?: string[];
  weekFrom?: number; weekTo?: number; createdFrom?: string; createdTo?: string;
  sort: string; dir: 'asc' | 'desc'; limit: number; offset: number;
}

export interface WeekMeta {
  id: string; languageID: string; level: string; weekId: number; status: string;
  judgeMode: string; generator: string; model: string; variantCount: number;
  tokensIn: number | null; tokensOut: number | null; createdAt: string; approvedAt: string | null; approvedBy: string | null;
  origin: string;
}

export type WeekDetail = WeekMeta & {
  pool: unknown; variants: unknown[]; review: unknown;
  promptVersion: number | null; judgeVersion: number | null;
  retiredAt: string | null; retiredBy: string | null; retiredReason: string | null;
};

const WEEK_STATUS = new Set(['draft', 'approved', 'rejected', 'retired']);
const WEEK_SORT = new Set(['created_at', 'week_index', 'language_id', 'level', 'status', 'approved_at']);

const csv = (value: unknown, allow: Set<string>, label: string): string[] | undefined => {
  if (value === undefined) return undefined;
  if (typeof value !== 'string' || !value) throw new ServiceError('invalid_request');
  const items = value.split(',').map(entry => entry.trim()).filter(Boolean);
  if (!items.length || items.some(item => !allow.has(item))) throw new ServiceError('invalid_request');
  return items;
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

/** Parses admin listing query parameters against strict whitelists. */
export function parseWeekListQuery(query: Record<string, unknown>): WeekListQuery {
  const sort = query.sort === undefined ? 'created_at' : String(query.sort);
  if (!WEEK_SORT.has(sort)) throw new ServiceError('invalid_request');
  const dir = query.dir === undefined ? 'desc' : String(query.dir);
  if (dir !== 'asc' && dir !== 'desc') throw new ServiceError('invalid_request');
  return {
    status: csv(query.status, WEEK_STATUS, 'status'), language: csv(query.language, new Set(['en', 'es', 'fr', 'de', 'it', 'pt', 'nb', 'zh']), 'language'),
    level: csv(query.level, new Set(['A1', 'A2', 'B1', 'B2', 'C1']), 'level'),
    weekFrom: int(query.week_from, 0, 999999), weekTo: int(query.week_to, 0, 999999),
    createdFrom: date(query.created_from), createdTo: date(query.created_to),
    sort, dir, limit: int(query.limit, 1, 100) ?? 50, offset: int(query.offset, 0, 100_000) ?? 0,
  };
}

const iso = (value: unknown) => value instanceof Date ? value.toISOString() : (value as string | null) ?? null;

const weekRow = (row: Record<string, unknown>): WeekMeta => ({
  id: row.id as string, languageID: row.language_id as string, level: row.level as string,
  weekId: row.week_index as number, status: row.status as string, judgeMode: row.judge_mode as string,
  generator: row.generator as string, model: row.model as string,
  variantCount: Array.isArray(row.variants) ? row.variants.length : 0,
  tokensIn: row.tokens_in == null ? null : Number(row.tokens_in),
  tokensOut: row.tokens_out == null ? null : Number(row.tokens_out),
  createdAt: iso(row.created_at) as string, approvedAt: iso(row.approved_at) as string | null,
  approvedBy: (row.approved_by as string | null) ?? null, origin: (row.origin as string | undefined) ?? 'unknown',
});

const WEEK_META_COLUMNS = `id, language_id, level, week_index, status, judge_mode, generator, model,
  jsonb_array_length(variants) AS variant_count, tokens_in, tokens_out, created_at, approved_at, approved_by, origin`;

const metadataRow = (row: Record<string, unknown>): WeekMeta => ({ ...weekRow(row),
  variantCount: row.variant_count as number });

/** Filtered, sorted, paginated week listing (metadata only). */
export async function listLevelTestWeeks(db: Database, q: WeekListQuery):
  Promise<{ items: WeekMeta[]; total: number; limit: number; offset: number }> {
  const where: string[] = [], values: unknown[] = [];
  if (q.status) { values.push(q.status); where.push(`status = ANY($${values.length})`); }
  if (q.language) { values.push(q.language); where.push(`language_id = ANY($${values.length})`); }
  if (q.level) { values.push(q.level); where.push(`level = ANY($${values.length})`); }
  if (q.weekFrom !== undefined) { values.push(q.weekFrom); where.push(`week_index >= $${values.length}`); }
  if (q.weekTo !== undefined) { values.push(q.weekTo); where.push(`week_index <= $${values.length}`); }
  if (q.createdFrom !== undefined) { values.push(q.createdFrom); where.push(`created_at >= $${values.length}::date`); }
  if (q.createdTo !== undefined) { values.push(q.createdTo); where.push(`created_at < ($${values.length}::date + interval '1 day')`); }
  const clause = where.length ? ` WHERE ${where.join(' AND ')}` : '';
  const sort = `${q.sort} ${q.dir.toUpperCase()}`, window = ` LIMIT ${q.limit} OFFSET ${q.offset}`;
  const [items, total] = await Promise.all([
    db.query(`SELECT ${WEEK_META_COLUMNS} FROM level_test_weeks${clause} ORDER BY ${sort}${window}`, values),
    db.query(`SELECT count(*)::int AS total FROM level_test_weeks${clause}`, values),
  ]);
  return { items: items.rows.map(metadataRow), total: total.rows[0]?.total ?? 0, limit: q.limit, offset: q.offset };
}

/** Full week content: metadata plus the heavy exam payload. */
export async function levelTestWeekDetail(db: Database, id: string): Promise<WeekDetail | undefined> {
  const row = (await db.query(`SELECT id, language_id, level, week_index, status, judge_mode, generator, model,
    pool, variants, review, tokens_in, tokens_out, created_at, approved_at, approved_by, origin,
    retired_at, retired_by, retired_reason,
    prompt_version, judge_version
    FROM level_test_weeks WHERE id=$1`, [id])).rows[0] as Record<string, unknown> | undefined;
  if (!row) return undefined;
  return { ...weekRow(row), pool: row.pool, variants: row.variants as unknown[], review: row.review,
    promptVersion: (row.prompt_version as number | null) ?? null,
    judgeVersion: (row.judge_version as number | null) ?? null,
    retiredAt: iso(row.retired_at), retiredBy: (row.retired_by as string | null) ?? null,
    retiredReason: (row.retired_reason as string | null) ?? null };
}

type GenerationJobRow = { id: string; language_id: string; level: string; week_index: number; state: string; attempts: number;
  error_code: string | null; error_details: unknown; started_at: Date | null; finished_at: Date | null; updated_at: Date };

/** Builds the operator status view without coupling the query/projection to HTTP. */
export async function levelTestGenerationStatus(db: Database,
  generation: { config: LevelTestSchedulerConfig; isStarted: () => boolean } | undefined,
  runCorrelationIds?: string[]) {
  const latest = (await db.query(`SELECT id, language_id, level, week_index, state, attempts, error_code, error_details,
    started_at, finished_at, updated_at FROM level_test_generation_jobs ORDER BY updated_at DESC LIMIT 1`))
    .rows[0] as GenerationJobRow | undefined;
  const jobs = (await db.query(`SELECT id, language_id, level, week_index, state, attempts, error_code, error_details,
    started_at, finished_at, updated_at FROM level_test_generation_jobs ORDER BY updated_at DESC LIMIT 10`))
    .rows as GenerationJobRow[];
  const iso = (value: Date | null | undefined) => value instanceof Date ? value.toISOString() : null;
  const jobItem = (job: GenerationJobRow) => ({ runId: job.id, attemptOrdinal: job.attempts,
    languageID: job.language_id, level: job.level, weekId: job.week_index, status: job.state, attempts: job.attempts, errorCode: job.error_code,
    errors: Array.isArray(job.error_details) ? job.error_details.filter(entry => typeof entry === 'string') : [],
    startedAt: iso(job.started_at), finishedAt: iso(job.finished_at), updatedAt: iso(job.updated_at) });
  const trackedRuns = runCorrelationIds ? await db.query(`SELECT id, language_id, level, week_index, state, attempts,
    error_code, error_details, started_at, finished_at, updated_at FROM level_test_generation_jobs WHERE id = ANY($1::uuid[])`,
  [runCorrelationIds]) : undefined;
  const exactRuns = trackedRuns && new Map((trackedRuns.rows as GenerationJobRow[]).map(job => [job.id, job]));
  return {
    schedule: generation ? { enabled: generation.config.enabled, day: generation.config.day, hour: generation.config.hour,
      languages: generation.config.languages, levels: generation.config.levels, timeoutMs: generation.config.timeoutMs }
      : { enabled: false, day: null, hour: null, languages: [], levels: [], timeoutMs: null },
    scheduler: { activeInProcess: generation?.isStarted() ?? false },
    latestJob: latest ? jobItem(latest) : null,
    jobs: jobs.map(jobItem),
    ...(runCorrelationIds ? { trackedRuns: runCorrelationIds.map(correlationId => {
      const run = exactRuns?.get(correlationId);
      return run ? { correlationId, registration: 'registered' as const, run: jobItem(run) }
        : { correlationId, registration: 'pending' as const };
    }) } : {}),
  };
}

function pageNumber(value: unknown, fallback: number, max: number): number {
  if (value === undefined) return fallback;
  if (typeof value !== 'string' || !/^(0|[1-9][0-9]*)$/.test(value)) throw new ServiceError('invalid_request');
  const parsed = Number(value);
  if (!Number.isSafeInteger(parsed) || parsed > max) throw new ServiceError('invalid_request');
  return parsed;
}

/** Lists reviewable drafts with the existing bounded pagination and wire projection. */
export async function listLevelTestDrafts(db: Database, query: Record<string, unknown>) {
  const limit = pageNumber(query.limit, 50, 100);
  if (limit < 1) throw new ServiceError('invalid_request');
  const offset = pageNumber(query.offset, 0, 100_000);
  const rows = (await db.query(`SELECT id, language_id, level, week_index, status, pool, variants, judge_mode, review, created_at
    FROM level_test_weeks WHERE status IN ('draft','rejected') ORDER BY created_at DESC, id DESC LIMIT $1 OFFSET $2`,
  [limit, offset])).rows;
  return { items: rows.map(row => ({ id: row.id, languageID: row.language_id, level: row.level,
    weekId: row.week_index, status: row.status, pool: row.pool, variants: row.variants,
    judgeMode: row.judge_mode, review: row.review,
    createdAt: row.created_at instanceof Date ? row.created_at.toISOString() : row.created_at })), limit, offset };
}
