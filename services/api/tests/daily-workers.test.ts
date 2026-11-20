import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { trace } from '@opentelemetry/api';
import { connectDatabase, type Database } from '../src/db.js';
import { ensureDailyPackage, type DailyJob } from '../src/daily-jobs.js';
import { LlmTransportError } from '../src/llm-config.js';
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

for(const mode of ['original','repair','released','cancel-before-send'] as const) test(`daily content worker traces separate operations; mode=${mode}`, async () => {
  const damaged=mode==='repair'||mode==='released';
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
  const question=structuredClone(candidate.content.listening.questions[0]);
  const answerKey=structuredClone(candidate.privatePayload.listening[0]);
  if(damaged)candidate.content.listening.questions[0].options.pop();
  const responses=[candidate,...(damaged?[{question,answerKey}]:[]),
    {decision:'pass',evidence:ids.map(id=>({id,verdict:'pass',rationale:'The item meets the required content criteria.'}))}];
  const keysSent: string[]=[];
  const events: {name:string;fields:Record<string,unknown>}[]=[];
  let root:Record<string,unknown>={};
  const controller=new AbortController();
  const budgetStates:unknown[]=[];
  trace.setGlobalTracerProvider({getTracer:()=>({startSpan:(_name:string,options:{attributes:Record<string,unknown>})=>{
    root={...options.attributes};
    return {end:()=>{},setAttribute:(key:string,value:unknown)=>{root[key]=value;},
      addEvent:(name:string,fields:Record<string,unknown>)=>events.push({name,fields})};
  }})} as never);
  const statements:string[]=[];let responseIndex=0;let operationCount=0;
  const job:DailyJob={id:'11111111-1111-4111-8111-111111111111',kind:'content',binding_id:'22222222-2222-4222-8222-222222222222',
    language:'en',editorial_date:'2026-11-08',attempt_id:null,state:'running',lease_owner:'daily-worker-a',lease_until:new Date('2026-11-08T12:01:00Z'),
    config_snapshot:{schemaVersion:1,profileVersion:'en-v1',contentBudgetNanoUsd:'100',autoPublishEnabled:false}};
  const client={query:async(sql:string,values:unknown[]=[]):Promise<any>=>{
    statements.push(sql);
    if(sql==='BEGIN'||sql==='COMMIT'||sql==='ROLLBACK')return {rows:[],rowCount:0};
    if(sql.includes('FROM daily_jobs')&&sql.includes('FOR UPDATE'))return {rows:[job]};
    if(sql.includes('FROM daily_config'))return {rows:[{generation_enabled:true,enabled_languages:['en'],budget_enforced:true}]};
    if(sql.includes('FROM daily_cost_reservations')&&sql.includes("state='held'")&&sql.includes('FOR UPDATE'))return {rows:mode==='released'?[]:[{id:values[0]}]};
    if(sql.includes('FROM daily_cost_reservations')&&sql.includes('FOR UPDATE'))return {rows:[]};
    if(sql.includes('UPDATE daily_cost_reservations SET state=$2'))budgetStates.push(values[1]);
    if(sql.includes('FROM daily_budget_limits'))return {rows:[{max_nano_usd:'100'}]};
    if(sql.includes('AS used'))return {rows:[{used:'0'}]};
    if(sql==='SELECT job_id FROM daily_provider_operations WHERE id=$1')return {rows:[{job_id:job.id}],rowCount:1};
    if(sql==='SELECT id FROM daily_jobs WHERE id=$1 FOR UPDATE')return {rows:[{id:job.id}],rowCount:1};
    if(sql.includes('FROM daily_provider_operations')&&sql.includes('FOR UPDATE'))return {rows:[]};
    if(sql.includes('INSERT INTO daily_provider_operations')){operationCount++;keysSent.push(String(values[3]));return {rows:[{id:values[0]}],rowCount:1};}
    if(sql.includes('RETURNING revision'))return {rows:[{revision:2}],rowCount:1};
    if(sql.includes('SELECT state,revision FROM daily_packages'))return {rows:[{state:'draft',revision:0}]};
    return {rows:[],rowCount:1};
  },release:()=>{}};
  const db={connect:async()=>client} as any;
  try {
    let clock = Date.parse('2026-11-08T12:00:00Z');
    const result=await runDailyContentJob(db,{},Object.assign(job,{created_at:new Date(clock-60_000),updated_at:new Date(clock-5_000)}),
      'daily-worker-a',{now:()=>new Date(clock),signal:controller.signal,createProvider:async()=>{if(mode==='cancel-before-send')controller.abort();return {provider:'glm',model:'test',usage:()=>({state:'unknown',tokensIn:null,tokensOut:null}),
      complete:async(messages,_signal,onUsage)=>{await onUsage?.({state:'unknown',tokensIn:null,tokensOut:null});
        const response=responses[responseIndex++]!;
        if(responseIndex===responses.length){
          const judged=JSON.parse(messages[1]!.content);
          assert.deepEqual(judged.publicPayload.listening.questions[0],question);
          assert.deepEqual(judged.privatePayload.listening[0],answerKey);
        }
        clock+=1_000; await onUsage?.({state:'complete',tokensIn:20,tokensOut:10});
        return {content:JSON.stringify(response),tokensIn:20,tokensOut:10,finishReason:'stop'};}};}});
    if(mode==='cancel-before-send'){
      assert.equal(result.state,'cancelled');assert.equal(operationCount,0);assert.equal(responseIndex,0);
      assert.deepEqual(budgetStates,['released']);return;
    }
    if(mode==='released'){
      assert.equal(result.state,'failed');assert.equal(responseIndex,1);
      assert.equal(result.errorCode,'daily_repair_reservation_unavailable');return;
    }
    assert.equal(result.state,'succeeded');
    assert.equal(responseIndex,responses.length);
    assert.equal(operationCount,responses.length);
    assert.equal(statements.filter(sql=>sql.includes('UPDATE daily_provider_operations SET usage=')).length,2*responses.length);
    assert.deepEqual(keysSent, [`${job.id}:generate:v1`,...(damaged?[`${job.id}:generate:repair:v1`]:[]),`${job.id}:judge:v1`]);
    assert.equal(root['daily.job_id'],job.id);
    assert.equal(root['daily.queue_ms'],55_000);
    assert.equal(root['daily.outcome'],'ready');
    assert.equal(events.find(event=>event.name==='generate.provider')?.fields.duration_ms,1_000);
    assert.equal(events.find(event=>event.name==='generate.provider')?.fields.finish_reason,'stop');
    assert.doesNotMatch(JSON.stringify(events),/This answer is supported/);
  } finally { trace.disable(); }
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
  const oldInfo=console.info, logLines:string[]=[];
  console.info=line=>{logLines.push(String(line));};
  let result:Awaited<ReturnType<typeof runDailyReviewJob>>;
  try{result=await runDailyReviewJob(db,{},job,'daily-review-a',{now:()=>new Date('2026-11-08T12:00:00Z'),createProvider:async()=>provider});}
  finally{console.info=oldInfo;}
  assert.match(logLines.join('\n'),/daily_provider_phase_dispatch/);
  assert.match(logLines.join('\n'),/daily_provider_phase_completed/);
  assert.equal(result.state,'succeeded');assert.equal(operationCount,1);assert.ok(inserted.reviewId);assert.equal(inserted.inputHash,hash);
  assert.ok(statements.some(sql=>sql.includes('UPDATE daily_attempts SET review_id=')));
  assert.equal(reviewUpdate[2],'partial');
});

