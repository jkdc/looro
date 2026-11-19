import { createHash, randomUUID } from 'node:crypto';
import type { PoolClient } from 'pg';
import { reserveDailyCost } from './daily-budget.js';
import { log } from './log.js';
import { runDailyContentQualityGate, publishDailyPackage } from './daily-generation.js';
import { transaction, type Database } from './db.js';
import { buildDailyContentGenerationMessages, buildDailyContentJudgeMessages, buildDailyReviewMessages } from './daily-prompts.js';
import { hashDailyArtifact } from './daily-content.js';
import { validateDailyReview } from './daily-review.js';
import { createDailyLlmProvider, type DailyLlmProvider } from './daily-llm.js';
import { extractJson, LlmTransportError } from './llm-config.js';
import { recordDailyProviderUsage, reserveDailyProviderOperation, startDailyProviderOperation, finishDailyProviderOperation,
  claimDailyJob, recoverExpiredDailyJobs, renewDailyJobLease, type DailyJob } from './daily-jobs.js';

export type DailyContentWorkerResult={jobId:string;state:'succeeded'|'failed'|'unknown'|'cancelled';packageVersionId:string|null;errorCode:string|null};
export type DailyContentWorkerDependencies={
  now?:()=>Date;
  createProvider?:(db:Database,env:NodeJS.ProcessEnv)=>Promise<DailyLlmProvider>;
};
export type DailyContentWorkerRunDependencies=DailyContentWorkerDependencies&{leaseMs?:number};
export type DailyReviewWorkerResult={jobId:string;state:'succeeded'|'failed'|'unknown';attemptId:string|null;errorCode:string|null};
type JobRow=DailyJob&{revision:number;lease_owner:string|null;lease_until:Date|null};
type PackageCandidate={publicPayload:unknown;privatePayload:unknown;contentHash:string;artifactHash:string;textHash:string};
const isRecord=(v:unknown):v is Record<string,any>=>!!v&&typeof v==='object'&&!Array.isArray(v);
const idPattern=/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const workerPattern=/^[a-zA-Z0-9_-]{1,64}$/;
const safeCode=(error:unknown,fallback:string)=>error instanceof Error&&/^[a-z0-9_]{1,80}$/.test(error.message)?error.message:fallback;
const contentHash=(value:string)=>createHash('sha256').update(value,'utf8').digest('hex');

export function normalizeDailyEditorialDate(value:string|Date):string{
  const date=value instanceof Date?value.toISOString().slice(0,10):value;
  if(!/^\d{4}-\d{2}-\d{2}$/.test(date)||!Number.isFinite(Date.parse(`${date}T00:00:00Z`))||
      new Date(`${date}T00:00:00Z`).toISOString().slice(0,10)!==date)throw new Error('invalid_daily_content_job');
  return date;
}

async function liveContentJob(tx:PoolClient,jobId:string,workerId:string,now:Date):Promise<JobRow>{
  const job=(await tx.query<JobRow>(`SELECT * FROM daily_jobs WHERE id=$1 AND kind='content' FOR UPDATE`,[jobId])).rows[0];
  if(!job||job.state!=='running'||job.lease_owner!==workerId||!job.lease_until||job.lease_until<=now)
    throw new Error('daily_job_lease_lost');
  return job;
}

async function closeDailyJob(tx:PoolClient,jobId:string,workerId:string,state:'succeeded'|'failed'|'unknown'|'cancelled',
  code:string|null,now:Date):Promise<void>{
  const updated=await tx.query(`UPDATE daily_jobs SET state=$3,error_code=$4,lease_owner=NULL,lease_until=NULL,
    revision=revision+1,updated_at=$5 WHERE id=$1 AND state='running' AND lease_owner=$2`,[jobId,workerId,state,code,now]);
  if(updated.rowCount!==1)throw new Error('daily_job_lease_lost');
}

