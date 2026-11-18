import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import type { Database } from '../src/db.js';
import type { DailyJob } from '../src/daily-jobs.js';
import { runDailyContentJob, runDailyContentWorkerOnce, runDailyReviewJob, runDailyReviewWorkerOnce } from '../src/daily-workers.js';
import { hashDailyArtifact } from '../src/daily-content.js';

test('daily content worker does not resolve or call a provider when its frozen budget is zero', async () => {
  const job: DailyJob = { id:'11111111-1111-4111-8111-111111111111',kind:'content',binding_id:'22222222-2222-4222-8222-222222222222',
    language:'en',editorial_date:'2026-11-08',attempt_id:null,state:'running',lease_owner:'daily-worker-a',lease_until:new Date('2026-11-08T12:01:00Z'),
    config_snapshot:{schemaVersion:1,profileVersion:'en-v1',contentBudgetNanoUsd:'0',autoPublishEnabled:false} };
  const statements:string[]=[];
  const client={query:async(sql:string)=>{
    statements.push(sql);
    if(sql.includes('FROM daily_jobs')&&sql.includes('FOR UPDATE'))return {rows:[job]};
    if(sql.includes('FROM daily_config'))return {rows:[{generation_enabled:true,enabled_languages:['en'],budget_enforced:true}]};
    return {rows:[],rowCount:1};
  },release:()=>{}};
  const db={connect:async()=>client} as unknown as Database;
  let providerResolutions=0;
  const result=await runDailyContentJob(db,{},job,'daily-worker-a',{now:()=>new Date('2026-11-08T12:00:00Z'),
    createProvider:async()=>{providerResolutions++;throw new Error('must_not_resolve');}});
  assert.deepEqual(result,{jobId:job.id,state:'failed',packageVersionId:null,errorCode:'daily_budget_unavailable'});
  assert.equal(providerResolutions,0);
  assert.equal(statements.some(sql=>sql.includes('INSERT INTO daily_provider_operations')),false);
});

test('daily content worker skips budget checks when daily_config disables enforcement', async () => {
  const job: DailyJob = { id:'11111111-1111-4111-8111-111111111111',kind:'content',binding_id:'22222222-2222-4222-8222-222222222222',
    language:'en',editorial_date:'2026-11-08',attempt_id:null,state:'running',lease_owner:'daily-worker-a',lease_until:new Date('2026-11-08T12:01:00Z'),
    config_snapshot:{schemaVersion:1,profileVersion:'en-v1',contentBudgetNanoUsd:'0',autoPublishEnabled:false} };
  const statements:string[]=[];
  const client={query:async(sql:string)=>{
    statements.push(sql);
    if(sql.includes('FROM daily_jobs')&&sql.includes('FOR UPDATE'))return {rows:[job]};
    if(sql.includes('FROM daily_config'))return {rows:[{generation_enabled:true,enabled_languages:['en'],budget_enforced:false}]};
    if(sql.includes('FROM daily_packages WHERE id=$1 FOR UPDATE'))return {rows:[{state:'draft',revision:0}],rowCount:1};
    return {rows:[],rowCount:1};
  },release:()=>{}};
  const db={connect:async()=>client} as unknown as Database;
  let providerResolutions=0;
  try {
    await runDailyContentJob(db,{},job,'daily-worker-a',{now:()=>new Date('2026-11-08T12:00:00Z'),
      createProvider:async()=>{providerResolutions++;throw new Error('provider_reached_after_bypass');}});
  } catch (error) { if (!(error instanceof Error) || !error.message.includes('provider_reached_after_bypass')) throw error; }
  // The bypass must reach the provider instead of failing with daily_budget_unavailable.
  assert.equal(providerResolutions,1);
  assert.ok(statements.some(sql=>sql.includes('INSERT INTO daily_cost_reservations')), 'reservation must still be recorded');
});

test('daily worker tick recovers expired leases and claims one content job atomically', async () => {
  const statements:string[]=[];
  const client={query:async(sql:string)=>{statements.push(sql);return {rows:[],rowCount:0};},release:()=>{}};
  const db={connect:async()=>client} as unknown as Database;
  const result=await runDailyContentWorkerOnce(db,{},'daily-worker-a',{now:()=>new Date('2026-11-08T12:00:00Z'),
    createProvider:async()=>{throw new Error('no queued job should resolve a provider');}});
  assert.equal(result,null);
  assert.ok(statements.some(sql=>/UPDATE daily_provider_operations/.test(sql)));
  assert.ok(statements.some(sql=>/UPDATE daily_jobs/.test(sql)));
  assert.ok(statements.some(sql=>/FOR UPDATE SKIP LOCKED/.test(sql)));
});

