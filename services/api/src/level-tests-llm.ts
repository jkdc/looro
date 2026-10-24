import { randomUUID, createHash } from 'node:crypto';
import { transaction, type Database } from './db.js';
import type { PoolClient } from 'pg';
import { LEVEL_TEST_BANKS, type LevelTestContentItem, type LevelTestSection } from './level-tests-content.generated.js';
import { chatCompletion, extractJson, resolveLlmConfig, LlmTransportError, type ChatReply, type ResolvedLlmConfig } from './llm-config.js';
import { expiresAt, weekIndex } from './level-tests.js';
import { identifySnapshot, type SnapshotContent, type SnapshotMetadata } from './level-test-snapshot.js';
import { levelTestSettings } from './level-test-settings.js';
export { levelTestSettings } from './level-test-settings.js';
import { validateGeneratedPool, type GeneratedPool, type RawGeneratedItem, type ValidationFailure } from './level-tests-validate.js';
import { resolveExamBlueprint, type ExamModelEntry } from './level-test-blueprint.js';

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

export const PROMPT_VERSION = 2;
export const JUDGE_VERSION = 2;
const WEEKLY_THEMES = ['daily routines and habits', 'travel, transport and getting lost', 'work, studies and colleagues',
  'food, restaurants and going out', 'weather, seasons and plans', 'moving house and neighbours',
  'shopping stories and returns', 'sport, weekends and free time'];

export { type ExamModelEntry } from './level-test-blueprint.js';
export const examModelFor = (level: string, languageID = 'en'): ExamModelEntry | undefined =>
  resolveExamBlueprint(languageID, level);

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

export function generationExamples(languageID: string, level: string, section: string): LevelTestContentItem[] {
  // The bundled English learning pool is not an exam-reviewed reference corpus.
  return languageID === 'en' ? [] : fewShotFor(languageID, level, section);
}

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

export function sectionGenerationSpec(section: SectionName, model: ExamModelEntry): Pick<SectionSpec, 'count' | 'perPassage'> {
  if (section === 'recognition') return { count: model.poolTarget.recognition, perPassage: 0 };
  if (section === 'writing') return { count: model.poolTarget.writing, perPassage: 0 };
  const sectionBlueprint = model.sections[section];
  return {
    count: section === 'reading' ? model.poolTarget.readingPassages : model.poolTarget.listeningPassages,
    perPassage: sectionBlueprint?.questions ?? (section === 'reading'
      ? model.examShape.readingPerPassage : model.examShape.listeningPerPassage),
  };
}

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

