import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { test } from 'node:test';
import { runDailyContentQualityGate } from '../src/daily-generation.js';
import { normalizeDailyEditorialDate } from '../src/daily-workers.js';

const fixture = JSON.parse(await readFile(new URL('../../../shared/contracts/daily-practice/fixtures/day-en.json', import.meta.url), 'utf8'));
const content = { topic:fixture.topic, objective:fixture.objective, expressions:fixture.expressions,
  listening:{transcript:fixture.listening.transcript,questions:fixture.listening.questions}, speaking:fixture.speaking,
  goals:fixture.goals, practice:fixture.practice };
const keys = (questions: any[]) => questions.map((q:any) => ({questionId:q.id,
  ...(q.kind==='single_choice'?{acceptedOptionId:q.options[0].id}:{acceptedAnswers:['a clear answer']}),
  explanation:{en:'This answer follows the passage and topic.',es:'Esta respuesta sigue el texto y el tema.'},
  targetExpressionIds:[fixture.expressions[0].id]}));
const candidate = () => ({content:structuredClone(content),privatePayload:{listening:keys(fixture.listening.questions),practice:keys(fixture.practice.questions)}});

test('daily worker normalizes PostgreSQL editorial dates before reservation', () => {
  assert.equal(normalizeDailyEditorialDate(new Date('2026-11-10T00:00:00.000Z')), '2026-11-10');
  assert.equal(normalizeDailyEditorialDate('2026-11-10'), '2026-11-10');
});
const evidence = (verdict:'pass'|'fail'|'uncertain') => [`topic:${content.topic.id}`,...content.expressions.map((x:any)=>`expression:${x.id}`),
  ...content.listening.questions.map((x:any)=>`listening:${x.id}`),...content.practice.questions.map((x:any)=>`practice:${x.id}`),...content.goals.map((x:any)=>`goal:${x.id}`),'speaking']
  .map(id=>({id,verdict,rationale:'The content meets the specified language-learning criterion.'}));

 test('daily_quality_gate_binds_profile_hashes_and_runs_a_separate_judge', async()=>{
  const order:string[]=[]; let judgeInput:any;
  const result=await runDailyContentQualityGate({language:'en',editorialDate:'2026-11-08',packageVersionId:'11111111-1111-4111-8111-111111111111',
    generate:async()=>{order.push('generate');return candidate();},judge:async input=>{order.push('judge');judgeInput=input;return {decision:'pass',evidence:evidence('pass')};}});
  assert.deepEqual(order,['generate','judge']);
  assert.equal(result.state,'ready');
  assert.equal(result.publicPayload.listening.speechLocale,'en-US');
  assert.equal(result.publicPayload.editorialDate,'2026-11-08');
  assert.equal(result.publicPayload.contentHash,result.contentHash);
  assert.equal(judgeInput.textHash,result.textHash);
  assert.equal(judgeInput.artifactHash,result.artifactHash);
});

test('uncertain_or_failed_judgement_never_returns_a_ready_package',async()=>{
  for(const decision of ['fail','uncertain'] as const){
    const result=await runDailyContentQualityGate({language:'en',editorialDate:'2026-11-08',packageVersionId:'11111111-1111-4111-8111-111111111111',
      generate:async()=>candidate(),judge:async()=>({decision,evidence:evidence(decision)})});
    assert.notEqual(result.state,'ready');
    assert.equal(result.judgeState,decision==='fail'?'failed':'unknown');
  }
});

test('daily_quality_gate_rejects_mismatched_speech_locale_before_judging',async()=>{
  let judged=false;
  await assert.rejects(runDailyContentQualityGate({language:'en',editorialDate:'2026-11-08',packageVersionId:'11111111-1111-4111-8111-111111111111',
    generate:async()=>{const value=candidate();(value.content.listening as any).speechLocale='fr-FR';return value;},
    judge:async()=>{judged=true;return {decision:'pass',evidence:evidence('pass')};}}),/invalid_daily_content_candidate/);
  assert.equal(judged,false);
});


test('daily_quality_gate_requires_independent_evidence_for_all_content_ids', async()=>{
  const result=await runDailyContentQualityGate({language:'en',editorialDate:'2026-11-08',packageVersionId:'11111111-1111-4111-8111-111111111111',
    generate:async()=>candidate(),judge:async()=>({decision:'pass',evidence:[{id:`topic:${content.topic.id}`,verdict:'pass',rationale:'Only topic checked.'}]})});
  assert.notEqual(result.state,'ready');
  assert.equal(result.judgeState,'unknown');
});


test('daily_quality_gate_does_not_trust_a_pass_when_any_id_is_uncertain',async()=>{
  const result=await runDailyContentQualityGate({language:'en',editorialDate:'2026-11-08',packageVersionId:'11111111-1111-4111-8111-111111111111',
    generate:async()=>candidate(),judge:async()=>({decision:'pass',evidence:evidence('pass').map((item,index)=>index===0?{...item,verdict:'uncertain'}:item)})});
  assert.equal(result.state,'unknown');
  assert.equal(result.judgeState,'unknown');
});
