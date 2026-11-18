import { createHash } from 'node:crypto';
import { randomUUID } from 'node:crypto';
import { transaction, type Database } from './db.js';
import { ServiceError } from './errors.js';
import { hashDailyArtifact, gradeDailyClosedAnswers, projectPublicDailyPackage } from './daily-content.js';
import { reserveDailyCost } from './daily-budget.js';

export type DailyStage='preparation'|'listening'|'speaking'|'practice'|'finished';
export type DailyCompletedStage={stage:Exclude<DailyStage,'finished'>;status:'completed'|'skipped';activeMs:number};
export type DailyFragment={id:string;role:'learner'|'tutor';text:string;voiceSegmentId:string;sequence:number;transcriptionUncertain:boolean};
export type DailyWordMark={markId:string;text:string;meaning:string;kind:'word'|'expression';sourceQuestionId:string|null};
export type DailyAttemptState={stage:DailyStage;revision:number;activeMs:number;completedStages:DailyCompletedStage[];
  speakingActiveMs?:number;
  fragments:DailyFragment[];wordMarks:DailyWordMark[];speakingClosed:boolean;finished:boolean;answers?:Record<string,unknown>;
  helpUsed?:Record<string,string[]>;sectionResults?:Record<string,unknown[]>;stopReason?:string|null};
export type DailyEventEnvelope={schemaVersion:1;eventId:string;expectedRevision:number;event:Record<string,any>};
const uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const id=/^[a-z0-9_-]{1,64}$/;
const record=(v:unknown):v is Record<string,any>=>!!v&&typeof v==='object'&&!Array.isArray(v);
function exact(value:unknown,keys:string[]):value is Record<string,any>{return record(value)&&Object.keys(value).length===keys.length&&Object.keys(value).every(k=>keys.includes(k));}
const text=(value:unknown,max:number)=>typeof value==='string'&&[...value].length<=max&&!/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/u.test(value)&&!/[\uD800-\uDFFF]/u.test(value);

