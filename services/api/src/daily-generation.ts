import type { PoolClient } from 'pg';
import { DailyQuestionValidationError, hashDailyArtifact, hashDailyText, validateDailyPackage, type DailyQuestion } from './daily-content.js';
import { resolveDailyLanguageProfile } from './daily-language-profiles.js';

export type DailyPublishCandidate = { state: string; revision: number|string; public_payload: unknown; private_payload: unknown;
  content_hash: string|null; text_hash: string|null; artifact_hash: string|null; judge_state: string; judge_text_hash: string|null; judge_artifact_hash: string|null;
};

export function validateDailyPublishCandidate(candidate: DailyPublishCandidate, artifactHash: string, expectedRevision: number): void {
  if (!Number.isSafeInteger(expectedRevision) || Number(candidate.revision) !== expectedRevision) throw new Error('daily_revision_conflict');
  if (candidate.state !== 'ready' || !/^[0-9a-f]{64}$/.test(artifactHash) || candidate.artifact_hash !== artifactHash ||
      candidate.judge_state !== 'passed' || !/^[0-9a-f]{64}$/.test(candidate.text_hash ?? '') ||
      candidate.judge_text_hash !== candidate.text_hash || candidate.judge_artifact_hash !== artifactHash)
    throw new Error('daily_publication_evidence_invalid');
  const dailyPackage = validateDailyPackage(candidate.public_payload);
  validateDailyAnswerKeys(dailyPackage, candidate.private_payload);
  if (dailyPackage.contentHash !== candidate.content_hash || hashDailyArtifact(candidate.public_payload) !== candidate.content_hash)
    throw new Error('daily_publication_hash_mismatch');
}

export async function publishDailyPackage(tx: PoolClient, packageVersionId: string, artifactHash: string,
  expectedRevision: number): Promise<{ language: string; editorialDate: string; packageVersionId: string; revision: number }> {
  const packageRow = (await tx.query<DailyPublishCandidate & {id:string; language:string; editorial_date:string}>(
    `SELECT id,language,editorial_date,state,revision,public_payload,private_payload,content_hash,text_hash,artifact_hash,judge_state,judge_text_hash,judge_artifact_hash
      FROM daily_packages WHERE id=$1 FOR UPDATE`, [packageVersionId])).rows[0];
  if (!packageRow) throw new Error('daily_package_not_found');
  validateDailyPublishCandidate(packageRow, artifactHash, expectedRevision);
  const publication = (await tx.query<{package_id:string;revision:number;consumed_at:Date|null;withdrawn_at:Date|null}>(
    'SELECT package_id,revision,consumed_at,withdrawn_at FROM daily_publications WHERE language=$1 AND editorial_date=$2 FOR UPDATE',
    [packageRow.language,packageRow.editorial_date])).rows[0];
  if (publication?.withdrawn_at) throw new Error('daily_publication_withdrawn');
  if (publication?.consumed_at && publication.package_id !== packageVersionId) throw new Error('daily_publication_consumed');
  if (publication?.package_id === packageVersionId) return { language:packageRow.language, editorialDate:packageRow.editorial_date,
    packageVersionId, revision:Number(publication.revision) };
  const revision = (publication?.revision ?? 0) + 1;
  if (publication) await tx.query(`UPDATE daily_publications SET package_id=$3,revision=$4,published_at=now(),withdrawn_at=NULL
    WHERE language=$1 AND editorial_date=$2`, [packageRow.language,packageRow.editorial_date,packageVersionId,revision]);
  else await tx.query(`INSERT INTO daily_publications(language,editorial_date,package_id,revision)
    VALUES($1,$2,$3,$4)`, [packageRow.language,packageRow.editorial_date,packageVersionId,revision]);
  await tx.query("UPDATE daily_packages SET state='published',revision=revision+1 WHERE id=$1 AND state='ready'", [packageVersionId]);
  return { language:packageRow.language, editorialDate:packageRow.editorial_date, packageVersionId, revision };
}