test('daily review worker tick claims only review jobs and does not resolve a provider when none are queued', async () => {
  const statements:string[]=[];
  const client={query:async(sql:string)=>{statements.push(sql);return {rows:[],rowCount:0};},release:()=>{}};
  const db={connect:async()=>client} as unknown as Database;
  const result=await runDailyReviewWorkerOnce(db,{},'daily-review-a',{now:()=>new Date('2026-11-08T12:00:00Z'),
    createProvider:async()=>{throw new Error('no review job should resolve a provider');}});
  assert.equal(result,null);
  assert.ok(statements.some(sql=>/FOR UPDATE SKIP LOCKED/.test(sql)&&/kind=\$4/.test(sql)));
});

test('daily content worker runs separate generation and judge operations with an injected provider', async () => {
  const fixture=JSON.parse(readFileSync(new URL('../../../shared/contracts/daily-practice/fixtures/day-en.json',import.meta.url),'utf8'));
  const content={topic:fixture.topic,objective:fixture.objective,expressions:fixture.expressions,
    listening:{transcript:fixture.listening.transcript,questions:fixture.listening.questions},speaking:fixture.speaking,
    goals:fixture.goals,practice:fixture.practice};
  const keys=(questions:any[])=>questions.map(q=>({questionId:q.id,...(q.kind==='single_choice'?{acceptedOptionId:q.options[0].id}:{acceptedAnswers:['a clear response']}),
    explanation:{en:'This answer is supported by the passage.',es:'Esta respuesta se apoya en el texto.'},targetExpressionIds:[fixture.expressions[0].id]}));
  const candidate={content,privatePayload:{listening:keys(fixture.listening.questions),practice:keys(fixture.practice.questions)}};
  const ids=[`topic:${content.topic.id}`,'speaking',...content.expressions.map((x:any)=>`expression:${x.id}`),
    ...content.listening.questions.map((x:any)=>`listening:${x.id}`),...content.practice.questions.map((x:any)=>`practice:${x.id}`),
    ...content.goals.map((x:any)=>`goal:${x.id}`)];
  const responses=[candidate,{decision:'pass',evidence:ids.map(id=>({id,verdict:'pass',rationale:'The item meets the required content criteria.'}))}];
  const statements:string[]=[];let responseIndex=0;let operationCount=0;
  const job:DailyJob={id:'11111111-1111-4111-8111-111111111111',kind:'content',binding_id:'22222222-2222-4222-8222-222222222222',
    language:'en',editorial_date:'2026-11-08',attempt_id:null,state:'running',lease_owner:'daily-worker-a',lease_until:new Date('2026-11-08T12:01:00Z'),
    config_snapshot:{schemaVersion:1,profileVersion:'en-v1',contentBudgetNanoUsd:'100',autoPublishEnabled:false}};
  const client={query:async(sql:string,values:unknown[]=[]):Promise<any>=>{
    statements.push(sql);
    if(sql==='BEGIN'||sql==='COMMIT'||sql==='ROLLBACK')return {rows:[],rowCount:0};
    if(sql.includes('FROM daily_jobs')&&sql.includes('FOR UPDATE'))return {rows:[job]};
    if(sql.includes('FROM daily_config'))return {rows:[{generation_enabled:true,enabled_languages:['en'],budget_enforced:true}]};
    if(sql.includes('FROM daily_cost_reservations')&&sql.includes('FOR UPDATE'))return {rows:[]};
    if(sql.includes('FROM daily_budget_limits'))return {rows:[{max_nano_usd:'100'}]};
    if(sql.includes('AS used'))return {rows:[{used:'0'}]};
    if(sql==='SELECT job_id FROM daily_provider_operations WHERE id=$1')return {rows:[{job_id:job.id}],rowCount:1};
    if(sql==='SELECT id FROM daily_jobs WHERE id=$1 FOR UPDATE')return {rows:[{id:job.id}],rowCount:1};
    if(sql.includes('FROM daily_provider_operations')&&sql.includes('FOR UPDATE'))return {rows:[]};
    if(sql.includes('INSERT INTO daily_provider_operations')){operationCount++;return {rows:[{id:values[0]}],rowCount:1};}
    if(sql.includes('RETURNING revision'))return {rows:[{revision:2}],rowCount:1};
    if(sql.includes('SELECT state,revision FROM daily_packages'))return {rows:[{state:'draft',revision:0}]};
    return {rows:[],rowCount:1};
  },release:()=>{}};
  const db={connect:async()=>client} as any;
  const result=await runDailyContentJob(db,{},job,'daily-worker-a',{now:()=>new Date('2026-11-08T12:00:00Z'),createProvider:async()=>({provider:'glm',model:'test',usage:()=>({state:'unknown',tokensIn:null,tokensOut:null}),
    complete:async(_messages:any,_signal:any,onUsage:any)=>{await onUsage({state:'unknown',tokensIn:null,tokensOut:null});const response=responses[responseIndex++]!;
      await onUsage({state:'complete',tokensIn:20,tokensOut:10});return {content:JSON.stringify(response),tokensIn:20,tokensOut:10};}})});
  assert.equal(result.state,'succeeded');
  assert.equal(responseIndex,2);
  assert.equal(operationCount,2);
  assert.equal(statements.filter(sql=>sql.includes('UPDATE daily_provider_operations SET usage=')).length,4);
});