export function validateDailyEvent(value:unknown):DailyEventEnvelope {
  if(!exact(value,['schemaVersion','eventId','expectedRevision','event'])||value.schemaVersion!==1||!uuid.test(value.eventId)||
    !Number.isSafeInteger(value.expectedRevision)||value.expectedRevision<0||!record(value.event))throw new ServiceError('invalid_daily_event');
  if(Buffer.byteLength(JSON.stringify(value),'utf8')>24*1024)throw new ServiceError('daily_payload_too_large',413);
  const e=value.event;
  switch(e.type){
    case 'preparation_completed':
      if(!record(e)||Object.keys(e).some(key=>!['type','activeMs','helpUsed'].includes(key))||
        Object.keys(e).length<2||Object.keys(e).length>3||e.type!=='preparation_completed'||!Number.isSafeInteger(e.activeMs)||e.activeMs<0||
        (e.helpUsed!==undefined&&(!Array.isArray(e.helpUsed)||e.helpUsed.length>8||e.helpUsed.some((x:unknown)=>!id.test(String(x))))))
        throw new ServiceError('invalid_daily_event');break;
    case 'stage_submitted':
      if(!exact(e,['type','stage','activeMs','answers','helpUsed'])||!['listening','practice'].includes(e.stage)||!Number.isSafeInteger(e.activeMs)||e.activeMs<0||
        !Array.isArray(e.answers)||!Array.isArray(e.helpUsed)||e.answers.length>4||e.helpUsed.length>8)throw new ServiceError('invalid_daily_event');
      {const seen=new Set<string>();for(const answer of e.answers){if(!exact(answer,['questionId','value'])||!id.test(answer.questionId)||seen.has(answer.questionId)||
        !(answer.value===null||text(answer.value,120)))throw new ServiceError('invalid_daily_event');seen.add(answer.questionId);}
       if(e.helpUsed.some((x:unknown)=>!id.test(String(x)))||new Set(e.helpUsed).size!==e.helpUsed.length)throw new ServiceError('invalid_daily_event');}break;
    case 'speaking_segment_saved':
      if(!exact(e,['type','segmentId','activeMs','fragments','helpUsed'])||!uuid.test(e.segmentId)||!Number.isSafeInteger(e.activeMs)||e.activeMs<0||
        !Array.isArray(e.fragments)||e.fragments.length>20||!Array.isArray(e.helpUsed)||e.helpUsed.some((x:unknown)=>!id.test(String(x))))throw new ServiceError('invalid_daily_event');
      {const seen=new Set<string>();let previous=-1;for(const f of e.fragments){if(!exact(f,['id','role','text','voiceSegmentId','sequence','transcriptionUncertain'])||!uuid.test(f.id)||seen.has(f.id)||
        !['learner','tutor'].includes(f.role)||!text(f.text,1000)||f.voiceSegmentId!==e.segmentId||!Number.isSafeInteger(f.sequence)||f.sequence<=previous||typeof f.transcriptionUncertain!=='boolean')throw new ServiceError('invalid_daily_event');
        seen.add(f.id);previous=f.sequence;}}break;
    case 'stage_skipped':
      if(!exact(e,['type','stage','activeMs','reason'])||!['preparation','listening','speaking','practice'].includes(e.stage)||!Number.isSafeInteger(e.activeMs)||e.activeMs<0||
        !['learner_choice','microphone_denied','network_error','funding_unavailable','local_tts_unavailable'].includes(e.reason))throw new ServiceError('invalid_daily_event');break;
    case 'word_marked':
      if(!exact(e,['type','markId','text','meaning','kind','sourceQuestionId'])||!uuid.test(e.markId)||!text(e.text,120)||!text(e.meaning,300)||
        !['word','expression'].includes(e.kind)||!(e.sourceQuestionId===null||id.test(e.sourceQuestionId)))throw new ServiceError('invalid_daily_event');break;
    case 'speaking_closed': if(!exact(e,['type']))throw new ServiceError('invalid_daily_event');break;
    case 'finished': if(!exact(e,['type','stopReason'])||!['learner_choice','natural_close'].includes(e.stopReason))throw new ServiceError('invalid_daily_event');break;
    default: throw new ServiceError('invalid_daily_event');
  }
  return value as DailyEventEnvelope;
}

