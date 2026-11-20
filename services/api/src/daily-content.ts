import { createHash } from 'node:crypto';
import { dailyLanguageProfiles } from './daily-language-profiles.js';

export type DailyPackage = {
  schemaVersion: 1; packageVersionId: string; contentHash: string; editorialDate: string;
  language: string; profileVersion: string; topic: { id: string; title: string; summary: string };
  objective: string; challengeReference: 'B1-B2';
  estimatedMinutes: { preparation: 2; listening: 4; speaking: 6; practice: 3; review: 2; total: 17 };
  expressions: Array<{ id: string; text: string; meanings: { en: string; es: string }; examples: { en: string; es: string } }>;
  listening: { speechLocale: string; transcript: string; questions: DailyQuestion[] };
  speaking: { role: string; opening: string; topicAnchors: string[]; closingCue: string };
  goals: Array<{ id: string; task: string; hints: { en: string; es: string };
    sentenceStarters: { en: string; es: string }; exampleResponses: { en: string; es: string } }>;
  practice: { questions: DailyQuestion[] };
  supportLocales: Array<'en' | 'es'>;
};
export type DailyQuestion = { id: string; kind: 'single_choice' | 'gap_fill'; prompt: string;
  options: Array<{ id: string; text: string }>; hints: { en: string; es: string } };
export class DailyQuestionValidationError extends Error {
  constructor(public section:'listening'|'practice',public position:number,message:string){super(message);}
}

const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const sha256 = /^[0-9a-f]{64}$/i;
const id = /^[a-z0-9_-]{1,64}$/;
const cleanText = (v: unknown, max: number): v is string => typeof v === 'string' && v.length > 0 &&
  v.trim().length > 0 && [...v].length <= max && !/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/u.test(v) && !/[\uD800-\uDFFF]/u.test(v);
const record = (v: unknown): v is Record<string, any> => !!v && typeof v === 'object' && !Array.isArray(v);
function requireText(v: unknown, max: number, path: string): asserts v is string {
  if (!cleanText(v, max)) throw new Error(`invalid daily package: ${path}`);
}
function requireKeys(v: unknown, keys: readonly string[], path: string): asserts v is Record<string, any> {
  if (!record(v) || Object.keys(v).length !== keys.length || Object.keys(v).some(k => !keys.includes(k)))
    throw new Error(`invalid daily package: ${path}`);
}
function validateQuestion(q: unknown, path: string): asserts q is DailyQuestion {
  requireKeys(q, ['id','kind','prompt','options','hints'], path);
  if (!id.test(q.id) || !['single_choice','gap_fill'].includes(q.kind)) throw new Error(`invalid daily package: ${path}.id/kind`);
  requireText(q.prompt, 400, `${path}.prompt`);
  requireKeys(q.hints, ['en','es'], `${path}.hints`); requireText(q.hints.en, 300, `${path}.hints.en`); requireText(q.hints.es, 300, `${path}.hints.es`);
  if (!Array.isArray(q.options) || (q.kind === 'single_choice' ? q.options.length !== 4 : q.options.length !== 0))
    throw new Error(`invalid daily package: ${path}.options`);
  const optionIds = new Set<string>();
  q.options.forEach((option: unknown, i: number) => {
    requireKeys(option, ['id','text'], `${path}.options[${i}]`);
    if (!id.test(option.id) || optionIds.has(option.id)) throw new Error(`duplicate or invalid option id: ${path}`);
    optionIds.add(option.id); requireText(option.text, 200, `${path}.options[${i}].text`);
  });
}

