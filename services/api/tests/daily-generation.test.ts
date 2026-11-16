import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { test } from 'node:test';
import { connectDatabase } from '../src/db.js';
import { hashDailyArtifact } from '../src/daily-content.js';
import { validateDailyPublishCandidate } from '../src/daily-generation.js';

const dailyPackage = JSON.parse(await readFile(new URL('../../../shared/contracts/daily-practice/fixtures/day-en.json',import.meta.url),'utf8'));
const artifactHash = 'c'.repeat(64);
const answerKey = (question: any) => ({ questionId:question.id,
  ...(question.kind==='single_choice'?{acceptedOptionId:question.options[0].id}:{acceptedAnswers:['a clear answer']}),
  explanation:{en:'This answer follows the passage and topic.',es:'Esta respuesta sigue el texto y el tema.'},
  targetExpressionIds:[dailyPackage.expressions[0].id] });
const privatePayload = {listening:dailyPackage.listening.questions.map(answerKey),practice:dailyPackage.practice.questions.map(answerKey)};
const candidate = () => ({ state:'ready',revision:4,public_payload:dailyPackage,private_payload:privatePayload,content_hash:hashDailyArtifact(dailyPackage),artifact_hash:artifactHash,
  text_hash:'a'.repeat(64),judge_state:'passed',judge_text_hash:'a'.repeat(64),judge_artifact_hash:artifactHash });
const databaseURL=process.env.TEST_DATABASE_URL;

test('judge_failure_cannot_publish',()=>{
  const value=candidate(); value.judge_state='failed';
  assert.throws(()=>validateDailyPublishCandidate(value,artifactHash,4),/evidence_invalid/);
});
test('uncertain_language_cannot_publish',()=>{
  const value=candidate(); value.judge_state='unknown';
  assert.throws(()=>validateDailyPublishCandidate(value,artifactHash,4),/evidence_invalid/);
});
test('edited_key_invalidates_judge',()=>{
  const value=candidate(); value.artifact_hash='d'.repeat(64);
  assert.throws(()=>validateDailyPublishCandidate(value,artifactHash,4),/evidence_invalid/);
});
test('local_tts_package_is_publishable_without_audio_provider_evidence',()=>{
  const packageWithoutAudio = structuredClone(dailyPackage);
  delete packageWithoutAudio.listening.audio;
  packageWithoutAudio.listening.speechLocale = 'en-US';
  const value=candidate(); value.public_payload=packageWithoutAudio;
  value.content_hash=hashDailyArtifact(packageWithoutAudio);
  assert.doesNotThrow(()=>validateDailyPublishCandidate(value,artifactHash,4));
});
test('mismatched_judge_text_hash_cannot_publish_even_when_artifact_hash_matches',()=>{
  const value=candidate(); value.judge_text_hash='b'.repeat(64);
  assert.throws(()=>validateDailyPublishCandidate(value,artifactHash,4),/evidence_invalid/);
});
test('publication_requires_exact_content_hash_and_revision',()=>{
  const value=candidate();
  assert.throws(()=>validateDailyPublishCandidate(value,'d'.repeat(64),4),/evidence_invalid/);
  assert.throws(()=>validateDailyPublishCandidate(value,artifactHash,3),/revision_conflict/);
});

test('ready package judge text hash is immutable in PostgreSQL', {skip:!databaseURL&&'Set TEST_DATABASE_URL.'}, async()=>{
  const db=connectDatabase(databaseURL!); const id=randomUUID(); const textHash='a'.repeat(64);
  try {
    await db.query(`INSERT INTO daily_packages(id,language,editorial_date,schema_version,profile_version,state,public_payload,private_payload,
      content_hash,artifact_hash,text_hash,judge_text_hash) VALUES($1,'en','2026-11-07',1,'en-v1','draft',$2::jsonb,'{}'::jsonb,$3,$4,$5,$5)`,
      [id,JSON.stringify(dailyPackage),'b'.repeat(64),artifactHash,textHash]);
    await db.query("UPDATE daily_packages SET state='ready' WHERE id=$1",[id]);
    await assert.rejects(db.query("UPDATE daily_packages SET judge_text_hash=$2 WHERE id=$1",[id,'c'.repeat(64)]),/daily_package_judge_evidence_immutable/);
  } finally { await db.query('DELETE FROM daily_packages WHERE id=$1',[id]); await db.end(); }
});

test('invalid_daily_answer_key_binding_cannot_publish',()=>{
  const value=candidate();
  value.private_payload={listening:[],practice:[]};
  assert.throws(()=>validateDailyPublishCandidate(value,artifactHash,4),/invalid_daily_answer_keys/);
});