function complete(state:DailyAttemptState,stage:Exclude<DailyStage,'finished'>,status:'completed'|'skipped',activeMs=0):DailyAttemptState {
  const existing=state.completedStages.find(x=>x.stage===stage);
  if(existing)throw new ServiceError('invalid_stage_transition',409);
  return {...state,activeMs:state.activeMs+activeMs,completedStages:[...state.completedStages,{stage,status,activeMs}]};
}
export function applyDailyEvent(state:DailyAttemptState,event:Record<string,any>):DailyAttemptState {
  if(state.finished)throw new ServiceError('daily_attempt_finished',409);
  switch(event.type){
    case 'preparation_completed':
      if(state.stage!=='preparation')throw new ServiceError('invalid_stage_transition',409);
      return {...complete(state,'preparation','completed',event.activeMs),stage:'listening',
        helpUsed:{...(state.helpUsed??{}),speaking:event.helpUsed??[]}};
    case 'stage_submitted': {
      if(event.stage!==state.stage||!['listening','practice'].includes(event.stage))throw new ServiceError('invalid_stage_transition',409);
      const updated=complete(state,event.stage,'completed',event.activeMs);
      return {...updated,stage:event.stage==='listening'?'speaking':'practice',answers:{...(state.answers??{}),[event.stage]:event.answers}};
    }
    case 'speaking_segment_saved':
      if(state.stage!=='speaking'||state.speakingClosed)throw new ServiceError('invalid_stage_transition',409);
      if(state.fragments.some(f=>event.fragments.some((n:DailyFragment)=>n.id===f.id)))throw new ServiceError('duplicate_daily_fragment',409);
      return {...state,activeMs:state.activeMs+event.activeMs,speakingActiveMs:(state.speakingActiveMs??0)+event.activeMs,fragments:[...state.fragments,...event.fragments],
        helpUsed:{...(state.helpUsed??{}),speaking:[...new Set([...(state.helpUsed?.speaking??[]),...event.helpUsed])]}};
    case 'speaking_closed':
      if(state.stage!=='speaking'||state.speakingClosed)throw new ServiceError('invalid_stage_transition',409);
      {const updated=complete(state,'speaking','completed');
       return {...updated,completedStages:updated.completedStages.map(stage=>stage.stage==='speaking'?{...stage,activeMs:state.speakingActiveMs??0}:stage),
         stage:'practice',speakingClosed:true,speakingActiveMs:0};}
    case 'stage_skipped': {
      if(event.stage!==state.stage||event.stage==='finished')throw new ServiceError('invalid_stage_transition',409);
      const updated=complete(state,event.stage,'skipped',event.activeMs);
      const next:DailyStage=event.stage==='preparation'?'listening':event.stage==='listening'?'speaking':event.stage==='speaking'?'practice':'practice';
      return {...updated,stage:next,speakingClosed:state.speakingClosed||event.stage==='speaking'};
    }
    case 'word_marked':
      if(state.wordMarks.length>=12||state.wordMarks.some(mark=>mark.markId===event.markId))throw new ServiceError('daily_word_mark_limit',409);
      return {...state,wordMarks:[...state.wordMarks,event as DailyWordMark]};
    case 'finished': {
      const byStage=new Map(state.completedStages.map(x=>[x.stage,x]));
      if(!state.speakingClosed||!['preparation','listening','speaking','practice'].every(x=>byStage.has(x as Exclude<DailyStage,'finished'>)))
        throw new ServiceError('invalid_stage_transition',409);
      return {...state,stage:'finished',finished:true};
    }
    default:throw new ServiceError('invalid_daily_event');
  }
}

export function hashDailyEventIntent(event:Record<string,unknown>):string {
  return createHash('sha256').update(JSON.stringify(event),'utf8').digest('hex');
}

const cursorOwnerHash=(accountId:string)=>createHash('sha256').update(accountId,'utf8').digest('hex');
export function encodeDailyAttemptCursor(accountId:string,language:string,startedAt:string,attemptId:string):string {
  if(!uuid.test(accountId)||!['en'].includes(language)||new Date(startedAt).toISOString()!==startedAt||!uuid.test(attemptId))
    throw new ServiceError('invalid_daily_cursor');
  return Buffer.from(JSON.stringify({owner:cursorOwnerHash(accountId),language,startedAt,attemptId}),'utf8').toString('base64url');
}
export function parseDailyAttemptCursor(cursor:string,accountId:string,language:string):{startedAt:string;attemptId:string} {
  try{
    if(!/^[A-Za-z0-9_-]{1,512}$/.test(cursor))throw new Error();
    const raw=Buffer.from(cursor,'base64url').toString('utf8');
    if(Buffer.from(raw,'utf8').toString('base64url')!==cursor)throw new Error();
    const value=JSON.parse(raw);
    if(!value||Object.keys(value).length!==4||value.owner!==cursorOwnerHash(accountId)||value.language!==language||
      typeof value.startedAt!=='string'||new Date(value.startedAt).toISOString()!==value.startedAt||!uuid.test(value.attemptId))throw new Error();
    return {startedAt:value.startedAt,attemptId:value.attemptId};
  }catch{throw new ServiceError('invalid_daily_cursor');}
}

export function hasDailyReviewEvidence(state:DailyAttemptState):boolean {
  if(state.fragments.some(fragment=>fragment.role==='learner'&&fragment.text.trim().length>0))return true;
  return Object.values(state.sectionResults??{}).some(items=>(items as {status:string}[]).some(item=>item.status==='correct'||item.status==='incorrect'));
}

export type DailyAttemptView={schemaVersion:1;attemptId:string;attemptNumber:number;packageVersionId:string;language:string;editorialDate:string;
  explanationLanguage:'en'|'es';revision:number;state:string;startedAt:number;completedStages:DailyCompletedStage[];activeMs:number;
  stopReason:string|null;reviewState:string;reviewId:string|null;inputHash:string|null;sectionResults?:unknown[]};
