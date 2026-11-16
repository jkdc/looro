import { transaction, type Database } from './db.js';
import { ServiceError } from './errors.js';
import { recordAdminAction } from './admin-audit.js';
import { hashDailyArtifact, projectPublicDailyPackage } from './daily-content.js';
import { ensureDailyPackage } from './daily-jobs.js';
import { publishDailyPackage } from './daily-generation.js';
import type { PoolClient } from 'pg';

export type NormalizedDailyAdminConfig = { expectedRevision:number; readEnabled:boolean; newAttemptsEnabled:boolean;
  generationEnabled:boolean; autoPublishEnabled:boolean; enabledLanguages:string[]; contentBudgetNanoUsd:string;
  reviewBudgetNanoUsd:string; retentionDays:number };
export type DailyAdminPackageQuery={language:'en';dateFrom:string;dateTo:string;limit:number;cursor:{date:string;id:string}|null};
export type DailyAdminJobQuery={language:'en';dateFrom:string;dateTo:string;kind:'all'|'content'|'review';limit:number};
export type DailyAdminGenerationRequest={language:'en';editorialDate:string;reason:string};
export type DailyAdminPublicationRequest={packageVersionId:string;artifactHash:string;expectedRevision:number;reason:string};
const keys=['schemaVersion','expectedRevision','readEnabled','newAttemptsEnabled','generationEnabled','autoPublishEnabled',
  'enabledLanguages','contentBudgetNanoUsd','reviewBudgetNanoUsd','retentionDays'];
const isRecord=(x:unknown):x is Record<string,unknown>=>!!x&&typeof x==='object'&&!Array.isArray(x);
const budget=(x:unknown):x is string=>typeof x==='string'&&/^(0|[1-9]\d{0,18})$/.test(x)&&BigInt(x)<=9_223_372_036_854_775_807n;
export function validateDailyAdminReason(value:unknown):string {
  if(typeof value!=='string'||[...value.trim()].length<10||[...value].length>500||/[\u0000-\u001f\u007f]/u.test(value))
    throw new ServiceError('admin_reason_required',400);
  return value.trim();
}

export function parseDailyAdminPackageQuery(value:unknown):DailyAdminPackageQuery {
  if(!isRecord(value)||Object.keys(value).some(key=>!['language','dateFrom','dateTo','limit','cursor'].includes(key))||
    value.language!=='en'||typeof value.dateFrom!=='string'||typeof value.dateTo!=='string'||
    !/^\d{4}-\d{2}-\d{2}$/.test(value.dateFrom)||!/^\d{4}-\d{2}-\d{2}$/.test(value.dateTo))
    throw new ServiceError('invalid_daily_admin_query',400);
  const from=new Date(`${value.dateFrom}T00:00:00Z`),to=new Date(`${value.dateTo}T00:00:00Z`);
  if(!Number.isFinite(from.getTime())||!Number.isFinite(to.getTime())||from.toISOString().slice(0,10)!==value.dateFrom||
    to.toISOString().slice(0,10)!==value.dateTo||to<from||to.getTime()-from.getTime()>30*86_400_000)
    throw new ServiceError('invalid_daily_admin_query',400);
  const limit=value.limit===undefined?'20':value.limit;
  if(typeof limit!=='string'||! /^(?:[1-9]|[1-4][0-9]|50)$/.test(limit))throw new ServiceError('invalid_daily_admin_query',400);
  let cursor:DailyAdminPackageQuery['cursor']=null;
  if(value.cursor!==undefined){
    if(typeof value.cursor!=='string'||value.cursor.length>512)throw new ServiceError('invalid_daily_admin_query',400);
    try{
      const raw=Buffer.from(value.cursor,'base64url').toString('utf8');
      if(Buffer.from(raw,'utf8').toString('base64url')!==value.cursor)throw new Error();
      const decoded=JSON.parse(raw);
      if(!isRecord(decoded)||Object.keys(decoded).length!==5||decoded.language!=='en'||decoded.dateFrom!==value.dateFrom||decoded.dateTo!==value.dateTo||
        typeof decoded.date!=='string'||decoded.date<value.dateFrom||decoded.date>value.dateTo||typeof decoded.id!=='string'||
        !/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(decoded.id))throw new Error();
      cursor={date:decoded.date,id:decoded.id};
    }catch{throw new ServiceError('invalid_daily_admin_query',400);}
  }
  return {language:'en',dateFrom:value.dateFrom,dateTo:value.dateTo,limit:Number(limit),cursor};
}

