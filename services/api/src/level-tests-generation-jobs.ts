import type { PoolClient } from 'pg';
import type { Database } from './db.js';
import { generateWeeklyLevelTest, type GenerateOptions, type GenerationOutcome } from './level-tests-llm.js';

export type GenerationJobResult = GenerationOutcome | {
  status: 'busy' | 'unknown'; languageID: string; level: string; weekId: number; errors?: string[];
};

const MAX_AUTOMATIC_ATTEMPTS = 2;
const lockKey = (languageID: string, level: string, weekId: number) => `level-test-generation:${languageID}:${level}:${weekId}`;

/** Serializes CLI and scheduler generation before any external provider call. */
export async function runLevelTestGenerationJob(
  db: Database, env: NodeJS.ProcessEnv, options: GenerateOptions,
): Promise<GenerationJobResult> {
  const weekId = options.weekIndex;
  if (!Number.isSafeInteger(weekId) || (weekId as number) < 0) throw new Error('invalid_generation_week');
  const client = await db.connect();
  let locked = false;
  try {
    const acquired = await client.query('SELECT pg_try_advisory_lock(hashtextextended($1, 0)) AS locked',
      [lockKey(options.languageID, options.level, weekId!)]);
    locked = acquired.rows[0]?.locked === true;
    if (!locked) return { status: 'busy', languageID: options.languageID, level: options.level, weekId: weekId! };

    const prior = (await client.query(`SELECT state, attempts FROM level_test_generation_jobs
      WHERE language_id=$1 AND level=$2 AND week_index=$3`, [options.languageID, options.level, weekId])).rows[0] as
      { state: string; attempts: number } | undefined;
    const existing = (await client.query(`SELECT status, variants FROM level_test_weeks
      WHERE language_id=$1 AND level=$2 AND week_index=$3`, [options.languageID, options.level, weekId])).rows[0] as
      { status: string; variants: unknown[] } | undefined;

    if (existing && !options.force) {
      await saveState(client, options, weekId!, 'finished', prior?.attempts ?? 0, null, []);
      return { status: 'exists', languageID: options.languageID, level: options.level, weekId: weekId!,
        variants: Array.isArray(existing.variants) ? existing.variants.length : 0 };
    }
    if (!options.force && prior?.state === 'unknown')
      return { status: 'unknown', languageID: options.languageID, level: options.level, weekId: weekId!, errors: ['reconciliation_required'] };
    if (!options.force && prior?.state === 'running') {
      await saveState(client, options, weekId!, 'unknown', prior.attempts, 'interrupted_generation',
        ['interrupted_generation']);
      return { status: 'unknown', languageID: options.languageID, level: options.level, weekId: weekId!, errors: ['reconciliation_required'] };
    }
    if (!options.force && prior?.state === 'finished')
      return { status: 'unknown', languageID: options.languageID, level: options.level, weekId: weekId!, errors: ['finished_without_week'] };
    if (!options.force && prior?.state === 'failed' && prior.attempts >= MAX_AUTOMATIC_ATTEMPTS)
      return { status: 'failed', languageID: options.languageID, level: options.level, weekId: weekId!, errors: ['attempt_limit_reached'] };

    const attempt = (prior?.attempts ?? 0) + 1;
    await saveState(client, options, weekId!, 'running', attempt, null, []);
    let outcome: GenerationOutcome;
    try {
      outcome = await generateWeeklyLevelTest({ db, env }, { ...options, weekIndex: weekId, signal: options.signal });
    } catch (error) {
      const unknown = options.signal?.aborted || (error instanceof Error && error.message === 'llm_outcome_unknown');
      await saveState(client, options, weekId!, unknown ? 'unknown' : 'failed', attempt,
        unknown ? 'llm_outcome_unknown' : 'generation_failed',
        [unknown ? 'llm_outcome_unknown' : 'generation_failed']);
      if (unknown) return { status: 'unknown', languageID: options.languageID, level: options.level, weekId: weekId!, errors: ['llm_outcome_unknown'] };
      return { status: 'failed', languageID: options.languageID, level: options.level, weekId: weekId!, errors: ['generation_failed'] };
    }
    const unknown = options.signal?.aborted || (outcome.status === 'failed' && outcome.errors?.some(code => code.includes('llm_outcome_unknown')));
    const state = unknown ? 'unknown' : outcome.status === 'failed' ? 'failed' : 'finished';
    const errorCode = unknown ? 'llm_outcome_unknown' : outcome.status === 'failed' ? safeCode(outcome.errors?.[0]) : null;
    // Keep the section-level reasons for the operator-facing error dialog; the
    // coarse error_code stays the stable contract for badges and summaries.
    const details = state === 'finished' ? [] : sanitizeGenerationErrorDetails(outcome.errors, unknown ? 'llm_outcome_unknown' : errorCode);
    await saveState(client, options, weekId!, state, attempt, errorCode, details);
    if (unknown) return { ...outcome, status: 'unknown', errors: ['llm_outcome_unknown'] };
    return outcome.status === 'failed' ? { ...outcome, errors: [errorCode ?? 'generation_failed'] } : outcome;
  } finally {
    if (locked) {
      try { await client.query('SELECT pg_advisory_unlock(hashtextextended($1, 0))',
        [lockKey(options.languageID, options.level, weekId!)]); } catch { /* connection release drops the session lock */ }
    }
    client.release();
  }
}

function safeCode(value: string | undefined): string {
  return value && /^[a-z0-9_]{1,80}$/.test(value) ? value : 'generation_failed';
}

async function saveState(client: PoolClient, options: GenerateOptions, weekId: number,
  state: 'running' | 'finished' | 'failed' | 'unknown', attempts: number, errorCode: string | null,
  errorDetails: string[]): Promise<void> {
  await client.query(`INSERT INTO level_test_generation_jobs(language_id,level,week_index,state,attempts,error_code,error_details,started_at,finished_at,updated_at)
    VALUES($1,$2,$3,$4,$5,$6,$7::jsonb,CASE WHEN $4='running' THEN now() ELSE NULL END,CASE WHEN $4='running' THEN NULL ELSE now() END,now())
    ON CONFLICT(language_id,level,week_index) DO UPDATE SET state=EXCLUDED.state,attempts=EXCLUDED.attempts,
      error_code=EXCLUDED.error_code,error_details=EXCLUDED.error_details,
      started_at=CASE WHEN EXCLUDED.state='running' THEN now() ELSE level_test_generation_jobs.started_at END,
      finished_at=CASE WHEN EXCLUDED.state='running' THEN NULL ELSE now() END,updated_at=now()`,
  [options.languageID, options.level, weekId, state, attempts, errorCode, JSON.stringify(errorDetails)]);
}

const MAX_ERROR_DETAILS = 10;
const SAFE_DETAIL = /^[a-z0-9_.,()%:\- ]+$/;

/** Keeps the messages the pipeline writes itself (`reading: only 2 passages
    (needed 4)`) and drops anything that looks like provider output: uppercase,
    quotes, braces or control characters never survive this filter. */
export function sanitizeGenerationErrorDetails(errors: string[] | undefined, fallback: string | null): string[] {
  const out: string[] = [];
  for (const value of errors ?? []) {
    const cleaned = String(value).replace(/[\u0000-\u001f]/g, ' ').replace(/\s+/g, ' ').trim().slice(0, 160);
    if (!cleaned || !SAFE_DETAIL.test(cleaned)) continue;
    if (!out.includes(cleaned)) out.push(cleaned);
    if (out.length >= MAX_ERROR_DETAILS) break;
  }
  if (!out.length && fallback) out.push(fallback);
  return out;
}