export function buildSectionMessages(section: SectionName, languageID: string, level: string,
                       count: number, perPassage: number, weekId: number,
                       fewShot: LevelTestContentItem[], model: ExamModelEntry,
                       repairFeedback: string[] = []): { role: 'system' | 'user'; content: string }[] {
  if (languageID !== 'en') return legacySectionMessages(section, languageID, level, count, perPassage, weekId, fewShot);
  const langName = LANGUAGE_NAMES[languageID] ?? languageID;
  const englishBlueprint = model;
  const sectionBlueprint = englishBlueprint?.sections[section];
  const skills = sectionBlueprint?.skills?.join(', ') ?? SKILLS_BY_LEVEL[level]?.[section] ?? 'general language competence';
  const theme = weeklyTheme(weekId);
  const fewShotJson = fewShot.length ? `Example items (style anchor — do NOT copy): ${JSON.stringify(fewShot)}` : '';

  const system = [
    `You are a certified exam item writer for CEFR ${level} ${langName} tests.`,
    'Return ONLY a valid JSON array — no prose, no code fences, no markdown.',
    'Every item must have exactly one indisputably correct answer and plausible-but-clearly-wrong distractors.',
    'Every `explain` teaches the rule in one short sentence in the target language.',
    ...(englishBlueprint ? [
      `Assessment anchor: ${englishBlueprint.canDoAnchor}`,
      `Do not use these out-of-level or unsuitable targets: ${englishBlueprint.forbidden.join('; ')}.`,
      'Do not simulate higher proficiency with rare vocabulary alone; match the task demand and context to the stated level.',
      'Do not require specialist or unstated cultural knowledge to answer.',
    ] : []),
    'Nothing offensive, political, or branded.',
  ].join(' ');

  let user: string;
  if (section === 'recognition') {
    const options = sectionBlueprint?.options ?? 4;
    const mix = sectionBlueprint?.mix;
    const mixInstruction = mix ? `Use this approximate choice/gap-fill pool mix: ${Object.entries(mix).map(([kind, target]) => `${target} ${kind}`).join(', ')}.` :
      'Use a balanced mixture of choice and gap-fill items.';
    user = [
      `Write ${count} vocabulary and grammar items at CEFR ${level} level in ${langName}.`,
      `Task family: ${sectionBlueprint?.taskFamily ?? 'vocabulary and grammar in context'}. Skills to test: ${skills}.`,
      `${mixInstruction} Choice items use ${options} options with exactly 1 correct answer.`,
      `Weekly theme for context: ${theme}.`,
      fewShotJson,
      `Reply as JSON array: [{"kind":"choice"|"gap","prompt":"...","options":["..."],"answers":["..."],"explain":"..."}]`,
    ].join('\n');
  } else if (section === 'reading') {
    const [minWords, maxWords] = sectionBlueprint?.words ?? [60, 120];
    const register = sectionBlueprint?.register ?? 'level-appropriate';
    const questionCount = sectionBlueprint?.questions ?? perPassage;
    const focus = level === 'A1' || level === 'A2' ? 'gist and clear details' :
      level === 'B1' ? 'gist, details, and simple inference' : 'detail, inference, intention, and viewpoint';
    user = [
      `Write ${count} distinct reading passages (${minWords}-${maxWords} words each) in ${langName} at CEFR ${level} level.`,
      `Register and task family: ${register}; ${sectionBlueprint?.taskFamily ?? 'comprehension of connected text'}. Assess ${focus}; avoid repeating the same question target within or across passages.`,
      `Content focus: ${skills}. Weekly theme: ${theme}.`,
      `Each passage has exactly ${questionCount} comprehension questions (choice with ${sectionBlueprint?.options ?? 4} options). Every keyed answer must be supported by the passage.`,
      fewShotJson,
      `Reply as JSON array: [{"title":"...","text":"...","items":[{"kind":"choice","prompt":"...","options":["..."],"answers":["..."],"explain":"..."}]}]`,
    ].join('\n');
  } else if (section === 'listening') {
    const [minWords, maxWords] = sectionBlueprint?.words ?? [40, 90];
    const format = sectionBlueprint?.format ?? 'natural spoken language';
    const questionCount = sectionBlueprint?.questions ?? perPassage;
    const focus = level === 'A1' || level === 'A2' ? 'gist and clear details' :
      level === 'B1' ? 'gist, details, and simple inference' : 'detail, inference, intention, and viewpoint';
    user = [
      `Write ${count} distinct listening passages (${minWords}-${maxWords} words each) in ${langName} at CEFR ${level} level.`,
      `Format and task family: ${format}; ${sectionBlueprint?.taskFamily ?? 'spoken comprehension'}. Assess ${focus}; vary speakers and situations without making the answer depend on visual clues.`,
      `Task family: ${sectionBlueprint?.taskFamily ?? 'controlled short production'}. Content focus: ${skills}. Weekly theme: ${theme}.`,
      `These will be read aloud — use natural spoken language. Each passage has exactly ${questionCount} comprehension questions (choice with ${sectionBlueprint?.options ?? 4} options). Every keyed answer must be supported by the spoken text.`,
      fewShotJson,
      `Reply as JSON array: [{"text":"...","items":[{"kind":"choice","prompt":"...","options":["..."],"answers":["..."],"explain":"..."}]}]`,
    ].join('\n');
  } else {
    user = [
      `Write ${count} short production items at CEFR ${level} level in ${langName}.`,
      `Content focus: ${skills}. Weekly theme: ${theme}.`,
      `Each item gives a short instruction in ${englishBlueprint ? 'English adapted to the target level' : "the learner's language"} and expects a short answer in ${langName}.`,
      `Accept 1-3 valid variants per item.`,
      fewShotJson,
      `Reply as JSON array: [{"kind":"written","prompt":"Write...","answers":["variant1","variant2"],"explain":"..."}]`,
    ].join('\n');
  }
  const messages = [{ role: 'system' as const, content: system }, { role: 'user' as const, content: user }];
  if (repairFeedback.length) messages.push({ role: 'user', content: [
    'Your previous response did not pass deterministic validation. Repair the entire section and satisfy every listed check.',
    `Validation locations and codes: ${repairFeedback.join(', ')}.`,
    'Do not repeat invalid or duplicate content. Return the complete corrected section in the required JSON format.',
  ].join(' ') });
  return messages;
}