function validISODate(value:unknown):value is string {
  if(typeof value!=='string'||!/^\d{4}-\d{2}-\d{2}$/.test(value))return false;
  const date=new Date(`${value}T00:00:00Z`);
  return Number.isFinite(date.getTime())&&date.toISOString().slice(0,10)===value;
}
export function parseDailyAdminJobQuery(value:unknown):DailyAdminJobQuery {
  if(!isRecord(value)||Object.keys(value).some(key=>!['language','dateFrom','dateTo','kind','limit'].includes(key))||
    value.language!=='en'||!validISODate(value.dateFrom)||!validISODate(value.dateTo)||value.dateFrom>value.dateTo||
    !(value.kind===undefined||value.kind==='all'||value.kind==='content'||value.kind==='review'))throw new ServiceError('invalid_daily_admin_query',400);
  const days=(Date.parse(`${value.dateTo}T00:00:00Z`)-Date.parse(`${value.dateFrom}T00:00:00Z`))/86_400_000;
  if(days>30)throw new ServiceError('invalid_daily_admin_query',400);
  const limitText=value.limit===undefined?'50':String(value.limit);
  if(!/^(?:[1-9]|[1-9][0-9]|100)$/.test(limitText))throw new ServiceError('invalid_daily_admin_query',400);
  return {language:'en',dateFrom:value.dateFrom,dateTo:value.dateTo,kind:(value.kind??'all') as DailyAdminJobQuery['kind'],limit:Number(limitText)};
}

function projectDailyProviderOperations(value:unknown):Record<string,unknown>[] {
  let rows=value;
  if(typeof rows==='string')try{rows=JSON.parse(rows);}catch{return [];}
  if(!Array.isArray(rows))return [];
  const phases=new Set(['generate','judge','tts','audio_check','review']);
  const states=new Set(['reserved','sending','succeeded','failed','unknown']);
  return rows.flatMap((entry:unknown)=>{
    if(!isRecord(entry)||!phases.has(String(entry.phase))||!states.has(String(entry.state)))return [];
    let usage:Record<string,unknown>|null=null;
    const raw=entry.usage;
    if(isRecord(raw)&&Object.keys(raw).every(key=>['state','tokensIn','tokensOut'].includes(key))&&
      ['complete','partial','unknown'].includes(String(raw.state))&&
      [raw.tokensIn,raw.tokensOut].every(count=>count===null||(typeof count==='number'&&Number.isSafeInteger(count)&&count>=0))&&
      !(raw.state==='complete'&&(raw.tokensIn===null||raw.tokensOut===null))&&
      !(raw.state==='partial'&&raw.tokensIn===null&&raw.tokensOut===null)&&
      !(raw.state==='unknown'&&(raw.tokensIn!==null||raw.tokensOut!==null)))
      usage={state:raw.state,tokensIn:raw.tokensIn,tokensOut:raw.tokensOut};
    const updatedAt=entry.updated_at instanceof Date?entry.updated_at.toISOString():
      typeof entry.updated_at==='string'&&Number.isFinite(Date.parse(entry.updated_at))?entry.updated_at:null;
    return [{phase:entry.phase,state:entry.state,usage,updated_at:updatedAt}];
  });
}

