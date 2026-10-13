import { randomUUID, createHash } from 'node:crypto';
import { transaction, type Database } from './db.js';
import type { PoolClient } from 'pg';
import { EXAM_MODELS, LEVEL_TEST_BANKS, type LevelTestContentItem, type LevelTestSection } from './level-tests-content.generated.js';
import { chatCompletion, extractJson, resolveLlmConfig, type ChatReply, type ResolvedLlmConfig } from './llm-config.js';
import { expiresAt, weekIndex } from './level-tests.js';
import { identifySnapshot, type SnapshotContent, type SnapshotMetadata } from './level-test-snapshot.js';
import { levelTestSettings } from './level-test-settings.js';
export { levelTestSettings } from './level-test-settings.js';
import { validateGeneratedPool, type GeneratedPool, type RawGeneratedItem } from './level-tests-validate.js';

/** Weekly level-verification test generation (level-tests-llm): four
    independent section calls (recognition, reading, listening, writing),
    each short enough to complete reliably, validated immediately, then a
    per-section judge pass. Everything fails closed: without a provider or
    a valid pool, the week has no generated exam and the deterministic
    endpoints keep serving. Grading is never performed here. */

export const LANGUAGE_NAMES: Record<string, string> = {
  en: 'English', es: 'Spanish', fr: 'French', de: 'German',
  it: 'Italian', pt: 'Portuguese', nb: 'Norwegian Bokm\u00e5l', zh: 'Chinese (Mandarin, Simplified)',
};

export const PROMPT_VERSION = 1;
export const JUDGE_VERSION = 1;
const WEEKLY_THEMES = ['daily routines and habits', 'travel, transport and getting lost', 'work, studies and colleagues',
  'food, restaurants and going out', 'weather, seasons and plans', 'moving house and neighbours',
  'shopping stories and returns', 'sport, weekends and free time'];
const MAX_SECTION_RETRIES = 1;

export interface ExamModelEntry { level: string; languageID: string; canDoAnchor: string;
  poolTarget: { recognition: number; writing: number; readingPassages: number; listeningPassages: number };
  examShape: Record<string, number>; sections: Record<string, unknown>; forbidden: string[] }

export function examModelFor(level: string): ExamModelEntry | undefined {
  const models = EXAM_MODELS as unknown as { levels: ExamModelEntry[] };
  return models.levels.find(entry => entry.level === level &&
    (entry.languageID === '*' || entry.languageID === 'en' || entry.languageID === 'es'));
}

const DEFAULT_THRESHOLDS = { overall: 70, recognition: 60, reading: 50, listening: 50, writing: 50 };

const thresholdsFor = (languageID: string, level: string): Record<string, number> => {
  const harvested = LEVEL_TEST_BANKS.find(bank => bank.languageID === languageID)
    ?.levels.find(entry => entry.level === level)?.thresholds;
  return { ...DEFAULT_THRESHOLDS, ...harvested };
};

const fewShotFor = (languageID: string, level: string, section: string): LevelTestContentItem[] => {
  const pool = LEVEL_TEST_BANKS.find(bank => bank.languageID === languageID)
    ?.levels.find(entry => entry.level === level)?.pool ?? [];
  return pool.filter(item => item.section === section).slice(0, 2);
};

const weeklyTheme = (weekId: number): string => WEEKLY_THEMES[weekId % WEEKLY_THEMES.length] ?? 'general';

// ---- section definitions ----

type SectionName = 'recognition' | 'reading' | 'listening' | 'writing';
const SECTION_ORDER: SectionName[] = ['recognition', 'reading', 'listening', 'writing'];

interface SectionSpec {
  section: SectionName;
  count: number;         // how many items (or passages for reading/listening)
  perPassage: number;    // questions per passage (0 = no passages)
}

const SECTION_SPECS: Record<SectionName, SectionSpec> = {
  recognition: { section: 'recognition', count: 12, perPassage: 0 },
  reading:     { section: 'reading',     count: 2,  perPassage: 3 },
  listening:   { section: 'listening',   count: 2,  perPassage: 2 },
  writing:     { section: 'writing',     count: 3,  perPassage: 0 },
};