export function validateDailyPackage(value: unknown): DailyPackage {
  requireKeys(value, ['schemaVersion','packageVersionId','contentHash','editorialDate','language','profileVersion','topic','objective',
    'challengeReference','estimatedMinutes','expressions','listening','speaking','goals','practice','supportLocales'], 'root');
  const p = value;
  const profile = dailyLanguageProfiles[p.language];
  const calendarDate = new Date(`${p.editorialDate}T00:00:00Z`);
  if (p.schemaVersion !== 1 || !uuid.test(p.packageVersionId) || !sha256.test(p.contentHash) ||
      !/^\d{4}-\d{2}-\d{2}$/.test(p.editorialDate) || Number.isNaN(calendarDate.getTime()) || calendarDate.toISOString().slice(0,10) !== p.editorialDate ||
      !profile?.enabled ||
      p.challengeReference !== 'B1-B2') throw new Error('invalid daily package: binding');
  if (p.profileVersion !== profile.profileVersion) throw new Error('invalid daily package: binding');
  requireKeys(p.topic, ['id','title','summary'], 'topic');
  if (!id.test(p.topic.id)) throw new Error('invalid daily package: topic.id');
  requireText(p.topic.title, 120, 'topic.title'); requireText(p.topic.summary, 400, 'topic.summary'); requireText(p.objective, 300, 'objective');
  requireKeys(p.estimatedMinutes, ['preparation','listening','speaking','practice','review','total'], 'estimatedMinutes');
  const estimate = p.estimatedMinutes;
  if (estimate.preparation !== 2 || estimate.listening !== 4 || estimate.speaking !== 6 ||
      estimate.practice !== 3 || estimate.review !== 2 || estimate.total !== 17)
    throw new Error('invalid daily package: estimatedMinutes');
  if (!Array.isArray(p.expressions) || p.expressions.length !== 5) throw new Error('invalid daily package: expressions');
  const expressionIds = new Set<string>();
  p.expressions.forEach((entry: unknown, i: number) => {
    requireKeys(entry, ['id','text','meanings','examples'], `expressions[${i}]`);
    if (!id.test(entry.id) || expressionIds.has(entry.id)) throw new Error('duplicate expression id'); expressionIds.add(entry.id);
    requireText(entry.text, 120, `expressions[${i}].text`);
    for (const field of ['meanings','examples']) { requireKeys(entry[field], ['en','es'], `expressions[${i}].${field}`);
      requireText(entry[field].en, 300, `${field}.en`); requireText(entry[field].es, 300, `${field}.es`); }
  });
  requireKeys(p.listening, ['speechLocale','transcript','questions'], 'listening');
  if (p.listening.speechLocale !== profile.speechLocale ||
      !/^[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*$/.test(p.listening.speechLocale))
    throw new Error('invalid daily package: listening speech locale binding');
  requireText(p.listening.transcript, 1500, 'listening.transcript');
  if (!Array.isArray(p.listening.questions) || p.listening.questions.length !== 3) throw new Error('invalid daily package: listening.questions');
  requireKeys(p.speaking, ['role','opening','topicAnchors','closingCue'], 'speaking');
  for (const field of ['role','opening','closingCue']) requireText(p.speaking[field], 400, `speaking.${field}`);
  if (!Array.isArray(p.speaking.topicAnchors) || p.speaking.topicAnchors.length > 3 ||
      p.speaking.topicAnchors.some((x: unknown) => !cleanText(x, 300) || /\?/.test(x))) throw new Error('invalid daily package: speaking.topicAnchors');
  if (!Array.isArray(p.goals) || p.goals.length !== 3) throw new Error('invalid daily package: goals');
  const goalIds = new Set<string>();
  p.goals.forEach((g: unknown, i: number) => { requireKeys(g, ['id','task','hints','sentenceStarters','exampleResponses'], `goals[${i}]`);
    if (!id.test(g.id) || goalIds.has(g.id)) throw new Error('duplicate goal id'); goalIds.add(g.id); requireText(g.task, 300, `goals[${i}].task`);
    for (const field of ['hints','sentenceStarters','exampleResponses']) { requireKeys(g[field], ['en','es'], `goals[${i}].${field}`);
      requireText(g[field].en, 400, `${field}.en`); requireText(g[field].es, 400, `${field}.es`); }
  });
  requireKeys(p.practice, ['questions'], 'practice');
  if (!Array.isArray(p.practice.questions) || p.practice.questions.length !== 4 ||
      p.practice.questions.filter((q: DailyQuestion) => q.kind === 'single_choice').length !== 2 ||
      p.practice.questions.filter((q: DailyQuestion) => q.kind === 'gap_fill').length !== 2) throw new Error('invalid daily package: practice.questions');
  const allQuestionIds = new Set<string>();
  if (p.listening.questions.some((q: DailyQuestion) => q.kind !== 'single_choice')) throw new Error('invalid daily package: listening question kind');
  for(const section of ['listening','practice'] as const) {
    p[section].questions.forEach((q:unknown,position:number)=>{
      const i=section==='listening'?position:position+p.listening.questions.length;
      try{validateQuestion(q,`questions[${i}]`);}
      catch(error){throw new DailyQuestionValidationError(section,position,error instanceof Error?error.message:'invalid daily question');}
      if(allQuestionIds.has(q.id))throw new Error('duplicate question id');
      allQuestionIds.add(q.id);
    });
  }
  if (JSON.stringify(p.supportLocales) !== JSON.stringify(['en','es'])) throw new Error('invalid daily package: supportLocales');
  const size = Buffer.byteLength(JSON.stringify(p));
  if (!profile || size > profile.maxPackageBytes) throw new Error('invalid daily package: size');
  return p as DailyPackage;
}

const publicFields = ['schemaVersion','packageVersionId','contentHash','editorialDate','language','profileVersion','topic','objective',
  'challengeReference','estimatedMinutes','expressions','listening','speaking','goals','practice','supportLocales'] as const;
export function projectPublicDailyPackage(value: unknown): DailyPackage {
  if (!record(value)) throw new Error('invalid daily package: root');
  const projected = Object.fromEntries(publicFields.map(key => [key, value[key]]));
  return validateDailyPackage(projected);
}

function canonical(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(canonical);
  if (record(value)) return Object.fromEntries(Object.entries(value).filter(([, v]) => v !== undefined)
    .sort(([a],[b]) => a < b ? -1 : a > b ? 1 : 0).map(([k,v]) => [k, canonical(v)]));
  return value;
}
export function hashDailyText(text: string): string { return createHash('sha256').update(text, 'utf8').digest('hex'); }
export function hashDailyArtifact(artifact: unknown): string {
  const digestable = record(artifact) ? Object.fromEntries(Object.entries(artifact).filter(([key]) => !['contentHash','artifactHash'].includes(key))) : artifact;
  return hashDailyText(JSON.stringify(canonical(digestable)));
}
export function gradeDailyClosedAnswers(answer: string, accepted: readonly string[]): boolean {
  const normalize = (v: string) => v.normalize('NFC').trim().replace(/\s+/gu, ' ').toLocaleLowerCase('en');
  return accepted.some(value => normalize(answer) === normalize(value));
}