/** Operational run metadata only; never selects attempt snapshots or provider payloads. */
export async function listDailyAdminJobs(db:Database,queryValue:unknown):Promise<{items:Record<string,unknown>[];hasMore:boolean}> {
  const query=parseDailyAdminJobQuery(queryValue);
  const result=await db.query(`SELECT j.id,j.kind,j.language,j.editorial_date,j.state,j.revision,j.error_code,j.created_at,j.updated_at,
    (SELECT o.phase FROM daily_provider_operations o WHERE o.job_id=j.id ORDER BY o.updated_at DESC,o.id DESC LIMIT 1) AS phase,
    COALESCE((SELECT jsonb_agg(jsonb_build_object('phase',o.phase,'state',o.state,'usage',o.usage,'updated_at',o.updated_at)
      ORDER BY o.updated_at,o.id) FROM daily_provider_operations o WHERE o.job_id=j.id),'[]'::jsonb) AS provider_operations,
    COALESCE((SELECT SUM(r.max_nano_usd) FROM daily_cost_reservations r WHERE r.job_id=j.id AND r.state IN ('held','settled','unknown')),0)::text AS reserved_nano_usd,
    COALESCE((SELECT SUM(r.settled_nano_usd) FROM daily_cost_reservations r WHERE r.job_id=j.id AND r.state='settled'),0)::text AS settled_nano_usd
    FROM daily_jobs j WHERE j.language=$1 AND j.created_at >= $2::date::timestamptz AND j.created_at < ($3::date + 1)::timestamptz
      AND ($4::text='all' OR j.kind=$4) ORDER BY j.created_at DESC,j.id DESC LIMIT $5`,
    [query.language,query.dateFrom,query.dateTo,query.kind,query.limit+1]);
  const rows=result.rows as Record<string,unknown>[];
  const fields=['id','kind','language','editorial_date','state','revision','error_code','created_at','updated_at','phase','reserved_nano_usd','settled_nano_usd'];
  return {items:rows.slice(0,query.limit).map(row=>({...Object.fromEntries(fields.map(field=>[field,row[field]??null])),
    provider_operations:projectDailyProviderOperations(row.provider_operations)})),hasMore:rows.length>query.limit};
}
export function parseDailyAdminGenerationRequest(value:unknown):DailyAdminGenerationRequest {
  if(!isRecord(value)||Object.keys(value).length!==3||Object.keys(value).some(key=>!['language','editorialDate','reason'].includes(key))||
    value.language!=='en'||typeof value.editorialDate!=='string'||!/^\d{4}-\d{2}-\d{2}$/.test(value.editorialDate))
    throw new ServiceError('invalid_daily_admin_request',400);
  const date=new Date(`${value.editorialDate}T00:00:00Z`);
  if(!Number.isFinite(date.getTime())||date.toISOString().slice(0,10)!==value.editorialDate)throw new ServiceError('invalid_daily_admin_request',400);
  return {language:'en',editorialDate:value.editorialDate,reason:validateDailyAdminReason(value.reason)};
}
export function parseDailyAdminPublicationRequest(value:unknown):DailyAdminPublicationRequest {
  if(!isRecord(value)||Object.keys(value).length!==4||Object.keys(value).some(key=>!['packageVersionId','artifactHash','expectedRevision','reason'].includes(key))||
    typeof value.packageVersionId!=='string'||!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value.packageVersionId)||
    typeof value.artifactHash!=='string'||!/^[0-9a-f]{64}$/.test(value.artifactHash)||!Number.isSafeInteger(value.expectedRevision)||Number(value.expectedRevision)<0)
    throw new ServiceError('invalid_daily_admin_request',400);
  return {packageVersionId:value.packageVersionId,artifactHash:value.artifactHash,expectedRevision:Number(value.expectedRevision),
    reason:validateDailyAdminReason(value.reason)};
}
function encodeDailyAdminCursor(query:DailyAdminPackageQuery,date:string,id:string):string {
  return Buffer.from(JSON.stringify({language:query.language,dateFrom:query.dateFrom,dateTo:query.dateTo,date,id}),'utf8').toString('base64url');
}