test('daily review worker persists only validator-admitted feedback from the frozen evidence snapshot',async()=>{
  const attemptId='33333333-3333-4333-8333-333333333333',packageId='44444444-4444-4444-8444-444444444444';
  const stageState={stage:'finished',revision:7,activeMs:1000,completedStages:[],fragments:[],wordMarks:[],answers:{},sectionResults:{},helpUsed:{},speakingClosed:true,finished:true};
  const frozen={schemaVersion:1,attemptId,packageVersionId:packageId,stageState};
  const hash=hashDailyArtifact(frozen);
  const job:DailyJob={id:'11111111-1111-4111-8111-111111111111',kind:'review',binding_id:attemptId,language:'en',editorial_date:null,
    attempt_id:attemptId,state:'running',lease_owner:'daily-review-a',lease_until:new Date('2026-11-08T12:01:00Z'),config_snapshot:{rubricVersion:'daily-review-v1'}};
  const input={state:'running',lease_owner:'daily-review-a',lease_until:job.lease_until,review_state:'queued',
    review_reservation_id:'55555555-5555-4555-8555-555555555555',input_hash:hash,package_id:packageId,language:'en',explanation_language:'es',
    stage_state:stageState,snapshot:frozen,frozen_at:new Date('2026-11-08T11:59:00Z'),public_payload:{topic:{title:'Travel'}}};
  const statements:string[]=[];let inserted:any;let operationCount=0;let reviewUpdate:unknown[]=[];
  const client={query:async(sql:string,values:unknown[]=[]):Promise<any>=>{
    statements.push(sql);
    if(['BEGIN','COMMIT','ROLLBACK'].includes(sql))return {rows:[],rowCount:0};
    if(sql.includes('JOIN daily_attempts a')&&sql.includes('JOIN daily_review_inputs'))return {rows:[input],rowCount:1};
    if(sql.includes('SELECT state,lease_owner,lease_until FROM daily_jobs'))return {rows:[job],rowCount:1};
    if(sql==='SELECT job_id FROM daily_provider_operations WHERE id=$1')return {rows:[{job_id:job.id}],rowCount:1};
    if(sql==='SELECT id FROM daily_jobs WHERE id=$1 FOR UPDATE')return {rows:[{id:job.id}],rowCount:1};
    if(sql.includes('FROM daily_provider_operations')&&sql.includes('FOR UPDATE'))return {rows:[],rowCount:0};
    if(sql.includes('INSERT INTO daily_provider_operations')){operationCount++;return {rows:[{id:values[0]}],rowCount:1};}
    if(sql.includes('INSERT INTO daily_reviews')){inserted=JSON.parse(String(values[4]));return {rows:[],rowCount:1};}
    if(sql.includes('UPDATE daily_attempts SET review_id='))reviewUpdate=values;
    return {rows:[],rowCount:1};
  },release:()=>{}};
  const db={connect:async()=>client} as unknown as Database;
  const provider={provider:'glm' as const,model:'test',usage:()=>({state:'unknown' as const,tokensIn:null,tokensOut:null}),
    complete:async(_messages:any,_signal:any,onUsage:any)=>{await onUsage({state:'unknown',tokensIn:null,tokensOut:null});
      await onUsage({state:'complete',tokensIn:12,tokensOut:24});return {content:JSON.stringify({sections:['listening','speaking','practice'].map(stage=>({stage,evidence:'not_observed',
        correct:null,total:null,helped:false,explanation:'No evidence was observed for this section.'})),strengths:[],priorities:[],errors:[],wordCandidates:[]}),tokensIn:12,tokensOut:24};}};
  const result=await runDailyReviewJob(db,{},job,'daily-review-a',{now:()=>new Date('2026-11-08T12:00:00Z'),createProvider:async()=>provider});
  assert.equal(result.state,'succeeded');assert.equal(operationCount,1);assert.ok(inserted.reviewId);assert.equal(inserted.inputHash,hash);
  assert.ok(statements.some(sql=>sql.includes('UPDATE daily_attempts SET review_id=')));
  assert.equal(reviewUpdate[2],'partial');
});