const SKILLS_BY_LEVEL: Record<string, Record<SectionName, string>> = {
  A1: {
    recognition: 'present simple, verb be, articles, plurals, possessives, basic questions',
    reading: 'short everyday texts (signs, notes, simple descriptions)',
    listening: 'short announcements, simple dialogues',
    writing: 'basic sentence completion and short formulaic phrases',
  },
  A2: {
    recognition: 'past simple, comparatives, going-to future, quantifiers, time prepositions, past continuous',
    reading: 'short narratives, informal notes, everyday descriptions',
    listening: 'short dialogues, announcements with specific details',
    writing: 'short sentences about past events, plans, and comparisons',
  },
  B1: {
    recognition: 'present perfect, first/second conditional, used to, relative clauses, modal deduction, connectors',
    reading: 'opinion texts, narratives with evaluation, everyday problem-solving',
    listening: 'dialogues with opinions and deductions, connected speech',
    writing: 'conditional sentences, present perfect statements, relative clauses',
  },
  B2: {
    recognition: 'passive voice, reported speech, third conditional, wish clauses, phrasal verbs, connectors',
    reading: 'formal texts, narratives with inference, argumentative fragments',
    listening: 'presentations, interviews, connected speech with inference',
    writing: 'passive transformations, reported speech, formal connectors',
  },
  C1: {
    recognition: 'inversion, cleft sentences, formal connectors, nuanced modality, collocations, ellipsis',
    reading: 'literary or argumentative texts with implicit meaning',
    listening: 'presentations, debates, nuanced inference',
    writing: 'inverted sentences, formal connectors, advanced collocations',
  },
};

function sectionPrompt(section: SectionName, languageID: string, level: string,
                       count: number, perPassage: number, weekId: number,
                       fewShot: LevelTestContentItem[]): { role: 'system' | 'user'; content: string }[] {
  const langName = LANGUAGE_NAMES[languageID] ?? languageID;
  const skills = SKILLS_BY_LEVEL[level]?.[section] ?? 'general language competence';
  const theme = weeklyTheme(weekId);
  const fewShotJson = fewShot.length ? `Example items (style anchor — do NOT copy): ${JSON.stringify(fewShot)}` : '';

  const system = [
    `You are a certified exam item writer for CEFR ${level} ${langName} tests.`,
    'Return ONLY a valid JSON array — no prose, no code fences, no markdown.',
    'Every item must have exactly one indisputably correct answer and plausible-but-clearly-wrong distractors.',
    'Every `explain` teaches the rule in one short sentence in the target language.',
    'Nothing offensive, political, or branded.',
  ].join(' ');

  let user: string;
  if (section === 'recognition') {
    user = [
      `Write ${count} vocabulary and grammar items at CEFR ${level} level in ${langName}.`,
      `Skills to test: ${skills}.`,
      `Item types: mix of choice (with ${4} options, exactly 1 correct) and gap-fill.`,
      `Weekly theme for context: ${theme}.`,
      fewShotJson,
      `Reply as JSON array: [{"kind":"choice"|"gap","prompt":"...","options":["..."],"answers":["..."],"explain":"..."}]`,
    ].join('\n');
  } else if (section === 'reading') {
    user = [
      `Write ${count} reading passages (60-120 words each) in ${langName} at CEFR ${level} level.`,
      `Content focus: ${skills}. Weekly theme: ${theme}.`,
      `Each passage has exactly ${perPassage} comprehension questions (choice with 4 options).`,
      fewShotJson,
      `Reply as JSON array: [{"title":"...","text":"...","items":[{"kind":"choice","prompt":"...","options":["..."],"answers":["..."],"explain":"..."}]}]`,
    ].join('\n');
  } else if (section === 'listening') {
    user = [
      `Write ${count} listening passages (40-90 words each) in ${langName} at CEFR ${level} level.`,
      `Content focus: ${skills}. Weekly theme: ${theme}.`,
      `These will be read aloud — use natural spoken language.`,
      `Each passage has exactly ${perPassage} comprehension questions (choice with 4 options).`,
      fewShotJson,
      `Reply as JSON array: [{"text":"...","items":[{"kind":"choice","prompt":"...","options":["..."],"answers":["..."],"explain":"..."}]}]`,
    ].join('\n');
  } else {
    user = [
      `Write ${count} short production items at CEFR ${level} level in ${langName}.`,
      `Content focus: ${skills}. Weekly theme: ${theme}.`,
      `Each item gives an instruction in the learner's language and expects a short answer in ${langName}.`,
      `Accept 1-3 valid variants per item.`,
      fewShotJson,
      `Reply as JSON array: [{"kind":"written","prompt":"Write...","answers":["variant1","variant2"],"explain":"..."}]`,
    ].join('\n');
  }
  return [{ role: 'system' as const, content: system }, { role: 'user' as const, content: user }];
}

