import type { Database } from './db.js';
import { ServiceError } from './errors.js';

export type DailySpeakingPackage={language:string;topic:{title:string;summary:string};
  speaking:{role:string;opening:string;topicAnchors:string[];closingCue:string};goals:{task:string}[]};
export function buildDailyVoiceInstructions(pkg:DailySpeakingPackage):string {
  const anchors=pkg.speaking.topicAnchors.length?pkg.speaking.topicAnchors.map(anchor=>'- '+anchor).join('\n'):'- Explore the topic naturally.';
  const goals=pkg.goals.map(goal=>'- '+goal.task).join('\n');
  const instructions=[
    'You are a supportive conversation partner for a shared daily '+pkg.language+' speaking activity.',
    'Topic: '+pkg.topic.title+'. Context: '+pkg.topic.summary,
    'Your role: '+pkg.speaking.role,
    'Make this feel like a natural two-way conversation, not an interview or a fixed sequence of questions.',
    'Begin with the supplied opening, then respond to what the learner actually says. Ask at most one natural follow-up at a time; do not read the anchors as a checklist.',
    'Opening: '+pkg.speaking.opening,
    'Optional topic anchors:\n'+anchors,
    'Learning goals:\n'+goals,
    'Use clear, encouraging language and give the learner time to speak. If they struggle, offer a brief rephrase or gentle prompt without taking over.',
    'Do not correct mistakes, grade, or give performance feedback during the conversation. That is reserved for the later review.',
    'Only close after the learner chooses to finish. When appropriate, use this cue: '+pkg.speaking.closingCue,
    'Do not impose a timer, turn count, or automatic ending. Do not use personal learning history or invent facts about the learner.',
  ].join('\n\n');
  if(Buffer.byteLength(instructions,'utf8')>12_000)throw new ServiceError('daily_voice_context_invalid');
  return instructions;
}

export function canStartDailySpeaking(state:unknown):boolean {
  if(!state||typeof state!=='object'||Array.isArray(state))return false;
  const value=state as Record<string,unknown>;
  if(value.stage!=='speaking'||value.speakingClosed===true||!Array.isArray(value.completedStages))return false;
  return value.completedStages.some((stage:unknown)=>stage&&typeof stage==='object'&&!Array.isArray(stage)&&
    (stage as Record<string,unknown>).stage==='listening');
}

export function dailySpeechHistory(state:unknown,segmentOrder:Map<string,number>=new Map()) {
  if(!state||typeof state!=='object'||Array.isArray(state))return [];
  const fragments=(state as {fragments?:unknown}).fragments;
  if(!Array.isArray(fragments))return [];
  let history=fragments.filter((item:any)=>item&&typeof item.text==='string'&&item.text.trim())
    .sort((a:any,b:any)=>(segmentOrder.get(a.voiceSegmentId)??Number.MAX_SAFE_INTEGER)-(segmentOrder.get(b.voiceSegmentId)??Number.MAX_SAFE_INTEGER)||a.sequence-b.sequence).map((fragment:any)=>({type:'message' as const,
      role:fragment.role==='learner'?'user' as const:'assistant' as const,
      content:[{type:fragment.role==='learner'?'input_text' as const:'output_text' as const,text:fragment.text}]})).slice(-40);
  while(history.length&&Buffer.byteLength(JSON.stringify(history),'utf8')>5_500)history=history.slice(1);
  return history;
}

export async function resolveDailyVoiceContext(db:Database,accountId:string,attemptId:string):Promise<{instructions:string}> {
  if(!/^[a-f0-9]{8}-[a-f0-9]{4}-[1-8][a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/i.test(attemptId))
    throw new ServiceError('not_found',404);
  const row=(await db.query<{state:string;stage_state:unknown;public_payload:DailySpeakingPackage}>(`SELECT a.state,a.stage_state,p.public_payload
    FROM daily_attempts a JOIN daily_packages p ON p.id=a.package_id
    WHERE a.account_id=$1 AND a.id=$2`,[accountId,attemptId])).rows[0];
  if(!row)throw new ServiceError('not_found',404);
  if(row.state!=='in_progress'||!canStartDailySpeaking(row.stage_state))throw new ServiceError('daily_voice_stage_unavailable',409);
  if(row.public_payload.language!=='en')throw new ServiceError('daily_language_not_enabled',404);
  const active=(await db.query(`SELECT 1 FROM daily_voice_segments WHERE attempt_id=$1 AND state='active'`,[attemptId])).rowCount;
  if(active)throw new ServiceError('daily_voice_active',409);
  return {instructions:buildDailyVoiceInstructions(row.public_payload)};
}
