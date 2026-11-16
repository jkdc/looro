import { createHash, randomUUID } from 'node:crypto';
import type { PoolClient } from 'pg';
import type { Database } from './db.js';
import { resolveDailyLanguageProfile } from './daily-language-profiles.js';
import type { GenerationUsage } from './level-test-generation-usage.js';

export type DailyConfig = { readEnabled: boolean; newAttemptsEnabled: boolean; generationEnabled: boolean;
  autoPublishEnabled: boolean; enabledLanguages: string[]; contentBudgetNanoUsd: bigint; reviewBudgetNanoUsd: bigint; retentionDays: number };
export type DailyJob = { id: string; kind: 'content' | 'review'; binding_id: string; language: string;
  editorial_date: string | null; attempt_id: string | null; state: string; lease_owner: string | null; lease_until: Date | null;
  config_snapshot: Record<string, unknown> };

/** Persist provider token usage for pre-release analysis; it never affects admission or learner quotas. */
export async function recordDailyProviderUsage(tx: PoolClient, operationId: string, value: GenerationUsage): Promise<void> {
  const validUUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
  const validCount = (count: unknown): count is number | null => count === null ||
    (typeof count === 'number' && Number.isSafeInteger(count) && count >= 0);
  if (!validUUID.test(operationId) || !value || !['complete','partial','unknown'].includes(value.state) ||
      !validCount(value.tokensIn) || !validCount(value.tokensOut) ||
      (value.state === 'complete' && (value.tokensIn === null || value.tokensOut === null)) ||
      (value.state === 'partial' && value.tokensIn === null && value.tokensOut === null) ||
      (value.state === 'unknown' && (value.tokensIn !== null || value.tokensOut !== null)) ||
      Object.keys(value as unknown as Record<string, unknown>).some(key => !['state','tokensIn','tokensOut'].includes(key)))
    throw new Error('invalid_daily_usage');
  const result = await tx.query(`UPDATE daily_provider_operations SET usage=$2::jsonb,updated_at=now()
    WHERE id=$1 AND state IN ('sending','succeeded','failed','unknown')`,
  [operationId, JSON.stringify({ state: value.state, tokensIn: value.tokensIn, tokensOut: value.tokensOut })]);
  if (result.rowCount !== 1) throw new Error('daily_provider_operation_not_writable');
}

const bool = (env: NodeJS.ProcessEnv, key: string): boolean => {
  const value = env[key] ?? 'false';
  if (value !== 'true' && value !== 'false') throw new Error(`invalid_${key}`);
  return value === 'true';
};
const bounded = (value: string | undefined, fallback: number, min: number, max: number, key: string): number => {
  if (value === undefined) return fallback;
  if (!/^(0|[1-9]\d*)$/.test(value)) throw new Error(`invalid_${key}`);
  const n = Number(value);
  if (!Number.isSafeInteger(n) || n < min || n > max) throw new Error(`invalid_${key}`);
  return n;
};
export function parseDailyConfig(env: NodeJS.ProcessEnv): DailyConfig {
  const languages = (env.DAILY_ENABLED_LANGUAGES ?? 'en').split(',').map(x => x.trim());
  if (!languages.length || new Set(languages).size !== languages.length || languages.some(x => x !== 'en'))
    throw new Error('invalid_DAILY_ENABLED_LANGUAGES');
  return { readEnabled: bool(env,'DAILY_READ_ENABLED'), newAttemptsEnabled: bool(env,'DAILY_NEW_ATTEMPTS_ENABLED'),
    generationEnabled: bool(env,'DAILY_GENERATION_ENABLED'), autoPublishEnabled: bool(env,'DAILY_AUTO_PUBLISH_ENABLED'),
    enabledLanguages: languages, contentBudgetNanoUsd: BigInt(bounded(env.DAILY_CONTENT_BUDGET_NANO_USD,0,0,Number.MAX_SAFE_INTEGER,'DAILY_CONTENT_BUDGET_NANO_USD')),
    reviewBudgetNanoUsd: BigInt(bounded(env.DAILY_REVIEW_BUDGET_NANO_USD,0,0,Number.MAX_SAFE_INTEGER,'DAILY_REVIEW_BUDGET_NANO_USD')),
    retentionDays: bounded(env.DAILY_RETENTION_DAYS,365,30,3650,'DAILY_RETENTION_DAYS') };
}