function attemptState(row:{stage_state:DailyAttemptState|string}):DailyAttemptState {
  return typeof row.stage_state==='string'?JSON.parse(row.stage_state) as DailyAttemptState:row.stage_state;
}
function attemptView(row:any):DailyAttemptView {
  const state=attemptState(row);
  return {schemaVersion:1,attemptId:row.id,attemptNumber:Number(row.attempt_number),packageVersionId:row.package_id,language:row.language,
    editorialDate:typeof row.editorial_date==='string'?row.editorial_date:row.editorial_date.toISOString().slice(0,10),
    explanationLanguage:row.explanation_language,revision:Number(row.revision),state:row.state,startedAt:new Date(row.started_at).getTime(),
    completedStages:state.completedStages,activeMs:Number(row.active_ms),stopReason:row.stop_reason,reviewState:row.review_state,
    reviewId:row.review_id,inputHash:row.input_hash,
    ...(state.sectionResults?{sectionResults:Object.entries(state.sectionResults).flatMap(([stage,items])=>items.map(item=>({stage,...item as object})))}:{})};
}
const MAX_REVIEW_RESERVATION_NANO=100_000_000n;
export type CreateDailyAttemptBody={schemaVersion:1;attemptId:string;packageVersionId:string;explanationLanguage:'en'|'es';dailyConsentVersion:1};
function parseCreateBody(value:unknown):CreateDailyAttemptBody {
  if(!exact(value,['schemaVersion','attemptId','packageVersionId','explanationLanguage','dailyConsentVersion'])||value.schemaVersion!==1||
    !uuid.test(value.attemptId)||!uuid.test(value.packageVersionId)||!['en','es'].includes(value.explanationLanguage)||value.dailyConsentVersion!==1)
    throw new ServiceError('invalid_daily_attempt');
  return value as CreateDailyAttemptBody;
}
export async function createDailyAttempt(db:Database,accountId:string,bodyValue:unknown,idempotencyKey:string,now=()=>new Date()):Promise<{status:200|201;attempt:DailyAttemptView}> {
  const body=parseCreateBody(bodyValue);
  if(!uuid.test(accountId)||!uuid.test(idempotencyKey))throw new ServiceError('invalid_daily_attempt');
  const requestHash=hashDailyArtifact(body);
  return transaction(db,async tx=>{
    const prior=(await tx.query<{request_hash:string;response:Record<string,unknown>;http_status:number}>(`SELECT request_hash,response,http_status
      FROM daily_attempt_request_keys WHERE account_id=$1 AND operation='create_attempt' AND idempotency_key=$2 FOR UPDATE`,[accountId,idempotencyKey])).rows[0];
    if(prior){if(prior.request_hash!==requestHash)throw new ServiceError('idempotency_conflict',409);
      return {status:prior.http_status===201?201:200,attempt:prior.response as unknown as DailyAttemptView};}
    const instant=now();if(!Number.isFinite(instant.getTime()))throw new ServiceError('invalid_daily_clock');
    const editorialDate=instant.toISOString().slice(0,10);
    await tx.query('SELECT pg_advisory_xact_lock(hashtextextended($1,0))',[`${accountId}:en:${editorialDate}`]);
    const config=(await tx.query<{new_attempts_enabled:boolean;max_attempts_per_day:number;review_budget_nano_usd:string;budget_enforced:boolean}>(
      'SELECT new_attempts_enabled,max_attempts_per_day,review_budget_nano_usd,budget_enforced FROM daily_config WHERE singleton=true FOR SHARE')).rows[0];
    if(!config?.new_attempts_enabled)throw new ServiceError('daily_unavailable',503);
    const publication=(await tx.query<{package_id:string;withdrawn_at:Date|null}>(`SELECT package_id,withdrawn_at FROM daily_publications
      WHERE language='en' AND editorial_date=$1 FOR UPDATE`,[editorialDate])).rows[0];
    if(!publication||publication.withdrawn_at)throw new ServiceError('daily_not_available',404);
    if(publication.package_id!==body.packageVersionId)throw new ServiceError('package_changed',409);
    const existing=(await tx.query(`SELECT * FROM daily_attempts WHERE account_id=$1 AND language='en' AND editorial_date=$2 ORDER BY attempt_number DESC LIMIT 1`,
      [accountId,editorialDate])).rows[0];
    if(existing&&Number(existing.attempt_number)>=config.max_attempts_per_day||existing&&existing.state==='in_progress'){const view=attemptView(existing);await tx.query(`INSERT INTO daily_attempt_request_keys(account_id,operation,idempotency_key,request_hash,http_status,response)
      VALUES($1,'create_attempt',$2,$3,200,$4::jsonb)`,[accountId,idempotencyKey,requestHash,JSON.stringify(view)]);return {status:200,attempt:view};}
    const attemptNumberResult=await tx.query<{n:number}>(`SELECT COALESCE(MAX(attempt_number),0)+1 AS n FROM daily_attempts
      WHERE account_id=$1 AND language='en' AND editorial_date=$2`,[accountId,editorialDate]);
    const attemptNumber=Number(attemptNumberResult.rows[0]?.n??1);
    if(attemptNumber>config.max_attempts_per_day)throw new ServiceError('daily_attempt_limit',409);
    const packageRow=(await tx.query<{public_payload:unknown}>(`SELECT p.public_payload FROM daily_packages p WHERE p.id=$1 AND p.state='published'`,[publication.package_id])).rows[0];
    if(!packageRow)throw new ServiceError('daily_not_available',404);
    const attemptId=body.attemptId,jobId=randomUUID();
    const initial:DailyAttemptState={stage:'preparation',revision:0,activeMs:0,completedStages:[],fragments:[],wordMarks:[],speakingClosed:false,finished:false};
    const inserted=await tx.query(`INSERT INTO daily_attempts(id,account_id,package_id,language,editorial_date,attempt_number,explanation_language,
      daily_consent_version,state,stage_state) VALUES($1,$2,$3,'en',$4,$5,$6,1,'in_progress',$7::jsonb) RETURNING *`,
    [attemptId,accountId,publication.package_id,editorialDate,attemptNumber,body.explanationLanguage,JSON.stringify(initial)]);
    await tx.query(`INSERT INTO daily_jobs(id,kind,binding_id,language,editorial_date,attempt_id,state,config_snapshot)
      VALUES($1,'review',$2,'en',$3,$2,'reserved',$4::jsonb)`,[jobId,attemptId,editorialDate,JSON.stringify({schemaVersion:1,rubricVersion:'daily-review-v1'})]);
    await tx.query(`INSERT INTO daily_budget_limits(cost_center,budget_date,max_nano_usd) VALUES('review',$1,$2) ON CONFLICT DO NOTHING`,
      [editorialDate,config.review_budget_nano_usd]);
    let reservationId:string;
    try{reservationId=await reserveDailyCost(tx,{bindingId:attemptId,jobId,budgetDate:editorialDate},'review',MAX_REVIEW_RESERVATION_NANO,config.budget_enforced);}
    catch(error){if(error instanceof Error&&error.message==='daily_budget_unavailable')throw new ServiceError('daily_budget_unavailable',503);throw error;}
    await tx.query('UPDATE daily_attempts SET review_reservation_id=$2 WHERE id=$1',[attemptId,reservationId]);
    await tx.query(`UPDATE daily_publications SET consumed_at=COALESCE(consumed_at,$3) WHERE language='en' AND editorial_date=$1 AND package_id=$2`,
      [editorialDate,publication.package_id,instant]);
    const view=attemptView(inserted.rows[0]);
    await tx.query(`INSERT INTO daily_attempt_request_keys(account_id,operation,idempotency_key,request_hash,http_status,response)
      VALUES($1,'create_attempt',$2,$3,201,$4::jsonb)`,[accountId,idempotencyKey,requestHash,JSON.stringify(view)]);
    return {status:201,attempt:view};
  });
}