async function startContentReservation(db:Database,job:DailyJob,workerId:string,now:Date):Promise<{packageVersionId:string;reservationId:string}|null>{
  if(!job.editorial_date||!idPattern.test(job.binding_id))throw new Error('invalid_daily_content_job');
  const editorialDate=normalizeDailyEditorialDate(job.editorial_date);
  return transaction(db,async tx=>{
    const live=await liveContentJob(tx,job.id,workerId,now);
    const settings=(await tx.query<{generation_enabled:boolean;enabled_languages:string[];budget_enforced:boolean}>(
      'SELECT generation_enabled,enabled_languages,budget_enforced FROM daily_config WHERE singleton=true FOR SHARE')).rows[0];
    if(!settings?.generation_enabled||!settings.enabled_languages.includes(live.language)){
      await closeDailyJob(tx,job.id,workerId,'cancelled','daily_generation_disabled',now);return null;
    }
    const snapshot=isRecord(live.config_snapshot)?live.config_snapshot:{};
    const budgetEnforced=settings.budget_enforced;
    if(budgetEnforced&&(typeof snapshot.contentBudgetNanoUsd!=='string'||!/^([1-9]\d*)$/.test(snapshot.contentBudgetNanoUsd)))
      throw new Error('daily_budget_unavailable');
    const reservationNano=BigInt(budgetEnforced?String(snapshot.contentBudgetNanoUsd):'1');
    const reservationId=await reserveDailyCost(tx,{bindingId:live.binding_id,jobId:live.id,budgetDate:normalizeDailyEditorialDate(live.editorial_date!)},
      'content',reservationNano,budgetEnforced);
    const profileVersion=typeof snapshot.profileVersion==='string'?snapshot.profileVersion:'en-v1';
    await tx.query(`INSERT INTO daily_packages(id,language,editorial_date,schema_version,profile_version,state)
      VALUES($1,$2,$3,1,$4,'draft') ON CONFLICT(id) DO NOTHING`,[live.binding_id,live.language,editorialDate,profileVersion]);
    const pkg=(await tx.query<{state:string;revision:number}>('SELECT state,revision FROM daily_packages WHERE id=$1 FOR UPDATE',[live.binding_id])).rows[0];
    if(!pkg||pkg.state!=='draft'||Number(pkg.revision)!==0)throw new Error('daily_package_not_dispatchable');
    return {packageVersionId:live.binding_id,reservationId};
  });
}

async function executeProviderPhase(db:Database,provider:DailyLlmProvider,job:DailyJob,workerId:string,phase:'generate'|'judge'|'review',
  reservationId:string,messages:Parameters<DailyLlmProvider['complete']>[0],now:()=>Date,onDispatch:()=>void):Promise<unknown>{
  const instant=now();
  const operation=await transaction(db,tx=>reserveDailyProviderOperation(tx,{jobId:job.id,workerId,phase,
    dispatchKey:`${job.id}:${phase}:v1`,reservationId,now:instant}));
  let started=false,providerReplied=false,closed=false;
  try{
    log.info('daily_provider_phase_dispatch',{phase:job.kind+':'+phase,jobId:job.id});
    const reply=await provider.complete(messages,undefined,async usage=>{
      const checkpointAt=now();
      await transaction(db,async tx=>{
        if(!started)await startDailyProviderOperation(tx,operation.id,workerId,checkpointAt);
        await recordDailyProviderUsage(tx,operation.id,usage);
      });
      if(!started){started=true;onDispatch();}
    });
    providerReplied=true;
    log.info('daily_provider_phase_completed',{phase:job.kind+':'+phase,jobId:job.id});
    await transaction(db,tx=>finishDailyProviderOperation(tx,operation.id,workerId,'succeeded',now(),contentHash(reply.content)));
    closed=true;
    return extractJson(reply.content);
  }catch(error){
    const detail=error instanceof Error?`${error.name}: ${error.message}`:String(error);
    log.error('daily_provider_phase_failed',{phase,jobId:job.id,detail});
    if(started&&!closed){
      const unknown=providerReplied||(error instanceof LlmTransportError&&error.message==='llm_outcome_unknown');
      try{await transaction(db,tx=>finishDailyProviderOperation(tx,operation.id,workerId,unknown?'unknown':'failed',now()));closed=true;}catch{/* Recovery marks a lost lease unknown. */}
    }
    throw error;
  }
}

async function withLeaseHeartbeat<T>(db:Database,job:DailyJob,workerId:string,leaseMs:number,now:()=>Date,run:()=>Promise<T>):Promise<T>{
  let heartbeat:Promise<void>|null=null;
  let heartbeatFailed=false;
  const timer=setInterval(()=>{
    if(heartbeat||heartbeatFailed)return;
    heartbeat=transaction(db,tx=>renewDailyJobLease(tx,job.id,workerId,now(),leaseMs))
      .catch(()=>{heartbeatFailed=true;}).finally(()=>{heartbeat=null;});
  },Math.max(250,Math.floor(leaseMs/3)));
  timer.unref?.();
  try{return await run();}
  finally{clearInterval(timer);if(heartbeat)await heartbeat;}
}