// ---- per-section generation ----

interface SectionResult {
  section: SectionName;
  items: LevelTestContentItem[];
  passages?: { title: string; text: string; items: LevelTestContentItem[] }[];
  errors: string[];
}

type LlmCall = (config: ResolvedLlmConfig, messages: { role: 'system' | 'user'; content: string }[]) => Promise<ChatReply>;

async function generateSection(
  call: LlmCall, config: ResolvedLlmConfig, section: SectionName,
  languageID: string, level: string, model: ExamModelEntry, weekId: number,
): Promise<SectionResult> {
  const spec = SECTION_SPECS[section];
  const fewShot = fewShotFor(languageID, level, section);
  const messages = sectionPrompt(section, languageID, level, spec.count, spec.perPassage, weekId, fewShot);

  const errors: string[] = [];
  let lastReply: ChatReply | undefined;
  for (let attempt = 0; attempt <= MAX_SECTION_RETRIES && !lastReply; attempt++) {
    const reply = await call(config, messages);
    if (attempt > 0) {
      // Directed retry: add the previous errors as feedback.
      messages.push({ role: 'user', content: `Previous attempt failed validation: ${errors.join('; ')}. Please fix and try again.` });
    }
    lastReply = reply;
  }
  if (!lastReply) throw new Error(`${section}: all attempts failed`);

  const parsed = extractJson(lastReply.content);
  if (!Array.isArray(parsed) || !parsed.length) throw new Error(`${section}: empty response`);

  if (section === 'reading' || section === 'listening') {
    // Passages: each element has { title?, text, items: [{kind, prompt, options, answers, explain}] }
    const passages: { title: string; text: string; items: LevelTestContentItem[] }[] = [];
    for (const passage of parsed) {
      const p = passage as { title?: string; text?: string; items?: { kind?: string; prompt?: string; options?: string[]; answers?: string[]; explain?: string; unitID?: string }[] };
      const passageText = typeof p.text === 'string' ? p.text.trim() : '';
      const rawItems = Array.isArray(p.items) ? p.items : [];
      if (!passageText || !rawItems.length) continue;
      const passageItems: LevelTestContentItem[] = rawItems.map((raw, index) => ({
        id: `pending-${index}`, section: section as LevelTestSection,
        kind: raw.kind ?? 'choice',
        prompt: raw.prompt ?? '',
        options: Array.isArray(raw.options) ? raw.options : [],
        answers: Array.isArray(raw.answers) ? raw.answers : [],
        explain: raw.explain ?? '',
        unitID: typeof raw.unitID === 'string' ? raw.unitID : undefined,
      }));
      if (passageItems.length < 2) continue;
      passages.push({ title: p.title ?? '', text: passageText, items: passageItems });
    }
    if (passages.length < SECTION_SPECS[section].count) {
      errors.push(`${section}: only ${passages.length} passages (needed ${SECTION_SPECS[section].count})`);
    }
    return { section, items: [], passages, errors };
  }

  // recognition / writing: flat item arrays
  const items: LevelTestContentItem[] = parsed.map((item: { kind?: string; prompt?: string; options?: string[]; answers?: string[]; explain?: string; unitID?: string }, index: number) => ({
    id: `pending-${index}`,
    section: section as LevelTestSection,
    kind: item.kind ?? 'choice',
    prompt: item.prompt ?? '',
    options: Array.isArray(item.options) ? item.options : [],
    answers: Array.isArray(item.answers) ? item.answers : [],
    explain: item.explain ?? '',
    unitID: typeof item.unitID === 'string' ? item.unitID : undefined,
  }));
  return { section, items, errors };
}