export async function runDailyAdminIdempotent<T>(db:Database,adminId:string,operation:string,idempotencyKey:string,body:unknown,
  run:(tx:PoolClient)=>Promise<T>):Promise<{response:T;replayed:boolean}> {
  if(!/^[0-9a-f-]{36}$/i.test(adminId)||!/^[a-z_]{1,64}$/.test(operation)||!/^[0-9a-f-]{36}$/i.test(idempotencyKey))
    throw new ServiceError('invalid_idempotency_key',400);
  if(!isRecord(body)||Buffer.byteLength(JSON.stringify(body),'utf8')>32*1024)throw new ServiceError('invalid_request',400);
  const requestHash=hashDailyArtifact(body);
  return transaction(db,async tx=>{
    await tx.query('SELECT pg_advisory_xact_lock(hashtextextended($1,0))',[`${adminId}:${operation}:${idempotencyKey}`]);
    const prior=(await tx.query<{request_hash:string;response:T}>(`SELECT request_hash,response FROM daily_admin_request_keys
      WHERE admin_id=$1 AND operation=$2 AND idempotency_key=$3 FOR UPDATE`,[adminId,operation,idempotencyKey])).rows[0];
    if(prior){if(prior.request_hash!==requestHash)throw new ServiceError('idempotency_conflict',409);
      return {response:prior.response,replayed:true};}
    const response=await run(tx);
    const serialized=JSON.stringify(response);
    if(Buffer.byteLength(serialized,'utf8')>32*1024)throw new ServiceError('daily_admin_response_too_large',500);
    await tx.query(`INSERT INTO daily_admin_request_keys(admin_id,operation,idempotency_key,request_hash,response)
      VALUES($1,$2,$3,$4,$5::jsonb)`,[adminId,operation,idempotencyKey,requestHash,serialized]);
    return {response,replayed:false};
  });
}
export function normalizeDailyAdminConfig(input:unknown):NormalizedDailyAdminConfig {
  if(!isRecord(input)||Object.keys(input).length!==keys.length||Object.keys(input).some(key=>!keys.includes(key))||input.schemaVersion!==1||
    !Number.isSafeInteger(input.expectedRevision)||Number(input.expectedRevision)<0||
    !['readEnabled','newAttemptsEnabled','generationEnabled','autoPublishEnabled'].every(key=>typeof input[key]==='boolean')||
    !Array.isArray(input.enabledLanguages)||input.enabledLanguages.length!==1||input.enabledLanguages[0]!=='en'||
    !budget(input.contentBudgetNanoUsd)||!budget(input.reviewBudgetNanoUsd)||
    !Number.isSafeInteger(input.retentionDays)||Number(input.retentionDays)<30||Number(input.retentionDays)>3650)
    throw new ServiceError('invalid_daily_config');
  return { expectedRevision:Number(input.expectedRevision),readEnabled:input.readEnabled as boolean,
    newAttemptsEnabled:input.newAttemptsEnabled as boolean,generationEnabled:input.generationEnabled as boolean,
    autoPublishEnabled:input.autoPublishEnabled as boolean,enabledLanguages:['en'],
    contentBudgetNanoUsd:input.contentBudgetNanoUsd as string,reviewBudgetNanoUsd:input.reviewBudgetNanoUsd as string,
    retentionDays:Number(input.retentionDays) };
}

export async function saveDailyAdminConfig(tx:PoolClient,input:unknown,adminId:string,reasonValue:unknown):Promise<Record<string,unknown>> {
  const config=normalizeDailyAdminConfig(input);
  const reason=validateDailyAdminReason(reasonValue);
    const result=await tx.query(`UPDATE daily_config SET revision=revision+1,read_enabled=$2,new_attempts_enabled=$3,
      generation_enabled=$4,auto_publish_enabled=$5,enabled_languages=$6::text[],content_budget_nano_usd=$7,
      review_budget_nano_usd=$8,retention_days=$9,updated_at=now()
      WHERE singleton=true AND revision=$1 RETURNING revision,read_enabled,new_attempts_enabled,generation_enabled,
      auto_publish_enabled,enabled_languages,content_budget_nano_usd,review_budget_nano_usd,retention_days,updated_at`,
    [config.expectedRevision,config.readEnabled,config.newAttemptsEnabled,config.generationEnabled,config.autoPublishEnabled,
      config.enabledLanguages,config.contentBudgetNanoUsd,config.reviewBudgetNanoUsd,config.retentionDays]);
    if(result.rowCount!==1)throw new ServiceError('daily_revision_conflict',409);
    await recordAdminAction(tx,adminId,'daily.config.update','daily_config',reason,{revision:result.rows[0]!.revision,
      readEnabled:config.readEnabled,newAttemptsEnabled:config.newAttemptsEnabled,generationEnabled:config.generationEnabled,
      autoPublishEnabled:config.autoPublishEnabled,enabledLanguages:config.enabledLanguages,
      contentBudgetNanoUsd:config.contentBudgetNanoUsd,reviewBudgetNanoUsd:config.reviewBudgetNanoUsd,
      retentionDays:config.retentionDays});
    return result.rows[0] as Record<string,unknown>;
}

export async function dailyAdminSnapshot(db:Database):Promise<Record<string,unknown>> {
  const [config,counts]=await Promise.all([
    db.query(`SELECT revision,read_enabled,new_attempts_enabled,generation_enabled,auto_publish_enabled,enabled_languages,
      content_budget_nano_usd::text,review_budget_nano_usd::text,retention_days,updated_at FROM daily_config WHERE singleton=true`),
    db.query(`SELECT kind,state,count(*)::integer AS count FROM daily_jobs GROUP BY kind,state ORDER BY kind,state`),
  ]);
  return {config:config.rows[0]??null,jobs:counts.rows};
}