export async function claimDailyJob(tx: PoolClient, workerId: string, leaseMs: number,
  now: () => Date = () => new Date(), kind?: 'content'|'review'): Promise<DailyJob | null> {
  if (!/^[a-zA-Z0-9_-]{1,64}$/.test(workerId) || !Number.isSafeInteger(leaseMs) || leaseMs < 1_000 || leaseMs > 300_000 ||
      (kind !== undefined && kind !== 'content' && kind !== 'review'))
    throw new Error('invalid_daily_lease');
  const instant = now();
  const result = await tx.query<DailyJob>(`WITH candidate AS (
      SELECT id FROM daily_jobs WHERE state='queued' AND ($4::text IS NULL OR kind=$4)
      ORDER BY created_at,id FOR UPDATE SKIP LOCKED LIMIT 1
    ) UPDATE daily_jobs j SET state='running',lease_owner=$1,lease_until=$2::timestamptz + ($3::bigint * interval '1 millisecond'),
    revision=j.revision+1,updated_at=$2 FROM candidate c WHERE j.id=c.id RETURNING j.*`, [workerId, instant, leaseMs, kind ?? null]);
  return result.rows[0] ?? null;
}

/** Extends a live lease only for its current owner; an expired lease cannot be resurrected. */
export async function renewDailyJobLease(tx: PoolClient, jobId: string, workerId: string, now: Date, leaseMs: number): Promise<void> {
  if (!dailyUUID.test(jobId) || !/^[a-zA-Z0-9_-]{1,64}$/.test(workerId) || !Number.isFinite(now.getTime()) ||
      !Number.isSafeInteger(leaseMs) || leaseMs < 1_000 || leaseMs > 300_000) throw new Error('invalid_daily_lease');
  const result = await tx.query(`UPDATE daily_jobs SET lease_until=$3::timestamptz+($4::bigint*interval '1 millisecond'),
    revision=revision+1,updated_at=$3 WHERE id=$1 AND state='running' AND lease_owner=$2 AND lease_until>$3`,
  [jobId, workerId, now, leaseMs]);
  if (result.rowCount !== 1) throw new Error('daily_job_lease_lost');
}

const dailyProviderPhases = new Set(['generate','judge','tts','audio_check','review']);
const dailyUUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
export type DailyProviderOperationBinding = { jobId:string; workerId:string; phase:'generate'|'judge'|'tts'|'audio_check'|'review';
  dispatchKey:string; reservationId:string; now:Date };

/** Creates the durable idempotency record before any provider request can be sent. */
export async function reserveDailyProviderOperation(tx:PoolClient,binding:DailyProviderOperationBinding):Promise<{id:string;reused:boolean}> {
  if(!dailyUUID.test(binding.jobId)||!dailyUUID.test(binding.reservationId)||! /^[a-zA-Z0-9_-]{1,64}$/.test(binding.workerId)||
    !dailyProviderPhases.has(binding.phase)||! /^[A-Za-z0-9:_-]{1,200}$/.test(binding.dispatchKey)||!Number.isFinite(binding.now.getTime()))
    throw new Error('invalid_daily_operation');
  const job=(await tx.query<{state:string;lease_owner:string|null;lease_until:Date|null}>(`SELECT state,lease_owner,lease_until FROM daily_jobs
    WHERE id=$1 FOR UPDATE`,[binding.jobId])).rows[0];
  if(!job||job.state!=='running'||job.lease_owner!==binding.workerId||!job.lease_until||job.lease_until<=binding.now)
    throw new Error('daily_job_lease_lost');
  const prior=(await tx.query<{id:string;job_id:string;phase:string;reservation_id:string|null;state:string}>(`SELECT id,job_id,phase,reservation_id,state
    FROM daily_provider_operations WHERE dispatch_key=$1 FOR UPDATE`,[binding.dispatchKey])).rows[0];
  if(prior){
    if(prior.job_id!==binding.jobId||prior.phase!==binding.phase||prior.reservation_id!==binding.reservationId)
      throw new Error('daily_dispatch_key_conflict');
    if(prior.state!=='reserved')throw new Error('daily_operation_not_dispatchable');
    return {id:prior.id,reused:true};
  }
  const id=randomUUID();
  await tx.query(`INSERT INTO daily_provider_operations(id,job_id,phase,dispatch_key,state,reservation_id,updated_at)
    VALUES($1,$2,$3,$4,'reserved',$5,$6)`,[id,binding.jobId,binding.phase,binding.dispatchKey,binding.reservationId,binding.now]);
  return {id,reused:false};
}