/** Validates the private answer-key projection against the public package before it can be judged. */
export function validateDailyAnswerKeys(publicValue: unknown, privateValue: unknown): unknown {
  const object = (value: unknown): value is Record<string, any> => !!value && typeof value === 'object' && !Array.isArray(value);
  const text = (value: unknown, max: number) => typeof value === 'string' && value.trim().length > 0 &&
    [...value].length <= max && !/[\u0000-\u001f\u007f]/u.test(value);
  const invalid = (): never => { throw new Error('invalid_daily_answer_keys'); };
  if (!object(publicValue) || !object(privateValue) || Object.keys(privateValue).length !== 2 ||
      !Array.isArray(publicValue.expressions) || !Array.isArray(publicValue.listening?.questions) ||
      !Array.isArray(publicValue.practice?.questions) || !Array.isArray(privateValue.listening) || !Array.isArray(privateValue.practice))
    return invalid();
  const expressionIds = new Set(publicValue.expressions.map((entry: any) => entry?.id).filter((id: unknown) => typeof id === 'string'));
  const questionGroups = [['listening', publicValue.listening.questions], ['practice', publicValue.practice.questions]] as const;
  const seen = new Set<string>();
  for (const [group, questions] of questionGroups) {
    const keys = privateValue[group];
    if (keys.length !== questions.length) return invalid();
    for (const [position,question] of questions.entries()) {
      if (!object(question) || typeof question.id !== 'string' || seen.has(question.id)) return invalid();
      seen.add(question.id);
      const key = keys.find((candidate: unknown) => object(candidate) && candidate.questionId === question.id);
      const invalidKey=():never=>{throw new DailyAnswerKeyValidationError(group,position);};
      if (!object(key) || !object(key.explanation) || Object.keys(key.explanation).length !== 2 ||
          !text(key.explanation.en, 500) || !text(key.explanation.es, 500) ||
          !Array.isArray(key.targetExpressionIds) || key.targetExpressionIds.length > expressionIds.size ||
          key.targetExpressionIds.some((id: unknown) => typeof id !== 'string' || !expressionIds.has(id)) ||
          new Set(key.targetExpressionIds).size !== key.targetExpressionIds.length)
        return invalidKey();
      if (question.kind === 'single_choice') {
        if (Object.keys(key).length !== 4 || typeof key.acceptedOptionId !== 'string' ||
            !question.options.some((option: any) => option?.id === key.acceptedOptionId) || Object.hasOwn(key, 'acceptedAnswers'))
          return invalidKey();
      } else if (question.kind === 'gap_fill') {
        if (Object.keys(key).length !== 4 || !Array.isArray(key.acceptedAnswers) || key.acceptedAnswers.length < 1 ||
            key.acceptedAnswers.length > 8 || key.acceptedAnswers.some((answer: unknown) => !text(answer, 120)) ||
            Object.hasOwn(key, 'acceptedOptionId')) return invalidKey();
      } else return invalid();
    }
  }
  return privateValue;
}

class DailyAnswerKeyValidationError extends Error {
  constructor(public section:'listening'|'practice',public position:number){super('invalid_daily_answer_keys');}
}
export type DailyQuestionRepairTarget = {
  section:'listening'|'practice'; questionId:string; kind:DailyQuestion['kind'];
  reason:'question_invalid'|'answer_key_invalid'; topic:string; transcript?:string;
  expressions: {id:string;text:string}[];
};
const repairId=/^[a-z0-9_-]{1,64}$/;

function repairTarget(candidate:unknown,error:unknown):{target:DailyQuestionRepairTarget;position:number;keyIndex:number}|undefined {
  if(!(error instanceof DailyQuestionValidationError||error instanceof DailyAnswerKeyValidationError)||!record(candidate)||
    !record(candidate.content)||!record(candidate.privatePayload))return;
  const {section,position}=error;
  const content=candidate.content, privatePayload=candidate.privatePayload;
  if(!record(content.listening)||!record(content.practice)||!record(content.topic)||!Array.isArray(content.expressions)||
    !Array.isArray(content.listening.questions)||!Array.isArray(content.practice.questions)||
    Object.keys(privatePayload).length!==2||!Array.isArray(privatePayload.listening)||!Array.isArray(privatePayload.practice))return;
  const allIds=new Set<string>();
  for(const group of ['listening','practice'] as const){
    if(privatePayload[group].length!==content[group].questions.length)return;
    for(const q of content[group].questions){
      if(!record(q)||typeof q.id!=='string'||!repairId.test(q.id)||allIds.has(q.id)||
        privatePayload[group].filter((key:unknown)=>record(key)&&key.questionId===q.id).length!==1)return;
      allIds.add(q.id);
    }
  }
  const questions=content[section].questions, keys=privatePayload[section];
  const question=questions[position];
  if(!record(question)||typeof question.id!=='string'||!repairId.test(question.id)||
    (question.kind!=='single_choice'&&question.kind!=='gap_fill')||keys.length!==questions.length)return;
  const keyIndex=keys.findIndex((k:unknown)=>record(k)&&k.questionId===question.id);
  if(typeof content.topic.title!=='string'||[...content.topic.title].length>120||
    (section==='listening'&&(typeof content.listening.transcript!=='string'||[...content.listening.transcript].length>1500))||
    content.expressions.length!==5||!content.expressions.every(entry=>record(entry)&&typeof entry.id==='string'&&repairId.test(entry.id)&&
      typeof entry.text==='string'&&[...entry.text].length<=120))return;
  return {position,keyIndex,target:{section,questionId:question.id,kind:question.kind,
    reason:error instanceof DailyQuestionValidationError?'question_invalid':'answer_key_invalid',
    topic:content.topic.title,...(section==='listening'?{transcript:content.listening.transcript}:{}),
    expressions:content.expressions.map(entry=>({id:entry.id,text:entry.text}))}};
}