const databaseURL=process.env.TEST_DATABASE_URL;
for(const mode of ['ready','judge-rejected','repair-unknown','released'] as const) test(`daily repair has durable accounting and keeps publication gated: ${mode}`,{skip:!databaseURL},async()=>{
  const db=connectDatabase(databaseURL!);
  const day={ready:'2032-01-04','judge-rejected':'2032-01-05','repair-unknown':'2032-01-06',released:'2032-01-07'}[mode];
  const previous=(await db.query('SELECT generation_enabled,auto_publish_enabled,budget_enforced,content_budget_nano_usd,enabled_languages FROM daily_config')).rows[0]!;
  let jobId:string|undefined;
  try{
    await db.query("UPDATE daily_config SET generation_enabled=true,auto_publish_enabled=false,budget_enforced=true,content_budget_nano_usd=100,enabled_languages=ARRAY['en']");
    jobId=await ensureDailyPackage(db,'en',day);
    const fixture=JSON.parse(readFileSync(new URL('../../../shared/contracts/daily-practice/fixtures/day-en.json',import.meta.url),'utf8'));
    const {topic,objective,expressions,listening,speaking,goals,practice}=fixture;
    const keys=(items:any[])=>items.map(q=>({questionId:q.id,...(q.kind==='single_choice'?{acceptedOptionId:q.options[0].id}:{acceptedAnswers:['a valid answer']}),
      explanation:{en:'A short explanation.',es:'Una explicación breve.'},targetExpressionIds:[expressions[0].id]}));
    const content={topic,objective,expressions,listening:{transcript:listening.transcript,questions:listening.questions},speaking,goals,practice};
    const privatePayload={listening:keys(listening.questions),practice:keys(practice.questions)};
    const original=structuredClone(content),answerKey=structuredClone(privatePayload.listening[0]);
    content.listening.questions[0].options.pop();
    let calls=0;
    const result=await runDailyContentWorkerOnce(db,{},'daily-repair-pg',{
      createProvider:async()=>({provider:'glm',model:'fixture',usage:()=>({state:'unknown',tokensIn:null,tokensOut:null}),
        async complete(messages,_signal,checkpoint){
          await checkpoint?.({state:'unknown',tokensIn:null,tokensOut:null});
          calls++;
          if(calls===2&&mode==='repair-unknown')throw new LlmTransportError('llm_outcome_unknown');
          await checkpoint?.({state:'complete',tokensIn:20,tokensOut:5});
          if(calls===1&&mode==='released')await db.query("UPDATE daily_cost_reservations SET state='released' WHERE job_id=$1",[jobId]);
          const judgeInput=calls===3?JSON.parse(messages[1]!.content):undefined;
          const decision=mode==='judge-rejected'?'fail':'pass';
          const value=calls===1?{content,privatePayload}:calls===2?{question:original.listening.questions[0],answerKey}:
            {decision,evidence:judgeInput.requiredEvidenceIds.map((id:string)=>({id,verdict:decision,rationale:'Synthetic evidence for the integration fixture.'}))};
          if(judgeInput){
            assert.deepEqual(judgeInput.publicPayload.listening.questions,original.listening.questions);
            assert.deepEqual(judgeInput.privatePayload,privatePayload);
            assert.equal(hashDailyArtifact(judgeInput.privatePayload),judgeInput.artifactHash);
          }
          return {content:JSON.stringify(value),tokensIn:20,tokensOut:5};
        }}),
    });
    assert.equal(result?.jobId,jobId);
    assert.equal(result?.state,{ready:'succeeded','judge-rejected':'failed','repair-unknown':'unknown',released:'failed'}[mode]);
    assert.equal(calls,mode==='released'?1:mode==='repair-unknown'?2:3);
    const ops=(await db.query('SELECT phase,dispatch_key,reservation_id,state,usage FROM daily_provider_operations WHERE job_id=$1 ORDER BY sent_at,dispatch_key',[jobId])).rows;
    const expected=[`${jobId}:generate:v1`,`${jobId}:generate:repair:v1`,...(mode==='repair-unknown'||mode==='released'?[]:[`${jobId}:judge:v1`])];
    assert.deepEqual(ops.map(row=>row.dispatch_key).sort(),expected.sort());
    assert.equal(new Set(ops.map(row=>row.reservation_id)).size,1);
    const repair=ops.find(row=>row.dispatch_key.endsWith(':repair:v1'));
    assert.equal(repair?.state,mode==='released'?'reserved':mode==='repair-unknown'?'unknown':'succeeded');
    assert.equal(repair?.usage?.state,mode==='released'?undefined:mode==='repair-unknown'?'unknown':'complete');
    const saved=(await db.query('SELECT state FROM daily_packages WHERE id=$1',[result?.packageVersionId])).rows[0];
    assert.equal(saved?.state,{ready:'ready','judge-rejected':'rejected','repair-unknown':'draft',released:'draft'}[mode]);
    assert.equal((await db.query('SELECT 1 FROM daily_publications WHERE editorial_date=$1',[day])).rowCount,0);
  }finally{
    if(jobId){
      await db.query('DELETE FROM daily_provider_operations WHERE job_id=$1',[jobId]);
      await db.query('DELETE FROM daily_cost_reservations WHERE job_id=$1',[jobId]);
      await db.query("DELETE FROM daily_packages WHERE editorial_date=$1 AND language='en'",[day]);
      await db.query('DELETE FROM daily_jobs WHERE id=$1',[jobId]);
      await db.query('DELETE FROM daily_budget_limits WHERE budget_date=$1',[day]);
    }
    await db.query('UPDATE daily_config SET generation_enabled=$1,auto_publish_enabled=$2,budget_enforced=$3,content_budget_nano_usd=$4,enabled_languages=$5',
      [previous.generation_enabled,previous.auto_publish_enabled,previous.budget_enforced,previous.content_budget_nano_usd,previous.enabled_languages]);
    await db.end();
  }
});