function legacySectionMessages(section: SectionName, languageID: string, level: string,
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
      `Weekly theme for context: ${theme}.`, fewShotJson,
      `Reply as JSON array: [{"kind":"choice"|"gap","prompt":"...","options":["..."],"answers":["..."],"explain":"..."}]`,
    ].join('\n');
  } else if (section === 'reading') {
    user = [
      `Write ${count} reading passages (60-120 words each) in ${langName} at CEFR ${level} level.`,
      `Content focus: ${skills}. Weekly theme: ${theme}.`,
      `Each passage has exactly ${perPassage} comprehension questions (choice with 4 options).`, fewShotJson,
      `Reply as JSON array: [{"title":"...","text":"...","items":[{"kind":"choice","prompt":"...","options":["..."],"answers":["..."],"explain":"..."}]}]`,
    ].join('\n');
  } else if (section === 'listening') {
    user = [
      `Write ${count} listening passages (40-90 words each) in ${langName} at CEFR ${level} level.`,
      `Content focus: ${skills}. Weekly theme: ${theme}.`,
      'These will be read aloud — use natural spoken language.',
      `Each passage has exactly ${perPassage} comprehension questions (choice with 4 options).`, fewShotJson,
      `Reply as JSON array: [{"text":"...","items":[{"kind":"choice","prompt":"...","options":["..."],"answers":["..."],"explain":"..."}]}]`,
    ].join('\n');
  } else {
    user = [
      `Write ${count} short production items at CEFR ${level} level in ${langName}.`,
      `Content focus: ${skills}. Weekly theme: ${theme}.`,
      `Each item gives an instruction in the learner's language and expects a short answer in ${langName}.`,
      'Accept 1-3 valid variants per item.', fewShotJson,
      `Reply as JSON array: [{"kind":"written","prompt":"Write...","answers":["variant1","variant2"],"explain":"..."}]`,
    ].join('\n');
  }
  return [{ role: 'system', content: system }, { role: 'user', content: user }];
}

// ---- per-section generation ----

interface SectionResult {
  section: SectionName;
  items: LevelTestContentItem[];
  passages?: { title: string; text: string; items: LevelTestContentItem[] }[];
  validationErrors: ValidationFailure[];
}

type LlmCall = (config: ResolvedLlmConfig, messages: { role: 'system' | 'user'; content: string }[], signal?: AbortSignal) => Promise<ChatReply>;

async function generateSection(
  call: LlmCall, config: ResolvedLlmConfig, section: SectionName,
  languageID: string, level: string, model: ExamModelEntry, weekId: number,
  repairFeedback: string[] = [], signal?: AbortSignal,
): Promise<SectionResult> {
  const spec = languageID === 'en' ? sectionGenerationSpec(section, model) : SECTION_SPECS[section];
  const fewShot = generationExamples(languageID, level, section);
  const messages = buildSectionMessages(section, languageID, level, spec.count, spec.perPassage, weekId, fewShot, model, repairFeedback);
  const reply = await call(config, messages, signal);
  let parsed: unknown;
  try { parsed = extractJson(reply.content); }
  catch { parsed = undefined; }
  if (!Array.isArray(parsed) || !parsed.length) return {
    section, items: [], passages: [], validationErrors: [{ section, code: 'invalid_response', detail: 'expected a non-empty JSON array' }],
  };

  if (section === 'reading' || section === 'listening') {
    // Passages: each element has { title?, text, items: [{kind, prompt, options, answers, explain}] }
    const passages: { title: string; text: string; items: LevelTestContentItem[] }[] = [];
    const validationErrors: ValidationFailure[] = [];
    for (const passage of parsed) {
      if (!isRecord(passage)) {
        validationErrors.push({ section, code: 'passage', detail: 'expected passage object' });
        continue;
      }
      const p = passage as { title?: string; text?: string; items?: unknown[] };
      const passageText = typeof p.text === 'string' ? p.text.trim() : '';
      const rawItems = Array.isArray(p.items) ? p.items : [];
      if (!passageText || !rawItems.length) {
        validationErrors.push({ section, code: 'passage', detail: 'passage needs text and questions' });
        continue;
      }
      const passageItems: LevelTestContentItem[] = rawItems.filter(isRecord).map((raw, index) => ({
        id: `pending-${index}`, section: section as LevelTestSection,
        kind: typeof raw.kind === 'string' ? raw.kind : 'choice',
        prompt: typeof raw.prompt === 'string' ? raw.prompt : '',
        options: Array.isArray(raw.options) ? raw.options.filter((v): v is string => typeof v === 'string') : [],
        answers: Array.isArray(raw.answers) ? raw.answers.filter((v): v is string => typeof v === 'string') : [],
        explain: typeof raw.explain === 'string' ? raw.explain : '',
        unitID: typeof raw.unitID === 'string' ? raw.unitID : undefined,
      }));
      if (passageItems.length !== rawItems.length) validationErrors.push({ section, code: 'item', detail: 'passage contains a non-object question' });
      if (passageItems.length < 2) {
        validationErrors.push({ section, code: 'passage', detail: 'passage has too few questions' });
        continue;
      }
      passages.push({ title: p.title ?? '', text: passageText, items: passageItems });
    }
    if (passages.length < spec.count) {
      validationErrors.push({ section, code: 'minimum', detail: `${passages.length} valid passages` });
    }
    return { section, items: [], passages, validationErrors };
  }

  // recognition / writing: flat item arrays
  const validationErrors: ValidationFailure[] = [];
  const items: LevelTestContentItem[] = parsed.filter(isRecord).map((item, index) => ({
    id: `pending-${index}`,
    section: section as LevelTestSection,
    kind: typeof item.kind === 'string' ? item.kind : 'choice',
    prompt: typeof item.prompt === 'string' ? item.prompt : '',
    options: Array.isArray(item.options) ? item.options.filter((v): v is string => typeof v === 'string') : [],
    answers: Array.isArray(item.answers) ? item.answers.filter((v): v is string => typeof v === 'string') : [],
    explain: typeof item.explain === 'string' ? item.explain : '',
    unitID: typeof item.unitID === 'string' ? item.unitID : undefined,
  }));
  if (items.length !== parsed.length) validationErrors.push({ section, code: 'item', detail: 'section contains a non-object item' });
  return { section, items, validationErrors };
}