export async function listDailyAdminPackages(db:Database,queryValue:unknown):Promise<{items:Record<string,unknown>[];nextCursor:string|null}> {
  const query=parseDailyAdminPackageQuery(queryValue);
  const result=await db.query(`SELECT id,language,editorial_date,state,revision,content_hash,text_hash,artifact_hash,
    judge_state,judge_text_hash,created_at FROM daily_packages
    WHERE language=$1 AND editorial_date BETWEEN $2::date AND $3::date AND
      ($4::date IS NULL OR (editorial_date,id)<($4::date,$5::uuid))
    ORDER BY editorial_date DESC,id DESC LIMIT $6`,[query.language,query.dateFrom,query.dateTo,query.cursor?.date??null,
      query.cursor?.id??null,query.limit+1]);
  const rows=result.rows as Record<string,unknown>[];
  const hasNext=rows.length>query.limit,items=rows.slice(0,query.limit).map(row=>({...row,
    editorial_date:row.editorial_date instanceof Date?row.editorial_date.toISOString().slice(0,10):row.editorial_date}));
  const last=items[items.length-1] as Record<string,unknown>|undefined;
  return {items,nextCursor:hasNext&&last?encodeDailyAdminCursor(query,String(last.editorial_date),String(last.id)):null};
}

export async function getDailyAdminPackage(db:Database,id:string):Promise<Record<string,unknown>> {
  if(!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(id))throw new ServiceError('not_found',404);
  const row=(await db.query(`SELECT id,language,editorial_date,state,revision,public_payload,private_payload,content_hash,text_hash,artifact_hash,
    judge_state,judge_text_hash,judge_artifact_hash,created_at
    FROM daily_packages WHERE id=$1`,[id])).rows[0] as Record<string,unknown>|undefined;
  if(!row)throw new ServiceError('not_found',404);
  const evidence=await db.query(`SELECT o.phase,o.state,o.result_hash,o.usage,o.updated_at FROM daily_provider_operations o
    JOIN daily_jobs j ON j.id=o.job_id WHERE j.language=$1 AND j.editorial_date=$2 AND j.kind='content' ORDER BY o.updated_at`,
    [row.language,row.editorial_date]);
  return {...projectDailyAdminPackage(row),evidence:evidence.rows};
}

/** Admin previews may show only the same whitelisted projection learners receive; private answer keys never reach browser JS. */
export function projectDailyAdminPackage(row:Record<string,unknown>):Record<string,unknown> {
  const {private_payload:_privatePayload,public_payload,...metadata}=row;
  let preview:unknown=null;
  if(public_payload!==null&&public_payload!==undefined) {
    try { preview=projectPublicDailyPackage(public_payload); } catch { /* Incomplete draft: expose metadata but no raw payload. */ }
  }
  return {...metadata,preview};
}

export async function startDailyAdminGeneration(db:Database,adminId:string,requestValue:unknown,idempotencyKey:string):Promise<
  {response:{runId:string;state:string};replayed:boolean}> {
  const request=parseDailyAdminGenerationRequest(requestValue);
  return runDailyAdminIdempotent(db,adminId,'generation_run_create',idempotencyKey,request,async tx=>{
    const runId=await ensureDailyPackage(db,request.language,request.editorialDate);
    if(runId==='disabled')throw new ServiceError('daily_generation_disabled',409);
    if(!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(runId))
      throw new ServiceError('daily_generation_run_unavailable',503);
    const job=(await db.query<{state:string}>('SELECT state FROM daily_jobs WHERE id=$1',[runId])).rows[0];
    if(!job)throw new ServiceError('daily_generation_run_unavailable',503);
    await recordAdminAction(tx,adminId,'daily.generation.start',`${request.language}:${request.editorialDate}`,request.reason,
      {language:request.language,editorialDate:request.editorialDate,runId});
    return {runId,state:job.state};
  });
}