export async function getDailyAttempt(db:Database,accountId:string,attemptId:string):Promise<DailyAttemptView|null> {
  if(!uuid.test(accountId)||!uuid.test(attemptId))return null;
  const row=(await db.query('SELECT * FROM daily_attempts WHERE account_id=$1 AND id=$2',[accountId,attemptId])).rows[0];
  return row?attemptView(row):null;
}

export async function getDailyAttemptPackage(db:Database,accountId:string,attemptId:string):Promise<Record<string,unknown>> {
  if(!uuid.test(accountId)||!uuid.test(attemptId))throw new ServiceError('not_found',404);
  const row=(await db.query<{public_payload:unknown;package_state:string;withdrawn_at:Date|null}>(`SELECT p.public_payload,p.state AS package_state,d.withdrawn_at
    FROM daily_attempts a JOIN daily_packages p ON p.id=a.package_id
    LEFT JOIN daily_publications d ON d.package_id=p.id AND d.language=a.language AND d.editorial_date=a.editorial_date
    WHERE a.account_id=$1 AND a.id=$2`,[accountId,attemptId])).rows[0];
  if(!row)throw new ServiceError('not_found',404);
  if(row.withdrawn_at||row.package_state==='withdrawn')throw new ServiceError('daily_content_withdrawn',410);
  return projectPublicDailyPackage(row.public_payload);
}

