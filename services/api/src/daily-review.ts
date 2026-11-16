import { createHash } from 'node:crypto';
import { ServiceError } from './errors.js';
import type { DailyAttemptState, DailyFragment, DailyWordMark } from './daily-attempts.js';

export type DailyReviewBinding={attemptId:string;packageVersionId:string;inputHash:string;language:'en';explanationLanguage:'en'|'es';
  rubricVersion:string;createdAt:number};
type ReviewEvidenceSnapshot=Pick<DailyAttemptState,'fragments'|'wordMarks'|'answers'|'sectionResults'|'helpUsed'>;
const uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const record=(v:unknown):v is Record<string,any>=>!!v&&typeof v==='object'&&!Array.isArray(v);
const clean=(v:unknown,max:number)=>typeof v==='string'&&v.trim().length>0&&[...v].length<=max&&!/[\u0000-\u001f\u007f]/u.test(v);
function stableId(seed:string):string {
  const bytes=createHash('sha256').update(seed,'utf8').digest().subarray(0,16);
  bytes[6]=(bytes[6]!&0x0f)|0x50;bytes[8]=(bytes[8]!&0x3f)|0x80;
  const h=bytes.toString('hex');return `${h.slice(0,8)}-${h.slice(8,12)}-${h.slice(12,16)}-${h.slice(16,20)}-${h.slice(20)}`;
}
function answerEvidence(snapshot:ReviewEvidenceSnapshot,questionId:string,quote:string):boolean {
  const answerStages=snapshot.answers??{};
  const resultStages=snapshot.sectionResults??{};
  return ['listening','practice'].some(stage=>{
    const answers=(answerStages[stage] as {questionId:string;value:string|null}[]|undefined)??[];
    const results=(resultStages[stage] as {questionId:string;status:string}[]|undefined)??[];
    return answers.some(answer=>answer.questionId===questionId&&answer.value===quote)&&
      results.some(result=>result.questionId===questionId&&result.status==='incorrect');
  });
}
function groundedSpeech(snapshot:ReviewEvidenceSnapshot,fragmentIds:unknown,quote:string):DailyFragment|undefined {
  if(!Array.isArray(fragmentIds)||fragmentIds.length!==1||typeof fragmentIds[0]!=='string')return undefined;
  return snapshot.fragments.find(fragment=>fragment.id===fragmentIds[0]&&fragment.role==='learner'&&fragment.text===quote);
}