/** Processes one immutable review snapshot; unknown provider outcomes are terminal and never resent. */
export async function runDailyReviewJob(db:Database,env:NodeJS.ProcessEnv,job:DailyJob,workerId:string,
  dependencies:DailyContentWorkerDependencies={}):Promise<DailyReviewWorkerResult>{
  const now=dependencies.now??(()=>new Date());
  if(job.kind!=='review'||!job.attempt_id||!idPattern.test(job.id)||!idPattern.test(job.attempt_id)||!workerPattern.test(workerId))
    throw new Error('invalid_daily_review_job');
  let providerInvoked=false;
  let reservationId:string|null=null;
  try{
    const input=await transaction(db,async tx=>{
      const live=(await tx.query(`SELECT j.state,j.lease_owner,j.lease_until,a.review_state,a.review_reservation_id,a.input_hash,
        a.package_id,a.language,a.explanation_language,a.stage_state,ri.snapshot,ri.frozen_at,dp.public_payload
        FROM daily_jobs j JOIN daily_attempts a ON a.id=j.attempt_id JOIN accounts ac ON ac.id=a.account_id AND ac.deleted_at IS NULL JOIN daily_review_inputs ri ON ri.attempt_id=a.id
        JOIN daily_packages dp ON dp.id=a.package_id WHERE j.id=$1 AND j.kind='review' FOR UPDATE OF j,a`,[job.id])).rows[0] as any;
      if(!live||live.state!=='running'||live.lease_owner!==workerId||!live.lease_until||new Date(live.lease_until)<=now()||
        live.review_state!=='queued'||live.input_hash!==hashDailyArtifact(live.snapshot))throw new Error('daily_review_input_unavailable');
      reservationId=live.review_reservation_id;
      if(!reservationId)throw new Error('daily_review_reservation_unavailable');
      const updated=await tx.query("UPDATE daily_attempts SET review_state='running',updated_at=$2 WHERE id=$1 AND review_state='queued'",[job.attempt_id,now()]);
      if(updated.rowCount!==1)throw new Error('daily_review_not_dispatchable');
      return live;
    });
    const provider=await (dependencies.createProvider??createDailyLlmProvider)(db,env);
    const snapshot=input.snapshot.stageState;
    const binding={attemptId:job.attempt_id,packageVersionId:input.package_id,inputHash:input.input_hash,language:'en' as const,
      explanationLanguage:input.explanation_language as 'en'|'es',rubricVersion:'daily-review-v1',createdAt:new Date(input.frozen_at).getTime()};
    const raw=await executeProviderPhase(db,provider,job,workerId,'review',reservationId!,
      buildDailyReviewMessages({explanationLanguage:binding.explanationLanguage,packagePayload:input.public_payload,snapshot}),now,()=>{providerInvoked=true;});
    const result=validateDailyReview(raw,binding,snapshot);
    const state=result.outcome==='partial'?'partial':'ready';
    await transaction(db,async tx=>{
      const current=(await tx.query(`SELECT state,lease_owner,lease_until FROM daily_jobs WHERE id=$1 FOR UPDATE`,[job.id])).rows[0] as any;
      if(!current||current.state!=='running'||current.lease_owner!==workerId||!current.lease_until||new Date(current.lease_until)<=now())
        throw new Error('daily_job_lease_lost');
      await tx.query('INSERT INTO daily_reviews(id,attempt_id,input_hash,rubric_version,result) VALUES($1,$2,$3,$4,$5::jsonb)',
        [result.reviewId,job.attempt_id,binding.inputHash,binding.rubricVersion,JSON.stringify(result)]);
      await tx.query('UPDATE daily_attempts SET review_id=$2,review_state=$3,updated_at=$4 WHERE id=$1 AND review_state=\'running\'',
        [job.attempt_id,result.reviewId,state,now()]);
      await tx.query("UPDATE daily_cost_reservations SET state='unknown' WHERE id=$1 AND state='held'",[reservationId]);
      await closeDailyJob(tx,job.id,workerId,'succeeded',null,now());
    });
    return {jobId:job.id,state:'succeeded',attemptId:job.attempt_id,errorCode:null};
  }catch(error){
    const unknown=error instanceof LlmTransportError&&error.message==='llm_outcome_unknown';
    const code=safeCode(error,'daily_review_failed');
    try{await transaction(db,async tx=>{
      const current=(await tx.query(`SELECT state,lease_owner FROM daily_jobs WHERE id=$1 FOR UPDATE`,[job.id])).rows[0] as any;
      if(!current||current.state!=='running'||current.lease_owner!==workerId)return;
      await tx.query('UPDATE daily_attempts SET review_state=$2,updated_at=$3 WHERE id=$1 AND review_state IN (\'queued\',\'running\')',
        [job.attempt_id,unknown?'unknown':'failed',now()]);
      if(reservationId)await tx.query('UPDATE daily_cost_reservations SET state=$2 WHERE id=$1 AND state=\'held\'',
        [reservationId,providerInvoked||unknown?'unknown':'released']);
      await closeDailyJob(tx,job.id,workerId,unknown?'unknown':'failed',code,now());
    });}catch{/* Recovery marks a lost lease unknown and never resends it. */}
    return {jobId:job.id,state:unknown?'unknown':'failed',attemptId:job.attempt_id,errorCode:code};
  }
}