/** Fences provider dispatch to the currently active job lease and reserved operation. */
export async function startDailyProviderOperation(tx:PoolClient,operationId:string,workerId:string,now:Date):Promise<void> {
  if(!dailyUUID.test(operationId)||! /^[a-zA-Z0-9_-]{1,64}$/.test(workerId)||!Number.isFinite(now.getTime()))
    throw new Error('invalid_daily_operation');
  const operation=(await tx.query<{job_id:string}>('SELECT job_id FROM daily_provider_operations WHERE id=$1',[operationId])).rows[0];
  if(!operation)throw new Error('daily_operation_not_dispatchable');
  const job=(await tx.query('SELECT id FROM daily_jobs WHERE id=$1 FOR UPDATE',[operation.job_id])).rows[0];
  if(!job)throw new Error('daily_operation_not_dispatchable');
  const result=await tx.query(`UPDATE daily_provider_operations o SET state='sending',sent_at=$3,updated_at=$3
    FROM daily_jobs j WHERE o.id=$1 AND o.job_id=j.id AND o.state='reserved' AND o.reservation_id IS NOT NULL
      AND j.state='running' AND j.lease_owner=$2 AND j.lease_until>$3`,[operationId,workerId,now]);
  if(result.rowCount!==1)throw new Error('daily_operation_not_dispatchable');
}

/** Closes only a live send owned by the still-current lease; uncertain results cannot be replayed. */
export async function finishDailyProviderOperation(tx:PoolClient,operationId:string,workerId:string,
  state:'succeeded'|'failed'|'unknown',now:Date,resultHash?:string):Promise<void> {
  if(!dailyUUID.test(operationId)||! /^[a-zA-Z0-9_-]{1,64}$/.test(workerId)||!['succeeded','failed','unknown'].includes(state)||
    !Number.isFinite(now.getTime())||(state==='succeeded'?!/^[0-9a-f]{64}$/.test(resultHash??''):resultHash!==undefined))
    throw new Error('invalid_daily_operation');
  const result=await tx.query(`UPDATE daily_provider_operations o SET state=$3,result_hash=$4,updated_at=$5
    FROM daily_jobs j WHERE o.id=$1 AND o.job_id=j.id AND o.state='sending'
      AND j.state='running' AND j.lease_owner=$2 AND j.lease_until>$5`,[operationId,workerId,state,resultHash??null,now]);
  if(result.rowCount!==1)throw new Error('daily_provider_operation_not_writable');
}

