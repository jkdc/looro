import type { PoolClient } from 'pg';
import { randomUUID } from 'node:crypto';
import { ROOT_CONTEXT, trace, type Span } from '@opentelemetry/api';
import type { Database } from './db.js';
import { generateWeeklyLevelTest, generationVersionsFor, type GenerateOptions, type GenerationOutcome } from './level-tests-llm.js';
import { paliqueMetrics } from './metrics.js';
import type { GenerationUsage } from './level-test-generation-usage.js';

export type GenerationJobResult = GenerationOutcome | {
  status: 'busy' | 'unknown'; languageID: string; level: string; weekId: number; errors?: string[];
};

const MAX_AUTOMATIC_ATTEMPTS = 2;
const lockKey = (languageID: string, level: string, weekId: number) => `level-test-generation:${languageID}:${level}:${weekId}`;

/** One generation attempt with outcome metrics: approved/draft count as
    finished (recording the last-success timestamp), failed and unknown are
    counted too; 'busy' and 'exists' are idempotent no-ops that record nothing. */
export async function runLevelTestGenerationJob(db: Database, env: NodeJS.ProcessEnv,
    options: GenerateOptions, runCorrelationId?: string): Promise<GenerationJobResult> {
  const startedAt = performance.now();
  let status: 'finished' | 'failed' | 'unknown' | null = null;
  try {
    const result = await runLevelTestGenerationJobOnce(db, env, options, runCorrelationId);
    status = result.status === 'approved' || result.status === 'draft' ? 'finished' :
      result.status === 'failed' ? 'failed' : result.status === 'unknown' ? 'unknown' : null;
    return result;
  } catch (error) {
    status = 'failed';
    throw error;
  } finally {
    if (status) {
      paliqueMetrics.generationJobs.add(1, { language: options.languageID, level: options.level, status });
      paliqueMetrics.generationJobDuration.record((performance.now() - startedAt) / 1000,
        { language: options.languageID, level: options.level });
      if (status === 'finished') paliqueMetrics.generationLastSuccess.record(Date.now() / 1000);
      if (status === 'failed') paliqueMetrics.generationLastFailure.record(Date.now() / 1000);
    }
  }
}

