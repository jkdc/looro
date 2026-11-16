import assert from 'node:assert/strict';
import { test } from 'node:test';
import { applyDailyEvent, encodeDailyAttemptCursor, getDailyReview, hasDailyReviewEvidence, parseDailyAttemptCursor, validateDailyEvent, type DailyAttemptState } from '../src/daily-attempts.js';

const initial:DailyAttemptState={stage:'preparation',revision:0,activeMs:0,completedStages:[],fragments:[],wordMarks:[],speakingClosed:false,finished:false};
const event=(value:Record<string,unknown>)=>({schemaVersion:1,eventId:'33333333-3333-4333-8333-333333333333',expectedRevision:0,event:value});

test('daily event rejects arbitrary fields and excessive UTF-8 payload without truncating',()=>{
  assert.throws(()=>validateDailyEvent({...event({type:'preparation_completed',activeMs:0}),accountId:'another-owner'}),/invalid/i);
  const huge=event({type:'word_marked',markId:'44444444-4444-4444-8444-444444444444',text:'x'.repeat(25_000),meaning:'',kind:'word',sourceQuestionId:null});
  assert.throws(()=>validateDailyEvent(huge),/too_large/i);
});

test('speaking_requires_listening_resolution_and_explicit_user_close',()=>{
  const prepared=applyDailyEvent(initial,validateDailyEvent(event({type:'preparation_completed',activeMs:2_000})).event);
  assert.throws(()=>applyDailyEvent(prepared,validateDailyEvent(event({type:'speaking_closed'})).event),/invalid_stage/i);
  const listening=applyDailyEvent(prepared,validateDailyEvent(event({type:'stage_skipped',stage:'listening',activeMs:0,reason:'learner_choice'})).event);
  const spoken=applyDailyEvent(listening,validateDailyEvent(event({type:'speaking_segment_saved',segmentId:'44444444-4444-4444-8444-444444444444',activeMs:1_000,helpUsed:[],fragments:[]})).event);
  const closed=applyDailyEvent(spoken,validateDailyEvent(event({type:'speaking_closed'})).event);
  assert.equal(closed.speakingClosed,true);
  assert.equal(closed.stage,'practice');
  assert.equal(closed.completedStages.find(stage=>stage.stage==='speaking')?.activeMs,1_000);
  assert.equal(closed.activeMs,3_000);
});

test('no daily event imposes a practice duration limit',()=>{
  const stage:DailyAttemptState={...initial,stage:'practice',completedStages:[{stage:'preparation',status:'completed',activeMs:0},{stage:'listening',status:'completed',activeMs:0},{stage:'speaking',status:'completed',activeMs:0}],speakingClosed:true};
  const updated=applyDailyEvent(stage,validateDailyEvent(event({type:'stage_skipped',stage:'practice',activeMs:86_400_000,reason:'learner_choice'})).event);
  assert.equal(updated.activeMs,86_400_000);
});

test('local_tts_unavailable_is_an_accepted_listening_skip',()=>{
  const prepared=applyDailyEvent(initial,validateDailyEvent(event({type:'preparation_completed',activeMs:0})).event);
  const skip=validateDailyEvent(event({type:'stage_skipped',stage:'listening',activeMs:0,reason:'local_tts_unavailable'}));
  const state=applyDailyEvent(prepared,skip.event);
  assert.equal(state.completedStages.at(-1)?.status,'skipped');
  assert.equal(state.stage,'speaking');
});

test('review dispatch requires learner evidence and excludes tutor-only speech',()=>{
  const tutorOnly:DailyAttemptState={...initial,fragments:[{id:'a',role:'tutor',text:'Try a longer sentence.',voiceSegmentId:'segment',sequence:0,transcriptionUncertain:false}]};
  assert.equal(hasDailyReviewEvidence(tutorOnly),false);
  const learnerSpeech:DailyAttemptState={...initial,fragments:[{id:'b',role:'learner',text:'I think this is useful.',voiceSegmentId:'segment',sequence:1,transcriptionUncertain:false}]};
  assert.equal(hasDailyReviewEvidence(learnerSpeech),true);
});

test('attempt history cursors are opaque and bound to the authenticated owner and language',()=>{
  const cursor=encodeDailyAttemptCursor('11111111-1111-4111-8111-111111111111','en','2026-11-07T10:00:00.000Z','22222222-2222-4222-8222-222222222222');
  assert.equal(typeof cursor,'string');
  assert.deepEqual(parseDailyAttemptCursor(cursor,'11111111-1111-4111-8111-111111111111','en'),
    { startedAt:'2026-11-07T10:00:00.000Z',attemptId:'22222222-2222-4222-8222-222222222222' });
  assert.throws(()=>parseDailyAttemptCursor(cursor,'33333333-3333-4333-8333-333333333333','en'),/invalid_daily_cursor/);
  assert.throws(()=>parseDailyAttemptCursor(cursor,'11111111-1111-4111-8111-111111111111','es'),/invalid_daily_cursor/);
});