// ---- section judge ----

async function judgeSection(
  call: LlmCall, config: ResolvedLlmConfig, section: SectionName,
  languageID: string, level: string, items: (RawGeneratedItem & { passageText?: string })[],
): Promise<{ id: string; verdict: 'ok' | 'flagged' | 'reject'; reason?: string }[]> {
  const langName = LANGUAGE_NAMES[languageID] ?? languageID;
  const system = `You are a strict exam reviewer for CEFR ${level} ${langName} tests. For EVERY item decide: is the marked answer uniquely correct, are all distractors clearly wrong, is the difficulty plausible? Reply ONLY JSON: {"items":[{"id":"...","verdict":"ok"|"flagged"|"reject","reason":"one sentence"}]}.`;
  const user = JSON.stringify(items.map(item => ({ id: item.id, kind: item.kind, prompt: item.prompt,
    options: item.options, answers: item.answers, explain: item.explain, passageText: item.passageText })));
  const reply = await call(config, [{ role: 'system' as const, content: system }, { role: 'user' as const, content: user }]);
  return parseVerdicts(reply);
}

// ---- orchestration ----

export interface GenerateDeps {
  db: Database; env: NodeJS.ProcessEnv;
  transport?: LlmCall;
  now?: Date;
}

export interface GenerateOptions {
  languageID: string; level: string;
  weekIndex?: number; force?: boolean; now?: Date;
}
export interface GenerationOutcome {
  status: 'approved' | 'draft' | 'exists' | 'failed';
  languageID: string; level: string; weekId: number;
  variants?: number; tokensIn?: number; tokensOut?: number; errors?: string[];
}