/** Serializes CLI and scheduler generation before any external provider call. */
async function runLevelTestGenerationJobOnce(
  db: Database, env: NodeJS.ProcessEnv, options: GenerateOptions, runCorrelationId?: string,
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

    const prior = (await client.query(`SELECT id, state, attempts FROM level_test_generation_jobs
      WHERE language_id=$1 AND level=$2 AND week_index=$3 ORDER BY attempts DESC LIMIT 1`,
    [options.languageID, options.level, weekId])).rows[0] as
      { id: string; state: string; attempts: number } | undefined;
    const existing = (await client.query(`SELECT status, variants FROM level_test_weeks
      WHERE language_id=$1 AND level=$2 AND week_index=$3`, [options.languageID, options.level, weekId])).rows[0] as
      { status: string; variants: unknown[] } | undefined;

    if (existing && !options.force) {
      // Nothing was generated, so nothing is recorded: a run row here would show
      // the operator a completed generation that never happened.
      return { status: 'exists', languageID: options.languageID, level: options.level, weekId: weekId!,
        variants: Array.isArray(existing.variants) ? existing.variants.length : 0 };
    }
    if (!options.force && prior?.state === 'unknown')
      return { status: 'unknown', languageID: options.languageID, level: options.level, weekId: weekId!, errors: ['reconciliation_required'] };
    if (!options.force && prior?.state === 'running') {
      await closeRun(client, prior.id, 'unknown', 'interrupted_generation', ['interrupted_generation']);
      return { status: 'unknown', languageID: options.languageID, level: options.level, weekId: weekId!, errors: ['reconciliation_required'] };
    }
    if (!options.force && prior?.state === 'finished')
      return { status: 'unknown', languageID: options.languageID, level: options.level, weekId: weekId!, errors: ['finished_without_week'] };
    // The cap protects the automatic paths (scheduler/CLI) from re-running a
    // broken generation forever. An explicit operator launch may retry it.
    if (!options.force && !options.manual && prior?.state === 'failed' && prior.attempts >= MAX_AUTOMATIC_ATTEMPTS)
      return { status: 'failed', languageID: options.languageID, level: options.level, weekId: weekId!, errors: ['attempt_limit_reached'] };

    const attempt = (prior?.attempts ?? 0) + 1;
    const runId = await startRun(client, options, weekId!, attempt, runCorrelationId);
    const started = performance.now();
    const versions = generationVersionsFor(options.languageID);
    let span: Span | undefined;
    try {
      span = trace.getTracer('palique-api').startSpan('level_test_generation', { attributes: {
        'generation.run_id': runId, 'generation.language': options.languageID, 'generation.level': options.level,
        'generation.week_index': weekId!, 'generation.attempt': attempt,
        'generation.prompt_version': versions.promptVersion, 'generation.judge_version': versions.judgeVersion,
      } }, ROOT_CONTEXT);
    } catch { /* Tracing must not alter durable job handling. */ }
    let terminal: GenerationOutcome['status'] | 'unknown' = 'failed';
    try {
      let outcome: GenerationOutcome;
      try {
        outcome = await generateWeeklyLevelTest({ db, env, onDiagnostic: (name, fields) => { span?.addEvent(name, fields); }, onUsage: async usage => {
          const updated = await client.query(`UPDATE level_test_generation_jobs
            SET tokens_in=$2, tokens_out=$3, usage_state=$4, updated_at=now() WHERE id=$1 AND state='running'`,
          [runId, usage.tokensIn, usage.tokensOut, usage.state]);
          if (updated.rowCount !== 1) throw new Error('generation_run_checkpoint_failed');
        } }, { ...options, weekIndex: weekId, signal: options.signal });
      } catch (error) {
        const unknown = options.signal?.aborted || (error instanceof Error && error.message === 'llm_outcome_unknown');
        await closeRun(client, runId, unknown ? 'unknown' : 'failed',
          unknown ? 'llm_outcome_unknown' : 'generation_failed',
          [unknown ? 'llm_outcome_unknown' : 'generation_failed']);
        terminal = unknown ? 'unknown' : 'failed';
        if (unknown) return { status: 'unknown', languageID: options.languageID, level: options.level, weekId: weekId!, errors: ['llm_outcome_unknown'] };
        return { status: 'failed', languageID: options.languageID, level: options.level, weekId: weekId!, errors: ['generation_failed'] };
      }
      const unknown = options.signal?.aborted || (outcome.status === 'failed' && outcome.errors?.some(code => code.includes('llm_outcome_unknown')));
      const state = unknown ? 'unknown' : outcome.status === 'failed' ? 'failed' : 'finished';
      const errorCode = unknown ? 'llm_outcome_unknown' : outcome.status === 'failed' ? safeCode(outcome.errors?.[0]) : null;
      // Keep the section-level reasons for the operator-facing error dialog; the
      // coarse error_code stays the stable contract for badges and summaries.
      const details = state === 'finished' ? [] : sanitizeGenerationErrorDetails(outcome.errors, unknown ? 'llm_outcome_unknown' : errorCode);
      await closeRun(client, runId, state, errorCode, details, outcome.usage);
      terminal = unknown ? 'unknown' : outcome.status;
      if (unknown) return { ...outcome, status: 'unknown', errors: ['llm_outcome_unknown'] };
      return outcome.status === 'failed' ? { ...outcome, errors: [errorCode ?? 'generation_failed'] } : outcome;
    } finally {
      try {
        span?.setAttribute('generation.outcome', terminal);
        span?.setAttribute('generation.duration_ms', Math.round(performance.now() - started));
      } catch { /* Best-effort tracing. */ }
      try { span?.end(); } catch { /* Best-effort tracing. */ }
    }
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

async function startRun(client: PoolClient, options: GenerateOptions, weekId: number, attempt: number,
  runCorrelationId?: string): Promise<string> {
  const id = runCorrelationId ?? randomUUID();
  if (!/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(id))
    throw new Error('invalid_generation_run_id');
  await client.query(`INSERT INTO level_test_generation_jobs(id, language_id, level, week_index, state, attempts, started_at, updated_at)
    VALUES($1,$2,$3,$4,'running',$5,now(),now())`,
  [id, options.languageID, options.level, weekId, attempt]);
  return id;
}

async function closeRun(client: PoolClient, id: string, state: 'finished' | 'failed' | 'unknown',
  errorCode: string | null, errorDetails: string[], usage?: GenerationUsage): Promise<void> {
  await client.query(`UPDATE level_test_generation_jobs
    SET state=$2, error_code=$3, error_details=$4::jsonb, finished_at=now(), updated_at=now(),
      tokens_in=CASE WHEN $5::boolean THEN $6::bigint ELSE tokens_in END,
      tokens_out=CASE WHEN $5::boolean THEN $7::bigint ELSE tokens_out END,
      usage_state=CASE WHEN $5::boolean THEN $8::text ELSE usage_state END WHERE id=$1`,
  [id, state, errorCode, JSON.stringify(errorDetails), usage !== undefined,
    usage?.tokensIn ?? null, usage?.tokensOut ?? null, usage?.state ?? null]);
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
