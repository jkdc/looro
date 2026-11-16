import assert from 'node:assert/strict';
import { test } from 'node:test';
import { validateDailyReview } from '../src/daily-review.js';

const binding={attemptId:'11111111-1111-4111-8111-111111111111',packageVersionId:'22222222-2222-4222-8222-222222222222',
  inputHash:'a'.repeat(64),language:'en' as const,explanationLanguage:'es' as const,rubricVersion:'daily-review-v1',createdAt:1};
const snapshot={fragments:[{id:'33333333-3333-4333-8333-333333333333',role:'learner' as const,text:'I go yesterday.',
  voiceSegmentId:'44444444-4444-4444-8444-444444444444',sequence:0,transcriptionUncertain:false}],
  wordMarks:[],answers:{listening:[{questionId:'listen_1',value:'option_a'}]},sectionResults:{listening:[{questionId:'listen_1',status:'incorrect'}]}};
const raw={sections:[
  {stage:'listening',evidence:'sufficient',correct:1,total:99,helped:false,explanation:'Comprende la idea, pero eligió otra opción.'},
  {stage:'speaking',evidence:'limited',correct:null,total:null,helped:false,explanation:'Se entiende la idea.'},
  {stage:'practice',evidence:'not_observed',correct:null,total:null,helped:false,explanation:'No hay respuestas observadas.'}],
  strengths:['La idea principal se entiende.'],priorities:['Practica el pasado simple.'],
  errors:[{kind:'grammar',quote:'I go yesterday.',correction:'I went yesterday.',explanation:'Use the past form.',fragmentIds:['33333333-3333-4333-8333-333333333333'],questionId:null,confidence:'supported'}],
  wordCandidates:[{kind:'word',text:'yesterday',meaning:'ayer',origin:'review',evidenceIds:['33333333-3333-4333-8333-333333333333']}]};

test('daily review discards fabricated quotes without losing grounded feedback',()=>{
  const reviewed=validateDailyReview({...raw,errors:[{...raw.errors[0],quote:'I went yesterday.'}]},binding,snapshot);
  assert.equal(reviewed.errors.length,0);
  assert.equal(reviewed.outcome,'partial');
});

test('daily review binds identities and derives stable word candidate identifiers',()=>{
  const first=validateDailyReview(raw,binding,snapshot);
  const second=validateDailyReview(raw,binding,snapshot);
  assert.deepEqual(first,second);
  assert.equal((first.errors[0] as any)?.quote,'I go yesterday.');
  assert.match(first.wordCandidates[0]!.candidateId,/^[a-f0-9-]{36}$/);
});

test('daily review recomputes closed exercise totals from frozen grading instead of trusting model counts',()=>{
  const reviewed=validateDailyReview(raw,binding,snapshot);
  assert.equal(reviewed.sections.find(section=>section.stage==='listening')?.correct,0);
  assert.equal(reviewed.sections.find(section=>section.stage==='listening')?.total,1);
});

test('one learner fragment is insufficient speaking evidence and makes the review partial',()=>{
  const reviewed=validateDailyReview({...raw,sections:raw.sections.map(section=>section.stage==='practice'?{...section,evidence:'sufficient',correct:1,total:1}:section)},binding,snapshot);
  assert.equal(reviewed.sections.find(section=>section.stage==='speaking')?.evidence,'not_observed');
  assert.equal(reviewed.outcome,'partial');
});

test('a candidate without any evidence is dropped without failing the whole review',()=>{
  const submitted=validateDailyReview({...raw,wordCandidates:[...raw.wordCandidates,
    {kind:'word',text:'fabricated',meaning:'inventada',origin:'review',evidenceIds:[]}]},binding,snapshot);
  assert.equal(submitted.wordCandidates.some(candidate=>candidate.text==='fabricated'),false);
  assert.equal(submitted.errors.length,1);
  assert.equal(submitted.wordCandidates.length,1);
});

test('an invented closed-answer error cannot authorize a word candidate',()=>{
  // The quote matches no submitted answer, so the validator drops the error;
  // the candidate citing that same question must then be dropped as well.
  const fabricated={...raw,errors:[{kind:'vocabulary',quote:'never submitted text',correction:'real fix',
    explanation:'e',fragmentIds:[],questionId:'listen_1',confidence:'supported'}],
    wordCandidates:[{kind:'word',text:'real fix',meaning:'arreglo',origin:'review',evidenceIds:['listen_1']}]};
  const reviewed=validateDailyReview(fabricated,binding,snapshot);
  assert.equal(reviewed.errors.length,0);
  assert.equal(reviewed.wordCandidates.length,0);
});

test('a correct closed answer cannot ground an error or a word candidate',()=>{
  const correctSnapshot={...snapshot,sectionResults:{listening:[{questionId:'listen_1',status:'correct'}]}};
  const reviewed=validateDailyReview({...raw,errors:[{kind:'vocabulary',quote:'option_a',correction:'right option',
    explanation:'e',fragmentIds:[],questionId:'listen_1',confidence:'supported'}],
    wordCandidates:[{kind:'word',text:'right option',meaning:'opción',origin:'review',evidenceIds:['listen_1']}]},
    binding,correctSnapshot);
  assert.equal(reviewed.errors.length,0);
  assert.equal(reviewed.wordCandidates.length,0);
});

test('a corrected form grounded on a failed question reaches Words',()=>{
  const reviewed=validateDailyReview({...raw,errors:[{kind:'vocabulary',quote:'option_a',correction:'went',
    explanation:'e',fragmentIds:[],questionId:'listen_1',confidence:'supported'}],
    wordCandidates:[{kind:'word',text:'went',meaning:'fui/fue',origin:'review',evidenceIds:['listen_1']}]},binding,snapshot);
  assert.equal(reviewed.errors.length,1);
  assert.equal(reviewed.wordCandidates.length,1);
  assert.equal(reviewed.wordCandidates[0]?.text,'went');
  assert.deepEqual(reviewed.wordCandidates[0]?.evidenceIds,['listen_1']);
});