export async function generateWeeklyLevelTest(deps: GenerateDeps, options: GenerateOptions): Promise<GenerationOutcome> {
  const { languageID, level } = options;
  const weekId = options.weekIndex ?? weekIndex(options.now ?? new Date());
  const model = examModelFor(level);
  if (!model) return { status: 'failed', languageID, level, weekId, errors: ['no exam model'] };

  const existing = await currentWeekRow(deps.db, languageID, level, weekId);
  if (existing?.status === 'approved' && !options.force)
    return { status: 'exists', languageID, level, weekId, variants: (existing.variants as unknown[]).length };

  const config = await resolveLlmConfig(deps.db, deps.env);
  if (!config) return { status: 'failed', languageID, level, weekId, errors: ['no llm configuration'] };
  const call = deps.transport ?? ((resolved, messages) => chatCompletion(resolved, messages));

  const errors: string[] = [];
  let tokensIn = 0, tokensOut = 0;

  // ---- generate sections independently ----
  const sectionResults = new Map<SectionName, SectionResult>();
  for (const section of SECTION_ORDER) {
    let result: SectionResult | undefined;
    for (let attempt = 0; attempt <= MAX_SECTION_RETRIES && !result; attempt++) {
      try {
        result = await generateSection(call, config, section, languageID, level, model, weekId);
        if (result.errors.length) {
          errors.push(...result.errors);
          if (attempt < MAX_SECTION_RETRIES) result = undefined; // retry
        }
      } catch (error) {
        errors.push(`${section} attempt ${attempt + 1}: ${error instanceof Error ? error.message : String(error)}`);
        if (attempt >= MAX_SECTION_RETRIES) break;
      }
    }
    if (result) sectionResults.set(section, result);
  }

  // ---- combine into a pool ----
  let pool: GeneratedPool = { recognition: [], writing: [], readingPassages: [], listeningPassages: [] };
  const recognitionResult = sectionResults.get('recognition');
  const readingResult = sectionResults.get('reading');
  const listeningResult = sectionResults.get('listening');
  const writingResult = sectionResults.get('writing');

  if (recognitionResult) pool.recognition = recognitionResult.items;
  if (readingResult) {
    for (const passage of readingResult.passages ?? [])
      pool.readingPassages.push(passage);
  }
  if (writingResult) pool.writing.push(...writingResult.items);
  if (listeningResult) {
    for (const passage of listeningResult.passages ?? [])
      pool.listeningPassages.push(passage);
  }

  assignProvisionalIds(pool);

  const validate = () => validateGeneratedPool({ recognition: pool.recognition, writing: pool.writing,
    reading: pool.readingPassages, listening: pool.listeningPassages }, model.examShape, languageID);
  const initial = validate();
  if (!initial.ok) return { status: 'failed', languageID, level, weekId, tokensIn, tokensOut,
    errors: initial.errors.map(error => `${error.section}: ${error.code}: ${error.detail}`) };
  pool = initial.pool;

  const settings = await levelTestSettings(deps.db, deps.env);
  const review: JudgeVerdict[] = [], rejected = new Set<string>();
  if (settings.verifyMode !== 'off') for (const section of SECTION_ORDER) {
    const items = sectionItems(pool, section);
    try {
      const verdicts = await judgeSection(call, config, section, languageID, level, items);
      const expected = new Set(items.map(item => item.id)), seen = new Set<string>();
      for (const verdict of verdicts) {
        if (!expected.has(verdict.id) || seen.has(verdict.id)) throw new Error('judge_invalid_ids');
        seen.add(verdict.id);
      }
      const missing = items.filter(item => !seen.has(item.id!));
      if (missing.length && settings.verifyMode === 'strict') throw new Error('judge_incomplete');
      review.push(...verdicts, ...missing.map(item => ({ id: item.id!, verdict: 'flagged' as const, reason: 'judge_incomplete' })));
      for (const verdict of verdicts) if (verdict.verdict === 'reject') rejected.add(verdict.id);
    } catch {
      if (settings.verifyMode === 'strict') return { status: 'failed', languageID, level, weekId,
        tokensIn, tokensOut, errors: [`${section}: judge_failed_or_incomplete`] };
      review.push(...items.map(item => ({ id: item.id!, verdict: 'flagged' as const, reason: 'judge_unavailable' })));
    }
  }
  if (rejected.size) {
    pool.recognition = pool.recognition.filter(item => !rejected.has(item.id));
    pool.writing = pool.writing.filter(item => !rejected.has(item.id));
    for (const section of ['reading', 'listening'] as const) {
      const key = section === 'reading' ? 'readingPassages' : 'listeningPassages';
      pool[key] = pool[key].map(passage => ({ ...passage, items: passage.items.filter(item => !rejected.has(item.id)) }))
        .filter(passage => passage.items.length >= (model.examShape[`${section}PerPassage`] ?? (section === 'reading' ? 3 : 2)));
    }
    const trimmed = validate();
    if (!trimmed.ok) return { status: 'failed', languageID, level, weekId, tokensIn, tokensOut,
      errors: trimmed.errors.map(error => `${error.section}: ${error.code}: ${error.detail}`) };
    pool = trimmed.pool;
  }

  const generationId = randomUUID();
  const variants = composeVariants(pool, model, weekId, settings.variants).map(variant =>
    identifySnapshot({ schemaVersion: 1, languageID, level, version: 1, weekId,
      expiresAt: expiresAt(options.now ?? new Date()), thresholds: thresholdsFor(languageID, level),
      ...variant }, 'llm', generationId));
  const status = settings.publishMode === 'auto' ? 'approved' : 'draft';
  const approvedBy = settings.publishMode === 'auto' ? 'system:auto' : null;

  await persistWeek(deps.db, { generationId, languageID, level, weekId, pool, variants, status,
    model: config.model, generator: `llm:${config.provider}`, review,
    tokensIn, tokensOut, approvedBy, verifyMode: settings.verifyMode, force: options.force === true });

  return { status: status as 'approved' | 'draft', languageID, level, weekId,
    variants: variants.length, tokensIn, tokensOut };
}