test('review polling returns persisted partial results and does not trigger work',async()=>{
  const result={schemaVersion:1,outcome:'partial'};
  let calls=0;
  const db={query:async()=>{calls++;return {rows:[{review_state:'partial',result}],rowCount:1};}} as any;
  const read=await getDailyReview(db,'11111111-1111-4111-8111-111111111111','22222222-2222-4222-8222-222222222222');
  assert.deepEqual(read,{status:200,review:result});
  assert.equal(calls,1);
});


const databaseURL=process.env.TEST_DATABASE_URL;
test('creating an attempt without review budget fails closed with the contracted error and no side effects',async(t)=>{
  if(!databaseURL)t.skip('Set TEST_DATABASE_URL for PostgreSQL tests.');
  const {connectDatabase}=await import('../src/db.js');
  const {createDailyAttempt}=await import('../src/daily-attempts.js');
  const {ServiceError}=await import('../src/errors.js');
  const db=await connectDatabase(databaseURL!);
  const accountId=(await db.query<{id:string}>(`INSERT INTO accounts(id,email) VALUES(gen_random_uuid(),$1) RETURNING id`,
    [`budget-gate-${Date.now()}-${Math.floor(Math.random()*1e6)}@daily.test`])).rows[0]!.id;
  try{
    const editorialDate=new Date().toISOString().slice(0,10);
    await db.query(`UPDATE daily_config SET new_attempts_enabled=true,review_budget_nano_usd='0' WHERE singleton=true`);
    const packageId=(await db.query<{id:string}>(`SELECT package_id AS id FROM daily_publications WHERE language='en' AND editorial_date=$1`,[editorialDate])).rows[0]?.id
      ??(await db.query<{id:string}>(`INSERT INTO daily_packages(id,language,editorial_date,schema_version,profile_version,state,revision,public_payload,private_payload,content_hash,artifact_hash,text_hash,judge_state,judge_text_hash,judge_artifact_hash)
        VALUES(gen_random_uuid(),'en',$1,1,'en-v1','published',0,'{}'::jsonb,'{}'::jsonb,'${'a'.repeat(64)}','${'b'.repeat(64)}','${'c'.repeat(64)}','passed','${'c'.repeat(64)}','${'b'.repeat(64)}') RETURNING id`,
        [editorialDate])).rows[0]!.id;
    if((await db.query(`SELECT 1 FROM daily_publications WHERE language='en' AND editorial_date=$1`,[editorialDate])).rowCount===0)
      await db.query(`INSERT INTO daily_publications(language,editorial_date,package_id,revision) VALUES('en',$1,$2,1)`,[editorialDate,packageId]);
    const body={schemaVersion:1 as const,attemptId:'11111111-1111-4111-8111-111111111111',packageVersionId:packageId,
      explanationLanguage:'es' as const,dailyConsentVersion:1 as const};
    await t.test('rejection is the contracted ServiceError',async()=>{
      await assert.rejects(createDailyAttempt(db,accountId,body,`11111111-1111-4111-8111-111111111112`),(error:unknown)=>{
        assert.ok(error instanceof ServiceError,'expected a ServiceError, got a bare error mapped to HTTP 500');
        assert.equal(error.code,'daily_budget_unavailable');
        assert.equal(error.status,503);
        return true;
      });
    });
    await t.test('rejection leaves no attempt, reservation or consumed publication behind',async()=>{
      assert.equal((await db.query(`SELECT count(*)::int AS n FROM daily_attempts WHERE account_id=$1`,[accountId])).rows[0]!.n,0);
      assert.equal((await db.query(`SELECT count(*)::int AS n FROM daily_cost_reservations WHERE binding_id=$1`,
        ['11111111-1111-4111-8111-111111111111'])).rows[0]!.n,0);
      assert.equal((await db.query(`SELECT consumed_at FROM daily_publications WHERE language='en' AND editorial_date=$1`,
        [editorialDate])).rows[0]!.consumed_at,null);
    });
  }finally{
    await db.query(`DELETE FROM daily_attempt_request_keys WHERE account_id=$1`,[accountId]);
    await db.query(`DELETE FROM accounts WHERE id=$1`,[accountId]);
    await db.end();
  }
});