export async function getDailyReview(db:Database,accountId:string,attemptId:string):Promise<
  {status:200;review:Record<string,unknown>}|{status:202;state:'queued'|'running';retryAfterMs:5000}> {
  if(!uuid.test(accountId)||!uuid.test(attemptId))throw new ServiceError('not_found',404);
  const row=(await db.query<{review_state:string;result:Record<string,unknown>|null}>(`SELECT a.review_state,r.result
    FROM daily_attempts a LEFT JOIN daily_reviews r ON r.id=a.review_id WHERE a.account_id=$1 AND a.id=$2`,[accountId,attemptId])).rows[0];
  if(!row)throw new ServiceError('not_found',404);
  if((row.review_state==='ready'||row.review_state==='partial')&&row.result)return {status:200,review:row.result};
  if(row.review_state==='queued'||row.review_state==='running')return {status:202,state:row.review_state,retryAfterMs:5000};
  if(row.review_state==='unknown')throw new ServiceError('review_unknown',409);
  if(row.review_state==='failed')throw new ServiceError('review_failed',409);
  throw new ServiceError('review_not_available',409);
}

export async function listDailyAttempts(db:Database,accountId:string,language:string,limit=20,cursor?:string):Promise<{items:DailyAttemptView[];nextCursor:string|null}> {
  if(!uuid.test(accountId)||language!=='en'||!Number.isSafeInteger(limit)||limit<1||limit>50)throw new ServiceError('invalid_daily_history');
  const parsed=cursor?parseDailyAttemptCursor(cursor,accountId,language):null;
  const result=parsed?await db.query(`SELECT * FROM daily_attempts WHERE account_id=$1 AND language=$2
      AND (started_at,id)<($3::timestamptz,$4::uuid) ORDER BY started_at DESC,id DESC LIMIT $5`,
      [accountId,language,parsed.startedAt,parsed.attemptId,limit+1]):
    await db.query('SELECT * FROM daily_attempts WHERE account_id=$1 AND language=$2 ORDER BY started_at DESC,id DESC LIMIT $3',
      [accountId,language,limit+1]);
  const rows=result.rows as any[];
  const page=rows.slice(0,limit);
  const last=rows.length>limit?page[page.length-1]:null;
  return {items:page.map(attemptView),nextCursor:last?encodeDailyAttemptCursor(accountId,language,
    new Date(last.started_at).toISOString(),last.id):null};
}