export type DailyJudgeDecision = 'pass'|'fail'|'uncertain';
export type DailyQualityGateResult = {
  state:'ready'|'rejected'|'unknown'; judgeState:'passed'|'failed'|'unknown';
  publicPayload:ReturnType<typeof validateDailyPackage>; privatePayload:unknown;
  contentHash:string; artifactHash:string; textHash:string; judgeEvidence:unknown[];
};
type DailyContentQualityGateInput = {
  language:string; editorialDate:string; packageVersionId:string;
  generate:()=>Promise<unknown>;
  repair?:(target:DailyQuestionRepairTarget)=>Promise<unknown>;
  onValidation?:(outcome:'accepted'|'repairable'|'invalid',reason?:string)=>void;
  judge:(input:{publicPayload:unknown;privatePayload:unknown;contentHash:string;artifactHash:string;textHash:string;requiredEvidenceIds?:string[]})=>Promise<{decision:DailyJudgeDecision;evidence:unknown[]}>;
};
const uuidPattern=/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const record=(v:unknown):v is Record<string,any>=>!!v&&typeof v==='object'&&!Array.isArray(v);

/** Builds server-owned package bindings, validates private keys, then invokes an independent judge. */
export async function runDailyContentQualityGate(input:DailyContentQualityGateInput):Promise<DailyQualityGateResult> {
  const profile=resolveDailyLanguageProfile(input.language);
  const date=new Date(`${input.editorialDate}T00:00:00Z`);
  if(!profile?.enabled||!uuidPattern.test(input.packageVersionId)||!/^\d{4}-\d{2}-\d{2}$/.test(input.editorialDate)||
    !Number.isFinite(date.getTime())||date.toISOString().slice(0,10)!==input.editorialDate)
    throw new Error('invalid_daily_content_binding');
  const validateCandidate=(candidate:unknown)=>{
    if(!record(candidate)||Object.keys(candidate).length!==2||!Object.hasOwn(candidate,'content')||!Object.hasOwn(candidate,'privatePayload')||
      !record(candidate.content)||Object.keys(candidate.content).length!==7||
      !['topic','objective','expressions','listening','speaking','goals','practice'].every(key=>Object.hasOwn(candidate.content,key))||
      !record(candidate.content.listening)||Object.keys(candidate.content.listening).length!==2||
      !Object.hasOwn(candidate.content.listening,'transcript')||!Object.hasOwn(candidate.content.listening,'questions')) {
      throw new Error('invalid_daily_content_candidate');
    }
    const base={schemaVersion:1 as const,packageVersionId:input.packageVersionId,contentHash:'0'.repeat(64),editorialDate:input.editorialDate,
      language:profile.language,profileVersion:profile.profileVersion,topic:candidate.content.topic,objective:candidate.content.objective,
      challengeReference:profile.challengeReference,estimatedMinutes:{preparation:2 as const,listening:4 as const,speaking:6 as const,
        practice:3 as const,review:2 as const,total:17 as const},expressions:candidate.content.expressions,
      listening:{speechLocale:profile.speechLocale,transcript:candidate.content.listening.transcript,questions:candidate.content.listening.questions},
      speaking:candidate.content.speaking,goals:candidate.content.goals,practice:candidate.content.practice,
      supportLocales:[...profile.supportLocales] as ('en'|'es')[]};
    const contentHash=hashDailyArtifact(base);
    const publicPayload=validateDailyPackage({...base,contentHash});
    const privatePayload=validateDailyAnswerKeys(publicPayload,candidate.privatePayload);
    return {contentHash,publicPayload,privatePayload};
  };
  const candidate=await input.generate();
  let validated:ReturnType<typeof validateCandidate>;
  try{
    validated=validateCandidate(candidate);
  }catch(error){
    const repair=input.repair&&repairTarget(candidate,error);
    if(!repair||!input.repair){input.onValidation?.('invalid');throw error;}
    const {target,position,keyIndex}=repair;
    input.onValidation?.('repairable',target.reason);
    const replacement=await input.repair(target);
    if(!record(replacement)||Object.keys(replacement).length!==2||
      !record(replacement.question)||!record(replacement.answerKey)||
      replacement.question.id!==target.questionId||replacement.question.kind!==target.kind||
      replacement.answerKey.questionId!==target.questionId)throw new Error('invalid_daily_question_repair');
    if(!record(candidate))throw new Error('invalid_daily_question_repair');
    const merged=structuredClone(candidate);
    merged.content[target.section].questions[position]=replacement.question;
    merged.privatePayload[target.section][keyIndex]=replacement.answerKey;
    try{validated=validateCandidate(merged);}
    catch(error){input.onValidation?.('invalid');throw error;}
  }
  input.onValidation?.('accepted');
  const {contentHash,publicPayload,privatePayload}=validated;
  const artifactHash=hashDailyArtifact(privatePayload);
  const publicText={...publicPayload} as Record<string,unknown>;delete publicText.contentHash;
  const textHash=hashDailyArtifact({publicPayload:publicText,privatePayload});
  const requiredEvidenceIds=[`topic:${publicPayload.topic.id}`,`speaking`,
    ...publicPayload.expressions.map(item=>`expression:${item.id}`),
    ...publicPayload.listening.questions.map(item=>`listening:${item.id}`),
    ...publicPayload.practice.questions.map(item=>`practice:${item.id}`),
    ...publicPayload.goals.map(item=>`goal:${item.id}`)];
  const judgement=await input.judge({publicPayload,privatePayload,contentHash,artifactHash,textHash,requiredEvidenceIds});
  if(!record(judgement)||!['pass','fail','uncertain'].includes(judgement.decision)||!Array.isArray(judgement.evidence)||judgement.evidence.length>128)
    throw new Error('invalid_daily_judge_result');
  const expectedEvidenceIds=new Set(requiredEvidenceIds);
  const receivedEvidence=new Map<string,DailyJudgeDecision>();
  for(const evidence of judgement.evidence) {
    if(!record(evidence)||Object.keys(evidence).length!==3||typeof evidence.id!=='string'||
      !['pass','fail','uncertain'].includes(evidence.verdict)||typeof evidence.rationale!=='string'||
      !evidence.rationale.trim()||[...evidence.rationale].length>500||receivedEvidence.has(evidence.id))
      return {state:'unknown',judgeState:'unknown',publicPayload,privatePayload,contentHash,artifactHash,textHash,judgeEvidence:[]};
    receivedEvidence.set(evidence.id,evidence.verdict as DailyJudgeDecision);
  }
  if(receivedEvidence.size!==expectedEvidenceIds.size||[...expectedEvidenceIds].some(id=>!receivedEvidence.has(id)))
    return {state:'unknown',judgeState:'unknown',publicPayload,privatePayload,contentHash,artifactHash,textHash,judgeEvidence:judgement.evidence};
  const decision=
    [...receivedEvidence.values()].includes('fail')?'fail':
    [...receivedEvidence.values()].includes('uncertain')?'uncertain':'pass';
  if(decision!==judgement.decision)
    return {state:'unknown',judgeState:'unknown',publicPayload,privatePayload,contentHash,artifactHash,textHash,judgeEvidence:judgement.evidence};
  return {state:decision==='pass'?'ready':decision==='fail'?'rejected':'unknown',
    judgeState:decision==='pass'?'passed':decision==='fail'?'failed':'unknown',publicPayload,privatePayload,contentHash,artifactHash,textHash,
    judgeEvidence:judgement.evidence};
}