/** Admits model feedback only when every quote and candidate is bound to frozen learner evidence. */
export function validateDailyReview(raw:unknown,binding:DailyReviewBinding,snapshot:ReviewEvidenceSnapshot) {
  if(!record(raw)||Object.keys(raw).some(key=>!['sections','strengths','priorities','errors','wordCandidates'].includes(key))||
    !Array.isArray(raw.sections)||raw.sections.length!==3||!Array.isArray(raw.strengths)||raw.strengths.length>3||
    !Array.isArray(raw.priorities)||raw.priorities.length>3||!Array.isArray(raw.errors)||raw.errors.length>6||
    !Array.isArray(raw.wordCandidates)||raw.wordCandidates.length>8)throw new ServiceError('invalid_daily_review');
  const expectedStages=['listening','speaking','practice'];
  const seenStages=new Set<string>();
  const sections:Record<string,any>[] = raw.sections.map((section:unknown)=>{
    if(!record(section)||Object.keys(section).length!==6||!['stage','evidence','correct','total','helped','explanation'].every(k=>Object.hasOwn(section,k))||
      !expectedStages.includes(section.stage)||seenStages.has(section.stage)||!['sufficient','limited','not_observed'].includes(section.evidence)||
      !(section.correct===null||(Number.isSafeInteger(section.correct)&&section.correct>=0))||
      !(section.total===null||(Number.isSafeInteger(section.total)&&section.total>=0))||typeof section.helped!=='boolean'||!clean(section.explanation,500))
      throw new ServiceError('invalid_daily_review');
    if(section.stage==='speaking'&&(section.correct!==null||section.total!==null))throw new ServiceError('invalid_daily_review');
    if(section.evidence==='not_observed'&&(section.correct!==null||section.total!==null))throw new ServiceError('invalid_daily_review');
    seenStages.add(section.stage);
    const stage=section.stage as 'listening'|'speaking'|'practice';
    const helpUsed=Boolean(snapshot.helpUsed?.[stage]?.length);
    if(stage==='speaking'){
      const learnerFragments=snapshot.fragments.filter(fragment=>fragment.role==='learner'&&fragment.text.trim().length>0);
      return {...section,evidence:learnerFragments.length<2?'not_observed':learnerFragments.some(fragment=>fragment.transcriptionUncertain)?'limited':'sufficient',
        correct:null,total:null,helped:helpUsed};
    }
    const results=(snapshot.sectionResults?.[stage] as {status:string}[]|undefined)??[];
    const answered=results.filter(result=>result.status!=='skipped').length;
    return {...section,evidence:results.length===0||answered===0?'not_observed':answered<results.length?'limited':'sufficient',
      correct:results.filter(result=>result.status==='correct').length,total:results.length||null,helped:helpUsed};
  });
  for(const items of [raw.strengths,raw.priorities])if(items.some((item:unknown)=>!clean(item,300)))throw new ServiceError('invalid_daily_review');
  const errors=raw.errors.map<Record<string,any>|null>((error:unknown)=>{
    if(!record(error)||Object.keys(error).some(key=>!['kind','quote','correction','explanation','fragmentIds','questionId','confidence'].includes(key))||
      !['kind','quote','correction','explanation','fragmentIds','questionId','confidence'].every(k=>Object.hasOwn(error,k))||
      !['grammar','vocabulary','comprehension','task'].includes(error.kind)||!clean(error.quote,300)||!clean(error.correction,300)||
      !clean(error.explanation,500)||!Array.isArray(error.fragmentIds)||!['supported','uncertain'].includes(error.confidence))
      throw new ServiceError('invalid_daily_review');
    let source:DailyFragment|undefined;
    if(error.questionId===null){
      source=groundedSpeech(snapshot,error.fragmentIds,error.quote);
      if(!source||error.kind==='comprehension'||(source.transcriptionUncertain&&error.confidence!=='uncertain')||
        (!source.transcriptionUncertain&&error.confidence==='uncertain'))return null;
    }else if(typeof error.questionId==='string'&&/^[a-z0-9_-]{1,64}$/.test(error.questionId)){
      if(error.fragmentIds.length||!answerEvidence(snapshot,error.questionId,error.quote))return null;
    }else throw new ServiceError('invalid_daily_review');
    return {...error,id:stableId(`${binding.attemptId}:${binding.inputHash}:error:${error.kind}:${error.quote}:${error.correction}`)};
  }).filter((error:Record<string,any>|null):error is Record<string,any>=>error!==null);
  const marks=snapshot.wordMarks??[];
  const candidates=new Map<string,{candidateId:string;kind:'word'|'expression';text:string;meaning:string;origin:'learner_mark'|'review';
    confidence:'supported';evidenceIds:string[]}>();
  for(const mark of marks)candidates.set(`${mark.kind}:${mark.text.toLocaleLowerCase('en-US')}`,{
    candidateId:mark.markId,kind:mark.kind,text:mark.text,meaning:mark.meaning,origin:'learner_mark',confidence:'supported',evidenceIds:[mark.markId]});
  for(const candidate of raw.wordCandidates){
    if(!record(candidate)||Object.keys(candidate).some(key=>!['kind','text','meaning','origin','evidenceIds'].includes(key))||
      !['word','expression'].includes(candidate.kind)||!clean(candidate.text,120)||typeof candidate.meaning!=='string'||
      !clean(candidate.meaning,300)||candidate.origin!=='review'||!Array.isArray(candidate.evidenceIds))
      throw new ServiceError('invalid_daily_review');
    const evidence=candidate.evidenceIds as string[];
    const grounded=evidence.length>0&&evidence.every(id=>{
      const citedError=errors.some(error=>error.questionId===id||
        (error.questionId===null&&error.fragmentIds.includes(id as string)));
      if(uuid.test(id))return snapshot.fragments.some(fragment=>fragment.id===id&&fragment.role==='learner'&&
        !fragment.transcriptionUncertain&&(fragment.text.includes(candidate.text)||errors.some(error=>
          error.questionId===null&&error.fragmentIds.includes(id)&&error.correction===candidate.text)));
      return typeof id==='string'&&/^[a-z0-9_-]{1,64}$/.test(id)&&citedError&&
        errors.some(error=>error.questionId===id&&error.correction===candidate.text);
    });
    if(!grounded)continue;
    const key=`${candidate.kind}:${candidate.text.toLocaleLowerCase('en-US')}`;
    if(!candidates.has(key))candidates.set(key,{candidateId:stableId(`${binding.attemptId}:${binding.inputHash}:word:${key}`),
      kind:candidate.kind,text:candidate.text,meaning:candidate.meaning,origin:'review',confidence:'supported',evidenceIds:[...new Set(evidence)]});
  }
  const output={schemaVersion:1,reviewId:stableId(`${binding.attemptId}:${binding.inputHash}:review`),...binding,
    outcome:sections.some(section=>section.evidence==='not_observed')?'partial':'completed',sections,
    strengths:raw.strengths,priorities:raw.priorities,errors,wordCandidates:[...candidates.values()].slice(0,20)};
  if(Buffer.byteLength(JSON.stringify(output),'utf8')>32*1024)throw new ServiceError('daily_review_too_large',413);
  return output;
}