export async function retryFailedSectionsOnce(failures: ValidationFailure[],
  repair: (section: SectionName, feedback: string[]) => Promise<void>): Promise<void> {
  const feedbackBySection = new Map<SectionName, string[]>();
  for (const failure of failures) {
    const match = /^(recognition|reading|listening|writing)(?:\[\d+\])?$/.exec(failure.section);
    const section = match?.[1] as SectionName | undefined;
    if (!section) continue;
    const entries = feedbackBySection.get(section) ?? [];
    const feedback = `${failure.section}.${failure.code}`;
    if (!entries.includes(feedback)) entries.push(feedback);
    feedbackBySection.set(section, entries);
  }
  for (const section of SECTION_ORDER) {
    const feedback = feedbackBySection.get(section);
    if (feedback?.length) await repair(section, feedback);
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

// ---- section judge ----

async function judgeSection(
  call: LlmCall, config: ResolvedLlmConfig, section: SectionName,
  languageID: string, level: string, model: ExamModelEntry,
  items: (RawGeneratedItem & { passageText?: string })[], signal?: AbortSignal,
): Promise<{ id: string; verdict: 'ok' | 'flagged' | 'reject'; reason?: string }[]> {
  const reply = await call(config, buildJudgeMessages(section, languageID, level, items, model), signal);
  return parseVerdicts(reply);
}

// ---- orchestration ----

export interface GenerateDeps {
  db: Database; env: NodeJS.ProcessEnv;
  transport?: LlmCall;
  now?: Date;
}

export type GenerationOrigin = 'user_operator' | 'automatic_worker';
export interface GenerateOptions {
  languageID: string; level: string;
  weekIndex?: number; force?: boolean; manual?: boolean; now?: Date; signal?: AbortSignal;
  /** Who asked for this generation. Required so every caller decides explicitly. */
  origin: GenerationOrigin;
}
export interface GenerationOutcome {
  status: 'approved' | 'draft' | 'exists' | 'failed';
  languageID: string; level: string; weekId: number;
  variants?: number; tokensIn?: number; tokensOut?: number; errors?: string[];
}

export async function generateWeeklyLevelTest(deps: GenerateDeps, options: GenerateOptions): Promise<GenerationOutcome> {
  const { languageID, level } = options;
  const weekId = options.weekIndex ?? weekIndex(options.now ?? new Date());
  const model = examModelFor(level, languageID);
  if (!model) return { status: 'failed', languageID, level, weekId, errors: ['no exam model'] };

  const existing = await currentWeekRow(deps.db, languageID, level, weekId);
  if (existing && !options.force)
    return { status: 'exists', languageID, level, weekId, variants: (existing.variants as unknown[]).length };

  const config = await resolveLlmConfig(deps.db, deps.env);
  if (!config) return { status: 'failed', languageID, level, weekId, errors: ['no llm configuration'] };
  // One stable gateway session per generation run; reused as the generation id.
  config.sessionID = randomUUID();
  const providerCall = deps.transport ?? ((resolved, messages, signal) => chatCompletion(resolved, messages, fetch, signal));
  let tokensIn = 0, tokensOut = 0, usageComplete = true;
  const call: LlmCall = async (resolved, messages, signal) => {
    const reply = await providerCall(resolved, messages, signal);
    if (typeof reply.tokensIn === 'number' && typeof reply.tokensOut === 'number') {
      tokensIn += reply.tokensIn;
      tokensOut += reply.tokensOut;
    } else {
      usageComplete = false;
    }
    return reply;
  };

  // ---- generate sections independently ----
  const sectionResults = new Map<SectionName, SectionResult>();
  for (const section of SECTION_ORDER) {
    try {
      sectionResults.set(section, await generateSection(call, config, section, languageID, level, model, weekId, [], options.signal));
    } catch (error) {
      const outcomeUnknown = options.signal?.aborted || (error instanceof LlmTransportError && error.code === 'llm_outcome_unknown');
      return { status: 'failed', languageID, level, weekId,
        errors: [outcomeUnknown ? 'llm_outcome_unknown' : `${section}:provider_failed`] };
    }
  }

  // ---- combine into a pool ----
  const buildPool = () => poolFromSectionResults(sectionResults);
  const validateCandidate = (candidate: GeneratedPool, targetModel: ExamModelEntry = model) => {
    return validateGeneratedPool({ recognition: candidate.recognition, writing: candidate.writing,
      reading: candidate.readingPassages, listening: candidate.listeningPassages }, targetModel.examShape, languageID,
      languageID === 'en' ? targetModel : undefined);
  };
  const validate = () => {
    const candidate = buildPool();
    assignProvisionalIds(candidate);
    return validateCandidate(candidate);
  };
  const initial = validate();
  const initialFailures = [...initial.errors, ...[...sectionResults.values()].flatMap(result => result.validationErrors)];
  try {
    await retryFailedSectionsOnce(initialFailures, async (section, feedback) => {
      const repaired = await generateSection(call, config, section, languageID, level, model, weekId, feedback, options.signal);
      sectionResults.set(section, repaired);
    });
  } catch (error) {
    const outcomeUnknown = options.signal?.aborted || (error instanceof LlmTransportError && error.code === 'llm_outcome_unknown');
    return { status: 'failed', languageID, level, weekId,
      errors: [outcomeUnknown ? 'llm_outcome_unknown' : 'section_repair_failed'] };
  }
  const final = validate();
  const finalFailures = [...final.errors, ...[...sectionResults.values()].flatMap(result => result.validationErrors)];
  if (!final.ok || finalFailures.length) return { status: 'failed', languageID, level, weekId, tokensIn, tokensOut,
    errors: finalFailures.map(error => `${error.section}: ${error.code}: ${error.detail}`) };
  let pool = final.pool;

  const settings = await levelTestSettings(deps.db, deps.env);
  const review: JudgeVerdict[] = [];
  if (settings.verifyMode !== 'off') for (const section of SECTION_ORDER) {
    const items = sectionItems(pool, section);
    try {
      const verdicts = await judgeSection(call, config, section, languageID, level, model, items, options.signal);
      const expected = new Set(items.map(item => item.id)), seen = new Set<string>();
      for (const verdict of verdicts) {
        if (!expected.has(verdict.id) || seen.has(verdict.id)) throw new Error('judge_invalid_ids');
        seen.add(verdict.id);
      }
      const missing = items.filter(item => !seen.has(item.id!));
      if (missing.length && settings.verifyMode === 'strict') throw new Error('judge_incomplete');
      review.push(...verdicts, ...missing.map(item => ({ id: item.id!, verdict: 'flagged' as const, reason: 'judge_incomplete' })));
      // English flagged content is excluded just like a rejection; it is not safe evidence for an exam.
    } catch (error) {
      if (options.signal?.aborted || (error instanceof LlmTransportError && error.code === "llm_outcome_unknown"))
        return { status: "failed", languageID, level, weekId, tokensIn, tokensOut, errors: ["llm_outcome_unknown"] };
      if (settings.verifyMode === "strict") return { status: "failed", languageID, level, weekId,
        tokensIn, tokensOut, errors: [`${section}: judge_failed_or_incomplete`] };
      review.push(...items.map(item => ({ id: item.id!, verdict: 'flagged' as const, reason: 'judge_unavailable' })));
    }
  }
  const hasUntrustedEnglishItems = languageID === 'en' && review.some(verdict => verdict.verdict === 'flagged' || verdict.verdict === 'reject');
  const hasRejectedItems = review.some(verdict => verdict.verdict === 'reject');
  if (hasRejectedItems || hasUntrustedEnglishItems) {
    pool = excludeJudgeUntrustedItems(pool, review, languageID === 'en');
    const compositionModel = languageID === 'en' ? { ...model, poolTarget: {
      recognition: model.examShape.recognition, writing: model.examShape.writing,
      readingPassages: model.examShape.readingPassages, listeningPassages: model.examShape.listeningPassages,
    } } : model;
    const trimmed = validateCandidate(pool, compositionModel);
    if (!trimmed.ok) return { status: 'failed', languageID, level, weekId, tokensIn, tokensOut,
      errors: trimmed.errors.map(error => `${error.section}: ${error.code}: ${error.detail}`) };
    pool = trimmed.pool;
  }

  const generationId = config.sessionID!;
  const variants = composeVariants(pool, model, weekId, settings.variants).map(variant =>
    identifySnapshot({ schemaVersion: 1, languageID, level, version: 1, weekId,
      expiresAt: expiresAt(options.now ?? new Date()), thresholds: thresholdsFor(languageID, level),
      ...variant }, 'llm', generationId));
  if (languageID === 'en' && new Set(variants.map(variant => variant.items.map(item => item.id).sort().join(','))).size < variants.length)
    return { status: 'failed', languageID, level, weekId, tokensIn, tokensOut, errors: ['insufficient_variant_diversity'] };
  const status = settings.publishMode === 'auto' ? 'approved' : 'draft';
  const approvedBy = settings.publishMode === 'auto' ? 'system:auto' : null;

  await persistWeek(deps.db, { generationId, languageID, level, weekId, pool, variants, status,
    model: config.model, generator: `llm:${config.provider}`, review,
    tokensIn: usageComplete ? tokensIn : null, tokensOut: usageComplete ? tokensOut : null, approvedBy, verifyMode: settings.verifyMode, force: options.force === true,
    origin: options.origin });

  return { status: status as 'approved' | 'draft', languageID, level, weekId,
    variants: variants.length, ...(usageComplete ? { tokensIn, tokensOut } : {}) };
}

function sectionItems(pool: GeneratedPool, section: SectionName): (RawGeneratedItem & { passageText?: string })[] {
  if (section === 'recognition') return pool.recognition;
  if (section === 'writing') return pool.writing;
  if (section === 'reading') return pool.readingPassages.flatMap(p => p.items.map(item => ({ ...item, passageText: p.text })));
  if (section === 'listening') return pool.listeningPassages.flatMap(p => p.items.map(item => ({ ...item, passageText: p.text })));
  return [];
}

function poolFromSectionResults(results: Map<SectionName, SectionResult>): GeneratedPool {
  return {
    recognition: results.get('recognition')?.items ?? [],
    writing: results.get('writing')?.items ?? [],
    readingPassages: results.get('reading')?.passages ?? [],
    listeningPassages: results.get('listening')?.passages ?? [],
  };
}

export function excludeJudgeUntrustedItems(pool: GeneratedPool,
  verdicts: { id: string; verdict: 'ok' | 'flagged' | 'reject' }[], excludeFlagged: boolean): GeneratedPool {
  const excluded = new Set(verdicts.filter(verdict => verdict.verdict === 'reject' ||
    (excludeFlagged && verdict.verdict === 'flagged')).map(verdict => verdict.id));
  return {
    recognition: pool.recognition.filter(item => !excluded.has(item.id)),
    writing: pool.writing.filter(item => !excluded.has(item.id)),
    readingPassages: pool.readingPassages.filter(passage => passage.items.every(item => !excluded.has(item.id))),
    listeningPassages: pool.listeningPassages.filter(passage => passage.items.every(item => !excluded.has(item.id))),
  };
}

export function buildJudgeMessages(section: SectionName, languageID: string, level: string,
  items: (RawGeneratedItem & { passageText?: string })[], model: ExamModelEntry) {
  const langName = LANGUAGE_NAMES[languageID] ?? languageID;
  const english = languageID === 'en';
  const system = english ? [
    `You are a careful quality reviewer for English CEFR ${level} ${section} exam items.`,
    `Use this level anchor: ${model.canDoAnchor}`,
    'For every item, verify that exactly one marked answer is correct and no distractor is also defensible.',
    'For comprehension, verify the answer is supported by the supplied passage and the question does not require outside knowledge.',
    'Check that the explanation is accurate and supports the key; check distractors are plausible but clearly wrong.',
    'Check the whole task and language demand fit the level anchor and the section format; advanced vocabulary alone is not evidence of a higher level.',
    `Reject items that rely on these unsuitable/out-of-level targets: ${model.forbidden.join('; ')}.`,
    'Use ok only when all checks pass; use flagged when a material concern cannot be resolved; reject when incorrect, ambiguous, unsupported, or out of scope.',
    'Reply ONLY JSON: {"items":[{"id":"<as given>","verdict":"ok"|"flagged"|"reject","reason":"one short evidence-based sentence"}]}. Return exactly one verdict for every supplied id.',
  ].join(' ') : `You are a strict exam reviewer for CEFR ${level} ${langName} tests. For EVERY item decide: is the marked answer uniquely correct, are all distractors clearly wrong, is the difficulty plausible? Reply ONLY JSON: {"items":[{"id":"...","verdict":"ok"|"flagged"|"reject","reason":"one sentence"}]}.`;
  const user = JSON.stringify(english ? {
    section, canDoAnchor: model.canDoAnchor, sectionBlueprint: model.sections[section],
    forbidden: model.forbidden,
    items: items.map(item => ({ id: item.id, kind: item.kind, prompt: item.prompt,
      options: item.options, answers: item.answers, explain: item.explain, passageText: item.passageText })),
  } : items.map(item => ({ id: item.id, kind: item.kind, prompt: item.prompt,
    options: item.options, answers: item.answers, explain: item.explain, passageText: item.passageText })));
  return [{ role: 'system' as const, content: system }, { role: 'user' as const, content: user }];
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
export function composeVariants(pool: GeneratedPool, model: ExamModelEntry, weekId: number, count: number): GeneratedVariant[] {
  if (model.languageID !== 'en') return composeLegacyVariants(pool, model, weekId, count);
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
    const recognition = stableBlock(flat.filter(item => item.section === 'recognition'),
      'recognition', shape.recognition, weekId, variant, count);
    const writing = stableBlock(flat.filter(item => item.section === 'writing'),
      'writing', shape.writing, weekId, variant, count);
    const readingOrder = stablePassageOrder(flat, 'reading', shape.readingPerPassage, weekId)
      .filter(passage => passage.id.startsWith('llm-reading-'));
    const readingIds = stableSelection(readingOrder.map(passage => passage.id), shape.readingPassages, weekId, variant, count);
    const listeningOrder = stablePassageOrder(flat, 'listening', shape.listeningPerPassage, weekId)
      .filter(passage => !readingIds.includes(passage.id));
    const listeningIds = stableSelection(listeningOrder.map(passage => passage.id), shape.listeningPassages, weekId, variant, count);
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

function composeLegacyVariants(pool: GeneratedPool, model: ExamModelEntry, weekId: number, count: number): GeneratedVariant[] {
  const variants: GeneratedVariant[] = [];
  const flat = flattenPool(pool);
  const shape = { recognition: model.examShape.recognition ?? 12, writing: model.examShape.writing ?? 3,
    readingPassages: model.examShape.readingPassages ?? 2, readingPerPassage: model.examShape.readingPerPassage ?? 3,
    listeningPassages: model.examShape.listeningPassages ?? 2, listeningPerPassage: model.examShape.listeningPerPassage ?? 2 };
  const passages = [
    ...pool.readingPassages.map((passage, index) => ({ id: `llm-reading-${index + 1}`, title: passage.title, text: passage.text })),
    ...pool.listeningPassages.map((passage, index) => ({ id: `llm-listening-${index + 1}`, title: passage.title, text: passage.text })),
  ];
  for (let variant = 1; variant <= count; variant++) {
    const salt = `v${variant}`;
    const recognition = legacyStableBlock(flat.filter(item => item.section === 'recognition'), salt, shape.recognition, weekId);
    const writing = legacyStableBlock(flat.filter(item => item.section === 'writing'), salt, shape.writing, weekId);
    const readingOrder = legacyPassageOrder(flat, 'reading', shape.readingPerPassage, salt).filter(p => p.id.startsWith('llm-reading-'));
    const readingChunks = legacyChunk(readingOrder, shape.readingPassages);
    const readingBlock = readingChunks[weekId % readingChunks.length] ?? readingOrder;
    const readingIds = (readingBlock.length === shape.readingPassages ? readingBlock : readingOrder.slice(0, shape.readingPassages)).map(p => p.id);
    const listeningOrder = legacyPassageOrder(flat, 'listening', shape.listeningPerPassage, salt).filter(p => !readingIds.includes(p.id));
    const listeningChunks = legacyChunk(listeningOrder, shape.listeningPassages);
    const listeningBlock = listeningChunks[weekId % listeningChunks.length] ?? listeningOrder;
    const listeningIds = (listeningBlock.length === shape.listeningPassages ? listeningBlock : listeningOrder.slice(0, shape.listeningPassages)).map(p => p.id);
    const items = [...recognition, ...readingIds.flatMap(id => flat.filter(item => item.section === 'reading' && item.passageId === id).slice(0, shape.readingPerPassage)),
      ...listeningIds.flatMap(id => flat.filter(item => item.section === 'listening' && item.passageId === id).slice(0, shape.listeningPerPassage)), ...writing];
    variants.push({ variant, items, passages: passages.filter(p => readingIds.includes(p.id) || listeningIds.includes(p.id)),
      readingPassageIds: readingIds, listeningPassageIds: listeningIds });
  }
  return variants;
}
function legacyChunk<T>(items: T[], size: number): T[][] {
  const chunks: T[][] = [];
  for (let i=0; i<items.length; i+=size) chunks.push(items.slice(i,i+size));
  return chunks;
}
function legacyStableBlock(items: FlatItem[], salt: string, count: number, weekId: number): FlatItem[] {
  const ordered = [...items].sort((a,b) => hashOf(salt,a.id).localeCompare(hashOf(salt,b.id)));
  const blocks: FlatItem[][] = [];
  for (let start=0; start<ordered.length; start+=count) blocks.push(ordered.slice(start,start+count));
  const block = blocks[weekId % blocks.length] ?? ordered;
  return block.length === count ? block : ordered.slice(0,count);
}
function legacyPassageOrder(items: FlatItem[], section: string, perPassage: number, salt: string): {id:string}[] {
  const counts = new Map<string,number>();
  for (const item of items) if(item.section===section && item.passageId) counts.set(item.passageId,(counts.get(item.passageId)??0)+1);
  return [...counts.entries()].filter(([,count])=>count>=perPassage).map(([id])=>({id}))
    .sort((a,b)=>hashOf(salt,a.id).localeCompare(hashOf(salt,b.id)));
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

function stableBlock(items: FlatItem[], section: string, count: number, weekId: number,
  variant: number, variantCount: number): FlatItem[] {
  const ordered = [...items].sort((a, b) => codepointCompare(hashOf(`${section}:${weekId}`, a.id), hashOf(`${section}:${weekId}`, b.id)));
  return stableSelection(ordered, count, weekId, variant, variantCount);
}

function stablePassageOrder(items: FlatItem[], section: string, perPassage: number, weekId: number):
  { id: string }[] {
  const counts = new Map<string, number>();
  for (const item of items) if (item.section === section && item.passageId)
    counts.set(item.passageId, (counts.get(item.passageId) ?? 0) + 1);
  return [...counts.entries()].filter(([, count]) => count >= perPassage)
    .map(([id]) => ({ id }))
    .sort((a, b) => codepointCompare(hashOf(`${section}:${weekId}`, a.id), hashOf(`${section}:${weekId}`, b.id)));
}

function stableSelection<T>(ordered: T[], count: number, _weekId: number, variant: number, variantCount: number): T[] {
  if (ordered.length <= count) return ordered.slice(0, count);
  const start = Math.floor((variant - 1) * ordered.length / Math.max(variantCount, 1)) % ordered.length;
  return Array.from({ length: count }, (_, offset) => ordered[(start + offset) % ordered.length]!);
}

function codepointCompare(a: string, b: string): number { return a < b ? -1 : a > b ? 1 : 0; }

function hashOf(salt: string, id: string): string {
  return createHash('sha256').update(`${salt}|${id}`).digest('hex');
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
  tokensIn: number | null; tokensOut: number | null; approvedBy: string | null;
  verifyMode: string; force: boolean; origin: GenerationOrigin;
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
      generator, model, prompt_version, judge_version, judge_mode, review, tokens_in, tokens_out, approved_at, approved_by, origin)
      VALUES($1,$2,$3,$4,'llm',$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17,$18)`,
      [input.generationId, input.languageID, input.level, input.weekId, input.status, JSON.stringify(input.pool),
       JSON.stringify(input.variants), input.generator, input.model, PROMPT_VERSION, JUDGE_VERSION,
       input.verifyMode, JSON.stringify(input.review ?? []), input.tokensIn, input.tokensOut,
       input.status === 'approved' ? new Date() : null, input.approvedBy, input.origin]);
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