function gradeSubmittedStage(state:DailyAttemptState,event:Record<string,any>,publicPackage:any,privatePackage:any,language:'en'|'es'):DailyAttemptState {
  const questions=event.stage==='listening'?publicPackage.listening.questions:publicPackage.practice.questions;
  const expected=event.stage==='listening'?privatePackage.listening:privatePackage.practice;
  if(!Array.isArray(questions)||!Array.isArray(expected))throw new ServiceError('daily_content_invalid',503);
  const expectedIds=questions.map((q:any)=>q.id).sort();const supplied=event.answers.map((a:any)=>a.questionId).sort();
  if(expectedIds.length!==supplied.length||expectedIds.some((id:string,i:number)=>id!==supplied[i]))throw new ServiceError('invalid_daily_event');
  const result=event.answers.map((answer:any)=>{
    const item=questions.find((q:any)=>q.id===answer.questionId);const key=expected.find((x:any)=>x.questionId===answer.questionId);
    if(!item||!key)throw new ServiceError('daily_content_invalid',503);
    const accepted=key.acceptedOptionId?[key.acceptedOptionId]:key.acceptedAnswers;
    if(!Array.isArray(accepted)||accepted.some((x:unknown)=>!text(x,120)))throw new ServiceError('daily_content_invalid',503);
    if(answer.value!==null&&item.kind==='single_choice'&&!item.options.some((option:any)=>option.id===answer.value))throw new ServiceError('invalid_daily_event');
    const correct=answer.value!==null&&gradeDailyClosedAnswers(answer.value,accepted);
    const explanation=key.explanation?.[language];if(!text(explanation,500))throw new ServiceError('daily_content_invalid',503);
    return {questionId:answer.questionId,status:answer.value===null?'skipped':correct?'correct':'incorrect',acceptedAnswer:accepted[0],explanation};
  });
  const applied=applyDailyEvent(state,event);
  return {...applied,sectionResults:{...(state.sectionResults??{}),[event.stage]:result},helpUsed:{...(state.helpUsed??{}),[event.stage]:event.helpUsed}};
}