test('daily provider signal aborts when heartbeat can no longer renew the content lease', async()=>{
  const job:DailyJob={id:'11111111-1111-4111-8111-111111111111',kind:'content',binding_id:'22222222-2222-4222-8222-222222222222',
    language:'en',editorial_date:'2026-11-08',attempt_id:null,state:'running',lease_owner:'daily-worker-a',
    lease_until:new Date('2026-11-08T12:01:00Z'),config_snapshot:{profileVersion:'en-v1',autoPublishEnabled:true}};
  let called=0, renewals=0, publication=0, signalled=false;
  const client={release(){},async query(sql:string){
    if(sql.includes('SET lease_until=')){renewals++;return {rows:[],rowCount:0};}
    if(sql.includes('state=CASE'))return {rows:[],rowCount:0};
    if(sql.includes('FOR UPDATE SKIP LOCKED')||sql.includes('FROM daily_jobs')&&sql.includes('FOR UPDATE'))
      return {rows:[job],rowCount:1};
    if(sql.includes('FROM daily_config'))return {rows:[{generation_enabled:true,enabled_languages:['en'],budget_enforced:false}]};
    if(sql.includes('SELECT state,revision FROM daily_packages'))return {rows:[{state:'draft',revision:0}]};
    if(sql==='SELECT job_id FROM daily_provider_operations WHERE id=$1')return {rows:[{job_id:job.id}]};
    if(sql.includes('daily_publications'))publication++;
    return {rows:[],rowCount:1};
  }};
  const result=await runDailyContentWorkerOnce({connect:async()=>client} as never,{},'daily-worker-a',{
    now:()=>new Date('2026-11-08T12:00:00Z'),leaseMs:1000,
    createProvider:async()=>({provider:'glm',model:'lease-test',usage:()=>({state:'unknown',tokensIn:null,tokensOut:null}),
      async complete(_messages,signal,checkpoint){
        called++;
        await checkpoint?.({state:'unknown',tokensIn:null,tokensOut:null});
        await new Promise<void>((resolve,reject)=>{
          const timer=setTimeout(()=>reject(new Error('abort_not_propagated')),1_500);
          const onAbort=()=>{signalled=true;clearTimeout(timer);resolve();};
          if(signal?.aborted)onAbort();else signal?.addEventListener('abort',onAbort,{once:true});
        });
        throw new LlmTransportError('llm_outcome_unknown');
      }}),
  });
  assert.equal(called,1);
  assert.equal(renewals,1);
  assert.equal(signalled,true);
  assert.equal(result?.state,'unknown');
  assert.equal(publication,0);
});