function sectionItems(pool: GeneratedPool, section: SectionName): (RawGeneratedItem & { passageText?: string })[] {
  if (section === 'recognition') return pool.recognition;
  if (section === 'writing') return pool.writing;
  if (section === 'reading') return pool.readingPassages.flatMap(p => p.items.map(item => ({ ...item, passageText: p.text })));
  if (section === 'listening') return pool.listeningPassages.flatMap(p => p.items.map(item => ({ ...item, passageText: p.text })));
  return [];
}

function buildJudgeMessages(level: string, model: ExamModelEntry, pool: GeneratedPool) {
  const system = [
    `You are a strict exam reviewer for CEFR ${level} language tests.`,
    'For EVERY item decide: is the marked answer uniquely correct, are all distractors clearly wrong, is the difficulty plausible for the level, and is the explain accurate?',
    'Reply ONLY JSON: {"items":[{"id":"<as given>","verdict":"ok"|"flagged"|"reject","reason":"one short sentence"}]}. Verdict every single id.',
  ].join(' ');
  return [{ role: 'system' as const, content: system }, { role: 'user' as const, content: JSON.stringify({
    canDoAnchor: model.canDoAnchor, items: pool }) }];
}

interface JudgeVerdict { id: string; verdict: 'ok' | 'flagged' | 'reject'; reason?: string }

function parseVerdicts(reply: ChatReply): JudgeVerdict[] {
  const parsed = extractJson(reply.content) as { items?: { id?: unknown; verdict?: unknown; reason?: unknown }[] };
  if (!Array.isArray(parsed?.items) || parsed.items.some(entry => !entry || typeof entry.id !== 'string' ||
      !['ok', 'flagged', 'reject'].includes(entry.verdict as string))) throw new Error('judge_invalid_schema');
  return parsed.items
    .map(entry => ({ id: entry.id as string, verdict: entry.verdict as 'ok' | 'flagged' | 'reject',
      reason: typeof entry.reason === 'string' ? entry.reason : undefined }));
}

function assignProvisionalIds(pool: GeneratedPool): void {
  pool.recognition.forEach((item, index) => { item.id = `rec-${index}`; });
  pool.writing.forEach((item, index) => { item.id = `wri-${index}`; });
  pool.readingPassages.forEach((passage, passageIndex) => {
    passage.items.forEach((item, itemIndex) => { item.id = `rea-${passageIndex}-${itemIndex}`; });
  });
  pool.listeningPassages.forEach((passage, passageIndex) => {
    passage.items.forEach((item, itemIndex) => { item.id = `lis-${passageIndex}-${itemIndex}`; });
  });
}

/** The K variants: same stable-hash block rotation as the deterministic
    composer, with the variant index salted into the hash. */
function composeVariants(pool: GeneratedPool, model: ExamModelEntry, weekId: number, count: number): GeneratedVariant[] {
  const variants: GeneratedVariant[] = [];
  const flat = flattenPool(pool);
  const shape = {
    recognition: model.examShape.recognition ?? 12, writing: model.examShape.writing ?? 3,
    readingPassages: model.examShape.readingPassages ?? 2, readingPerPassage: model.examShape.readingPerPassage ?? 3,
    listeningPassages: model.examShape.listeningPassages ?? 2, listeningPerPassage: model.examShape.listeningPerPassage ?? 2,
  };
  const passages = [
    ...pool.readingPassages.map((passage, index) => ({ id: `llm-reading-${index + 1}`, title: passage.title, text: passage.text })),
    ...pool.listeningPassages.map((passage, index) => ({ id: `llm-listening-${index + 1}`, title: passage.title, text: passage.text })),
  ];
  for (let variant = 1; variant <= count; variant++) {
    const salt = `v${variant}`;
    const recognition = stableBlock(flat.filter(item => item.section === 'recognition'),
      salt, shape.recognition, weekId);
    const writing = stableBlock(flat.filter(item => item.section === 'writing'),
      salt, shape.writing, weekId);
    const readingOrder = stablePassageOrder(flat, 'reading', shape.readingPerPassage, salt)
      .filter(passage => passage.id.startsWith('llm-reading-'));
    const readingChunks = chunk(readingOrder, shape.readingPassages);
    const readingBlock = readingChunks[weekId % readingChunks.length] ?? readingOrder;
    const readingIds = (readingBlock.length === shape.readingPassages ? readingBlock : readingOrder.slice(0, shape.readingPassages)).map(passage => passage.id);
    const listeningOrder = stablePassageOrder(flat, 'listening', shape.listeningPerPassage, salt)
      .filter(passage => !readingIds.includes(passage.id));
    const listeningChunks = chunk(listeningOrder, shape.listeningPassages);
    const listeningBlock = listeningChunks[weekId % listeningChunks.length] ?? listeningOrder;
    const listeningIds = (listeningBlock.length === shape.listeningPassages ? listeningBlock : listeningOrder.slice(0, shape.listeningPassages)).map(passage => passage.id);
    const items = [
      ...recognition,
      ...readingIds.flatMap(id => flat.filter(item => item.section === 'reading' && item.passageId === id).slice(0, shape.readingPerPassage)),
      ...listeningIds.flatMap(id => flat.filter(item => item.section === 'listening' && item.passageId === id).slice(0, shape.listeningPerPassage)),
      ...writing,
    ];
    variants.push({ variant, items, passages: passages.filter(passage =>
      readingIds.includes(passage.id) || listeningIds.includes(passage.id)),
      readingPassageIds: readingIds, listeningPassageIds: listeningIds });
  }
  return variants;
}