export async function appendDailyEvent(db:Database,accountId:string,attemptId:string,eventValue:unknown,now=()=>new Date()):Promise<DailyAttemptView> {
  const envelope=validateDailyEvent(eventValue);if(!uuid.test(accountId)||!uuid.test(attemptId))throw new ServiceError('not_found',404);
  const intentHash=hashDailyEventIntent(envelope.event);
  return transaction(db,async tx=>{
    const row=(await tx.query(`SELECT * FROM daily_attempts WHERE account_id=$1 AND id=$2 FOR UPDATE`,[accountId,attemptId])).rows[0];
    if(!row)throw new ServiceError('not_found',404);
    const prior=(await tx.query<{request_hash:string;response:Record<string,unknown>}>(`SELECT request_hash,response FROM daily_attempt_events
      WHERE attempt_id=$1 AND event_id=$2`,[attemptId,envelope.eventId])).rows[0];
    if(prior){if(prior.request_hash!==intentHash)throw new ServiceError('idempotency_conflict',409);return prior.response as unknown as DailyAttemptView;}
    if(Number(row.revision)!==envelope.expectedRevision)throw new ServiceError('revision_conflict',409);
    const state=attemptState(row);const event=envelope.event;
    if(event.type==='speaking_segment_saved'){
      const voice=(await tx.query(`SELECT 1 FROM daily_voice_segments WHERE id=$1 AND attempt_id=$2 AND state IN ('closed','unknown')`,
        [event.segmentId,attemptId])).rowCount;
      if(!voice)throw new ServiceError('daily_voice_segment_unavailable',409);
    }
    let next:DailyAttemptState;
    if(event.type==='stage_submitted'){
      const packageRow=(await tx.query<{public_payload:any;private_payload:any}>(`SELECT public_payload,private_payload FROM daily_packages WHERE id=$1`,[row.package_id])).rows[0];
      if(!packageRow)throw new ServiceError('daily_content_unavailable',503);
      next=gradeSubmittedStage(state,event,packageRow.public_payload,packageRow.private_payload,row.explanation_language);
    }else next=applyDailyEvent(state,event);
    next={...next,revision:envelope.expectedRevision+1};
    const finished=event.type==='finished';const instant=now();
    const inputSnapshot=finished?{schemaVersion:1,attemptId,packageVersionId:row.package_id,stageState:next}:null;
    const inputHash=finished?hashDailyArtifact(inputSnapshot):null;
    const noEvidence=finished&&!hasDailyReviewEvidence(next);
    const reviewState=finished?(noEvidence?'partial':'queued'):row.review_state;
    const update=await tx.query(`UPDATE daily_attempts SET stage_state=$3::jsonb,revision=$4,active_ms=$5,state=$6,stop_reason=$7,
      review_state=$8,input_hash=$9,updated_at=$10 WHERE account_id=$1 AND id=$2 AND revision=$4-1 RETURNING *`,
    [accountId,attemptId,JSON.stringify(next),next.revision,next.activeMs,finished?'completed':'in_progress',finished?event.stopReason:null,reviewState,inputHash,instant]);
    if(update.rowCount!==1)throw new ServiceError('revision_conflict',409);
    if(finished){
      await tx.query('INSERT INTO daily_review_inputs(attempt_id,input_hash,snapshot) VALUES($1,$2,$3::jsonb)',[attemptId,inputHash,JSON.stringify(inputSnapshot)]);
      if(noEvidence){
        const reviewId=randomUUID();
        const result={schemaVersion:1,reviewId,attemptId,packageVersionId:row.package_id,language:row.language,
          explanationLanguage:row.explanation_language,inputHash,rubricVersion:'daily-review-v1',createdAt:instant.getTime(),outcome:'partial',
          sections:['listening','speaking','practice'].map(stage=>({stage,evidence:'not_observed',correct:null,total:null,helped:false,
            explanation:row.explanation_language==='es'?'No hubo respuestas o fragmentos suficientes para evaluar esta parte.':'There was not enough submitted evidence to assess this section.'})),
          strengths:[],priorities:[],errors:[],wordCandidates:next.wordMarks.map(mark=>({candidateId:mark.markId,kind:mark.kind,text:mark.text,
            meaning:mark.meaning,origin:'learner_mark',confidence:'supported',evidenceIds:[mark.markId]}))};
        await tx.query('INSERT INTO daily_reviews(id,attempt_id,input_hash,rubric_version,result) VALUES($1,$2,$3,$4,$5::jsonb)',
          [reviewId,attemptId,inputHash,'daily-review-v1',JSON.stringify(result)]);
        await tx.query("UPDATE daily_attempts SET review_id=$2 WHERE id=$1",[attemptId,reviewId]);
        await tx.query("UPDATE daily_cost_reservations SET state='released' WHERE id=(SELECT review_reservation_id FROM daily_attempts WHERE id=$1) AND state='held'",[attemptId]);
        await tx.query("UPDATE daily_jobs SET state='cancelled',revision=revision+1,updated_at=$2 WHERE kind='review' AND attempt_id=$1 AND state='reserved'",[attemptId,instant]);
      }else{
        await tx.query(`UPDATE daily_jobs SET state='queued',revision=revision+1,updated_at=$2 WHERE kind='review' AND attempt_id=$1 AND state='reserved'`,[attemptId,instant]);
      }
    }
    const currentRow=finished?(await tx.query('SELECT * FROM daily_attempts WHERE account_id=$1 AND id=$2',[accountId,attemptId])).rows[0]:update.rows[0];
    const view=attemptView(currentRow);
    await tx.query(`INSERT INTO daily_attempt_events(attempt_id,event_id,request_hash,expected_revision,accepted_revision,event_type,body,response)
      VALUES($1,$2,$3,$4,$5,$6,$7::jsonb,$8::jsonb)`,[attemptId,envelope.eventId,intentHash,envelope.expectedRevision,next.revision,event.type,JSON.stringify(event),JSON.stringify(view)]);
    return view;
  });
}