async function saveCandidate(tx:PoolClient,packageVersionId:string,candidate:PackageCandidate):Promise<void>{
  const result=await tx.query(`UPDATE daily_packages SET state='validating',revision=revision+1,public_payload=$2::jsonb,
    private_payload=$3::jsonb,content_hash=$4,artifact_hash=$5,text_hash=$6,judge_state='pending',judge_artifact_hash=$5,
    judge_text_hash=$6 WHERE id=$1 AND state='draft' AND revision=0`,[packageVersionId,JSON.stringify(candidate.publicPayload),
    JSON.stringify(candidate.privatePayload),candidate.contentHash,candidate.artifactHash,candidate.textHash]);
  if(result.rowCount!==1)throw new Error('daily_package_revision_conflict');
}

/** Processes one already-claimed content job; every provider phase has a reserved, fenced, non-replayable operation. */
export async function runDailyContentJob(db:Database,env:NodeJS.ProcessEnv,job:DailyJob,workerId:string,
  dependencies:DailyContentWorkerDependencies={}):Promise<DailyContentWorkerResult>{
  const now=dependencies.now??(()=>new Date());
  if(job.kind!=='content'||!idPattern.test(job.id)||!workerPattern.test(workerId))throw new Error('invalid_daily_content_job');
  let reservation:{packageVersionId:string;reservationId:string}|null=null;
  let providerInvoked=false;
  try{
    reservation=await startContentReservation(db,job,workerId,now());
    if(!reservation)return {jobId:job.id,state:'cancelled',packageVersionId:null,errorCode:'daily_generation_disabled'};
    const provider=await (dependencies.createProvider??createDailyLlmProvider)(db,env);
    const editorialDate=normalizeDailyEditorialDate(job.editorial_date!);
    let revision=0;
    const gate=await runDailyContentQualityGate({language:job.language,editorialDate,packageVersionId:reservation.packageVersionId,
      generate:async()=>{
        const generated=await executeProviderPhase(db,provider,job,workerId,'generate',reservation!.reservationId,
          buildDailyContentGenerationMessages(job.language,editorialDate),now,()=>{providerInvoked=true;});
        return generated;
      },
      judge:async input=>{
        await transaction(db,async tx=>{await liveContentJob(tx,job.id,workerId,now());
          await saveCandidate(tx,reservation!.packageVersionId,input);revision=1;});
        const judged=await executeProviderPhase(db,provider,job,workerId,'judge',reservation!.reservationId,
          buildDailyContentJudgeMessages(input),now,()=>{providerInvoked=true;});
        return judged as {decision:'pass'|'fail'|'uncertain';evidence:unknown[]};
      }});
    const state=gate.state==='ready'?'ready':'rejected';
    const finalRevision=await transaction(db,async tx=>{
      await liveContentJob(tx,job.id,workerId,now());
      const updated=await tx.query<{revision:number}>(`UPDATE daily_packages SET state=$2,revision=revision+1,public_payload=$3::jsonb,
        private_payload=$4::jsonb,content_hash=$5,artifact_hash=$6,text_hash=$7,judge_state=$8,judge_artifact_hash=$6,judge_text_hash=$7
        WHERE id=$1 AND state='validating' AND revision=$9 RETURNING revision`,[reservation!.packageVersionId,state,
        JSON.stringify(gate.publicPayload),JSON.stringify(gate.privatePayload),gate.contentHash,gate.artifactHash,gate.textHash,gate.judgeState,revision]);
      if(updated.rowCount!==1)throw new Error('daily_package_revision_conflict');
      const nextRevision=Number(updated.rows[0]!.revision);
      const snapshot=isRecord(job.config_snapshot)?job.config_snapshot:{};
      if(state==='ready'&&snapshot.autoPublishEnabled===true)
        await publishDailyPackage(tx,reservation!.packageVersionId,gate.artifactHash,nextRevision);
      if(providerInvoked)await tx.query("UPDATE daily_cost_reservations SET state='unknown' WHERE id=$1 AND state='held'",[reservation!.reservationId]);
      else await tx.query("UPDATE daily_cost_reservations SET state='released' WHERE id=$1 AND state='held'",[reservation!.reservationId]);
      await closeDailyJob(tx,job.id,workerId,state==='ready'?'succeeded':'failed',state==='ready'?null:
        gate.judgeState==='failed'?'daily_judge_failed':'daily_judge_uncertain',now());
      return nextRevision;
    });
    void finalRevision;
    return {jobId:job.id,state:state==='ready'?'succeeded':'failed',packageVersionId:reservation.packageVersionId,
      errorCode:state==='ready'?null:gate.judgeState==='failed'?'daily_judge_failed':'daily_judge_uncertain'};
  }catch(error){
    const unknown=error instanceof LlmTransportError&&error.message==='llm_outcome_unknown';
    const detail=error instanceof Error?`${error.name}: ${error.message}`:String(error);
    log.error('daily_content_generation_failed',{detail,kind:error instanceof Error?error.name:typeof error});
    const code=unknown?'llm_outcome_unknown':safeCode(error,'daily_generation_failed');
    try{await transaction(db,async tx=>{
      await liveContentJob(tx,job.id,workerId,now());
      if(reservation){
        await tx.query(`UPDATE daily_packages SET state='draft',judge_state=CASE WHEN state='validating' THEN 'unknown' ELSE judge_state END
          WHERE id=$1 AND state IN ('draft','validating')`,[reservation.packageVersionId]);
        await tx.query(`UPDATE daily_cost_reservations SET state=$2 WHERE id=$1 AND state='held'`,[reservation.reservationId,
          providerInvoked||unknown?'unknown':'released']);
      }
      await closeDailyJob(tx,job.id,workerId,unknown?'unknown':'failed',code,now());
    });}catch{/* A lost lease is reconciled by recoverExpiredDailyJobs; never resend this dispatch. */}
    return {jobId:job.id,state:unknown?'unknown':'failed',packageVersionId:reservation?.packageVersionId??null,errorCode:code};
  }
}