interface GeneratedVariant extends Pick<SnapshotContent, 'items' | 'passages' | 'readingPassageIds' | 'listeningPassageIds'> { variant: number }

interface FlatItem { id: string; section: LevelTestSection; kind: string; prompt: string;
  options: string[]; answers: string[]; explain: string; unitID?: string; passageId?: string; speech?: string }

function flattenPool(pool: GeneratedPool): FlatItem[] {
  const items: FlatItem[] = [];
  const push = (section: LevelTestSection, item: RawGeneratedItem, passageId?: string, speech?: string) => {
    const itemSpeech = item.speech ?? undefined;
    items.push({
      id: typeof item.id === 'string' ? item.id : `pending-${items.length}`,
      section, kind: item.kind ?? 'choice', prompt: typeof item.prompt === 'string' ? item.prompt : '',
      options: Array.isArray(item.options) ? item.options : [],
      answers: Array.isArray(item.answers) ? item.answers : [],
      explain: typeof item.explain === 'string' ? item.explain : '',
      unitID: typeof item.unitID === 'string' ? item.unitID : undefined,
      passageId: item.passageId ?? passageId, speech: itemSpeech ?? speech,
    });
  };
  for (const item of pool.recognition) push('recognition', item);
  for (const item of pool.writing) push('writing', item);
  pool.readingPassages.forEach((passage, index) => {
    for (const item of passage.items) push('reading', item, `llm-reading-${index + 1}`, item.speech ?? undefined);
  });
  pool.listeningPassages.forEach((passage, index) => {
    for (const item of passage.items) push('listening', item, `llm-listening-${index + 1}`, item.speech ?? passage.text ?? undefined);
  });
  return items;
}

function stableBlock(items: FlatItem[], salt: string, count: number, weekId: number): FlatItem[] {
  const ordered = [...items].sort((a, b) => hashOf(salt, a.id).localeCompare(hashOf(salt, b.id)));
  const blocks: FlatItem[][] = [];
  for (let start = 0; start < ordered.length; start += count) blocks.push(ordered.slice(start, start + count));
  const block = blocks[weekId % blocks.length] ?? ordered;
  return block.length === count ? block : ordered.slice(0, count);
}

function stablePassageOrder(items: FlatItem[], section: string, perPassage: number, salt: string):
  { id: string }[] {
  const counts = new Map<string, number>();
  for (const item of items) if (item.section === section && item.passageId)
    counts.set(item.passageId, (counts.get(item.passageId) ?? 0) + 1);
  return [...counts.entries()].filter(([, count]) => count >= perPassage)
    .map(([id]) => ({ id }))
    .sort((a, b) => hashOf(salt, a.id).localeCompare(hashOf(salt, b.id)));
}