export async function recoverExpiredDailyJobs(tx: PoolClient, now: Date): Promise<{ requeued: number; unknown: number }> {
  if (!Number.isFinite(now.getTime())) throw new Error('invalid_daily_clock');
  await tx.query(`UPDATE daily_provider_operations o SET state='unknown',updated_at=$1
    FROM daily_jobs j WHERE o.job_id=j.id AND j.state='running' AND j.lease_until <= $1 AND o.state='sending'`, [now]);
  const updated = await tx.query<{ id:string;kind:'content'|'review';attempt_id:string|null;state:'queued'|'unknown'|'failed' }>(`UPDATE daily_jobs j SET
      state=CASE
        WHEN EXISTS (SELECT 1 FROM daily_provider_operations o WHERE o.job_id=j.id AND o.state IN ('unknown','succeeded')) THEN 'unknown'
        WHEN EXISTS (SELECT 1 FROM daily_provider_operations o WHERE o.job_id=j.id AND o.state='failed') THEN 'failed'
        ELSE 'queued' END,
      lease_owner=NULL,lease_until=NULL,revision=revision+1,updated_at=$1
    WHERE j.state='running' AND j.lease_until <= $1 RETURNING id,kind,attempt_id,state`, [now]);
  for(const job of updated.rows)if(job.kind==='review'&&job.attempt_id)
    await tx.query('UPDATE daily_attempts SET review_state=$2,updated_at=$3 WHERE id=$1 AND review_state IN (\'queued\',\'running\')',
      [job.attempt_id,job.state,now]);
  return { requeued: updated.rows.filter(x => x.state === 'queued').length, unknown: updated.rows.filter(x => x.state === 'unknown').length };
}

function bindingUUID(value: string): string {
  const bytes = createHash('sha256').update(value).digest().subarray(0,16);
  bytes[6] = (bytes[6]! & 0x0f) | 0x50; bytes[8] = (bytes[8]! & 0x3f) | 0x80;
  const hex = bytes.toString('hex'); return `${hex.slice(0,8)}-${hex.slice(8,12)}-${hex.slice(12,16)}-${hex.slice(16,20)}-${hex.slice(20)}`;
}
export async function ensureDailyPackage(db: Database, language: string, editorialDate: string): Promise<string> {
  const profile = resolveDailyLanguageProfile(language);
  if (!profile?.enabled) throw new Error('daily_language_not_enabled');
  const date = new Date(`${editorialDate}T00:00:00Z`);
  if (!/^\d{4}-\d{2}-\d{2}$/.test(editorialDate) || !Number.isFinite(date.getTime()) || date.toISOString().slice(0,10) !== editorialDate)
    throw new Error('invalid_editorial_date');
  const bindingId = bindingUUID(`daily-content:${language}:${editorialDate}`);
  const jobId = randomUUID();
  const config = (await db.query(`SELECT generation_enabled,auto_publish_enabled,enabled_languages,content_budget_nano_usd,review_budget_nano_usd
    FROM daily_config WHERE singleton=true`)).rows[0] as { generation_enabled: boolean; auto_publish_enabled: boolean; enabled_languages: string[]; content_budget_nano_usd: string; review_budget_nano_usd:string }|undefined;
  if (!config?.generation_enabled || !config.enabled_languages.includes(language)) return 'disabled';
  await db.query(`INSERT INTO daily_budget_limits(cost_center,budget_date,max_nano_usd)
    VALUES('content',$1,$2),('review',$1,$3) ON CONFLICT(cost_center,budget_date) DO NOTHING`,
  [editorialDate,config.content_budget_nano_usd,config.review_budget_nano_usd]);
  const result = await db.query(`INSERT INTO daily_jobs(id,kind,binding_id,language,editorial_date,state,config_snapshot)
    VALUES($1,'content',$2,$3,$4,'queued',$5::jsonb)
    ON CONFLICT (language,editorial_date) WHERE kind='content' DO NOTHING
    RETURNING id`, [jobId,bindingId,language,editorialDate,JSON.stringify({ schemaVersion:1,profileVersion:profile.profileVersion,
      autoPublishEnabled:config.auto_publish_enabled,contentBudgetNanoUsd:String(config.content_budget_nano_usd) })]);
  if (result.rows[0]) return jobId;
  const existing = await db.query<{id:string}>(`SELECT id FROM daily_jobs WHERE kind='content' AND language=$1 AND editorial_date=$2`, [language,editorialDate]);
  return existing.rows[0]?.id ?? 'already_completed';
}