export async function getDailyAdminRun(db:Database,id:string):Promise<Record<string,unknown>> {
  if(!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(id))throw new ServiceError('not_found',404);
  const row=(await db.query(`SELECT j.id,j.kind,j.binding_id,j.language,j.editorial_date,j.state,j.revision,j.error_code,j.created_at,j.updated_at,
    COALESCE((SELECT SUM(CASE WHEN state='settled' THEN COALESCE(settled_nano_usd,0) ELSE max_nano_usd END)
      FROM daily_cost_reservations r WHERE r.job_id=j.id AND r.state IN ('held','settled','unknown')),0)::text AS reserved_nano_usd,
    (SELECT phase FROM daily_provider_operations o WHERE o.job_id=j.id ORDER BY o.updated_at DESC,o.id DESC LIMIT 1) AS phase,
    COALESCE((SELECT jsonb_agg(jsonb_build_object('phase',o.phase,'state',o.state,'usage',o.usage,'updated_at',o.updated_at)
      ORDER BY o.updated_at,o.id) FROM daily_provider_operations o WHERE o.job_id=j.id),'[]'::jsonb) AS provider_operations
    FROM daily_jobs j WHERE j.id=$1`,[id])).rows[0] as Record<string,unknown>|undefined;
  if(!row)throw new ServiceError('not_found',404);
  const {binding_id,...run}=row;
  return {...run,packageVersionId:row.kind==='content'?binding_id:null,
    provider_operations:projectDailyProviderOperations(row.provider_operations)};
}

export async function publishDailyAdminPackage(db:Database,adminId:string,requestValue:unknown,idempotencyKey:string):Promise<
  {response:Record<string,unknown>;replayed:boolean}> {
  const request=parseDailyAdminPublicationRequest(requestValue);
  return runDailyAdminIdempotent(db,adminId,'publication_create',idempotencyKey,request,async tx=>{
    let publication;
    try{publication=await publishDailyPackage(tx,request.packageVersionId,request.artifactHash,request.expectedRevision);}
    catch(error){
      const code=error instanceof Error?error.message:'daily_publication_failed';
      const status=code==='daily_package_not_found'?404:code==='daily_revision_conflict'||code==='daily_publication_consumed'||code==='daily_publication_withdrawn'?409:400;
      if(!/^(?:daily|invalid_daily)_[a-z0-9_]{1,80}$/.test(code))throw error;
      throw new ServiceError(code,status);
    }
    await recordAdminAction(tx,adminId,'daily.package.publish',request.packageVersionId,request.reason,
      {artifactHash:request.artifactHash,revision:publication.revision,language:publication.language,editorialDate:publication.editorialDate});
    return publication as unknown as Record<string,unknown>;
  });
}

export async function withdrawDailyAdminPackage(db:Database,adminId:string,id:string,expectedRevision:number,reasonValue:unknown,key:string):Promise<
  {response:Record<string,unknown>;replayed:boolean}> {
  if(!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(id)||
    !Number.isSafeInteger(expectedRevision)||expectedRevision<0)throw new ServiceError('invalid_daily_admin_request',400);
  const reason=validateDailyAdminReason(reasonValue),body={packageVersionId:id,expectedRevision,reason};
  return runDailyAdminIdempotent(db,adminId,'package_withdraw',key,body,async tx=>{
    const row=(await tx.query<{language:string;editorial_date:string;state:string;revision:number}>(
      'SELECT language,editorial_date,state,revision FROM daily_packages WHERE id=$1 FOR UPDATE',[id])).rows[0];
    if(!row)throw new ServiceError('not_found',404);
    if(Number(row.revision)!==expectedRevision)throw new ServiceError('daily_revision_conflict',409);
    if(row.state==='withdrawn')throw new ServiceError('daily_package_terminal',409);
    const updated=await tx.query(`UPDATE daily_packages SET state='withdrawn',revision=revision+1 WHERE id=$1 AND revision=$2
      AND state IN ('draft','validating','ready','published') RETURNING revision`,[id,expectedRevision]);
    if(updated.rowCount!==1)throw new ServiceError('daily_revision_conflict',409);
    await tx.query(`UPDATE daily_publications SET withdrawn_at=COALESCE(withdrawn_at,now()),revision=revision+1
      WHERE package_id=$1 AND withdrawn_at IS NULL`,[id]);
    await recordAdminAction(tx,adminId,'daily.package.withdraw',id,reason,{language:row.language,editorialDate:row.editorial_date,
      previousState:row.state,revision:Number(updated.rows[0]!.revision)});
    return {packageVersionId:id,state:'withdrawn',revision:Number(updated.rows[0]!.revision)};
  });
}