function hashOf(salt: string, id: string): string {
  return createHash('sha256').update(`${salt}|${id}`).digest('hex');
}

function chunk<T>(list: T[], size: number): T[][] {
  const out: T[][] = [];
  for (let start = 0; start < list.length; start += size) out.push(list.slice(start, start + size));
  return out;
}


async function currentWeekRow(sql: PoolClient | Database, languageID: string, level: string, weekId: number) {
  const rows = await sql.query(`SELECT status, variants FROM level_test_weeks
    WHERE language_id=$1 AND level=$2 AND week_index=$3`, [languageID, level, weekId]);
  return rows.rows[0] as { status: string; variants: unknown[] } | undefined;
}

async function persistWeek(db: Database, input: {
  generationId: string; languageID: string; level: string; weekId: number;
  pool: GeneratedPool; variants: unknown[]; status: string;
  model: string; generator: string; review: unknown[] | undefined;
  tokensIn: number; tokensOut: number; approvedBy: string | null;
  verifyMode: string; force: boolean;
}): Promise<void> {
  await transaction(db, async (sql: PoolClient) => {
    const existing = await currentWeekRow(sql, input.languageID, input.level, input.weekId);
    // --force over an approved week retires it (audit) and stores a fresh row;
    // drafts are replaced in place. Without force, an approved week is untouched.
    if (existing?.status === 'approved' && !input.force) return;
    if (existing) {
      await sql.query(`UPDATE level_test_weeks SET status='retired'
        WHERE language_id=$1 AND level=$2 AND week_index=$3 AND status <> 'retired'`,
        [input.languageID, input.level, input.weekId]);
    }
    await sql.query(`INSERT INTO level_test_weeks(id, language_id, level, week_index, source, status, pool, variants,
      generator, model, prompt_version, judge_version, judge_mode, review, tokens_in, tokens_out, approved_at, approved_by)
      VALUES($1,$2,$3,$4,'llm',$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17)`,
      [input.generationId, input.languageID, input.level, input.weekId, input.status, JSON.stringify(input.pool),
       JSON.stringify(input.variants), input.generator, input.model, PROMPT_VERSION, JUDGE_VERSION,
       input.verifyMode, JSON.stringify(input.review ?? []), input.tokensIn, input.tokensOut,
       input.status === 'approved' ? new Date() : null, input.approvedBy]);
  });
}

export interface ServedLevelTest extends SnapshotContent, Partial<SnapshotMetadata> {
  variant: number;
  /** Legacy rows did not freeze grading metadata; keep their original wire behavior. */
  legacy?: true;
}

export async function approvedWeeklyLevelTest(db: Database, languageID: string, level: string,
    variant: number, now: Date = new Date()): Promise<ServedLevelTest | undefined> {
  const weekId = weekIndex(now);
  const rows = await db.query(`SELECT id, pool, variants FROM level_test_weeks
    WHERE language_id=$1 AND level=$2 AND week_index=$3 AND status='approved'`,
    [languageID, level, weekId]);
  const row = rows.rows[0] as { id: string; variants: ServedLevelTest[] } | undefined;
  if (!row || !Array.isArray(row.variants) || !row.variants.length) return undefined;
  const chosen = row.variants.find(entry => entry.variant === variant) ?? row.variants[0];
  if (!chosen) return undefined;
  // New generations store the complete snapshot once. Never resolve its thresholds
  // or composition against the current bundle when serving the persisted variant.
  if (chosen.examId && chosen.contentHash) return chosen;
  // Do not invent grading values for legacy generations that never stored them.
  // Their missing kind/threshold fields need an explicit future migration policy.
  return { schemaVersion: 1, languageID, level, version: 1, weekId, expiresAt: expiresAt(now),
    thresholds: {}, variant: chosen.variant, legacy: true,
    generationId: row.id, examId: `${row.id}:v${chosen.variant}`,
    passages: chosen.passages, readingPassageIds: chosen.readingPassageIds,
    listeningPassageIds: chosen.listeningPassageIds, items: chosen.items };
}