/** Recovers expired work and claims at most one content job using PostgreSQL SKIP LOCKED. */
export async function runDailyContentWorkerOnce(db:Database,env:NodeJS.ProcessEnv,workerId:string,
  dependencies:DailyContentWorkerRunDependencies={}):Promise<DailyContentWorkerResult|null>{
  const now=dependencies.now??(()=>new Date());
  const leaseMs=dependencies.leaseMs??300_000;
  const instant=now();
  await transaction(db,tx=>recoverExpiredDailyJobs(tx,instant));
  const job=await transaction(db,tx=>claimDailyJob(tx,workerId,leaseMs,()=>instant,'content'));
  if(!job)return null;
  return withLeaseHeartbeat(db,job,workerId,leaseMs,now,()=>runDailyContentJob(db,env,job,workerId,dependencies));
}

/** Recovers expired work and claims at most one review job using PostgreSQL SKIP LOCKED. */
export async function runDailyReviewWorkerOnce(db:Database,env:NodeJS.ProcessEnv,workerId:string,
  dependencies:DailyContentWorkerRunDependencies={}):Promise<DailyReviewWorkerResult|null>{
  const now=dependencies.now??(()=>new Date());
  const leaseMs=dependencies.leaseMs??300_000;
  const instant=now();
  await transaction(db,tx=>recoverExpiredDailyJobs(tx,instant));
  const job=await transaction(db,tx=>claimDailyJob(tx,workerId,leaseMs,()=>instant,'review'));
  if(!job)return null;
  return withLeaseHeartbeat(db,job,workerId,leaseMs,now,()=>runDailyReviewJob(db,env,job,workerId,dependencies));
}
