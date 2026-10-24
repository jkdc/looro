import type { LevelTestContentItem, LevelTestSection } from './level-tests-content.generated.js';
import type { ExamModelEntry, ExamSectionName, ExamShape } from './level-test-blueprint.js';

/** Gate 1 of the LLM generation pipeline (level-tests-llm): deterministic
    validation of a generated pool. Mirrors the exporter's rules — schema,
    exactly one correct choice answer among its options, passage word ranges,
    disjoint reading/listening, in-generation dedupe, section minimums — and
    reports per-section errors so generation can retry one section without
    discarding the rest. Pure and synchronous; no LLM, no network. */

export interface RawGeneratedItem {
  id?: string | null;
  section: LevelTestSection | string;
  kind?: string;
  skill?: string;
  prompt?: string;
  options?: string[] | null;
  answers?: string[] | null;
  explain?: string | null;
  unitID?: string | null;
  passageId?: string | null;
  speech?: string | null;
  title?: string | null;
  text?: string | null;
}

export interface RawGeneratedPassage {
  id?: string | null;
  title?: string | null;
  text?: string | null;
  items?: RawGeneratedItem[] | null;
}

export interface GeneratedPool {
  recognition: LevelTestContentItem[];
  writing: LevelTestContentItem[];
  readingPassages: { title: string; text: string; items: LevelTestContentItem[] }[];
  listeningPassages: { title: string; text: string; items: LevelTestContentItem[] }[];
}

export interface ValidationFailure { section: string; code: string; detail: string }
export interface ValidationResult { ok: boolean; errors: ValidationFailure[]; pool: GeneratedPool }

const MINIMUMS = { recognition: 12, writing: 3, readingPassages: 2, listeningPassages: 2 };
const WORDS = (text: string) => text.trim().split(/\s+/).filter(Boolean).length;
const normalize = (text: string) => text.toLowerCase().replace(/[.,!?;:¿¡"“”«»…()]/g, ' ').replace(/\s+/g, ' ').trim();

export function validateGeneratedPool(pool: unknown, examShape: Partial<ExamShape>,
  targetLanguage: string, blueprint?: ExamModelEntry): ValidationResult {
  const shape = {
    recognition: examShape.recognition ?? 12, writing: examShape.writing ?? 3,
    readingPassages: examShape.readingPassages ?? 2, readingPerPassage: examShape.readingPerPassage ?? 3,
    listeningPassages: examShape.listeningPassages ?? 2, listeningPerPassage: examShape.listeningPerPassage ?? 2,
  };
  const errors: ValidationFailure[] = [];
  const clean: GeneratedPool = { recognition: [], writing: [], readingPassages: [], listeningPassages: [] };
  const raw = record(pool);
  if (!raw) errors.push({ section: 'pool', code: 'object', detail: 'expected a pool object' });
  const recognition = arrayed(raw?.recognition);
  const writing = arrayed(raw?.writing);
  const readingPassages = arrayed(raw?.reading);
  const listeningPassages = arrayed(raw?.listening);

  const seenPrompts = new Set<string>();
  const seenIds = new Set<string>();
  const acceptItem = (section: LevelTestContentItem['section'], bucket: LevelTestContentItem[], value: unknown, index: number): boolean => {
    const where = `${section}[${index}]`;
    const fields = record(value);
    if (!fields) return fail(where, 'item', 'expected an item object');
    const item = fields as unknown as RawGeneratedItem;
    const kind = item.kind ?? (Array.isArray(item.options) ? 'choice' : 'written');
    if (!['choice', 'gap', 'written', 'order'].includes(kind)) return fail(where, 'kind', 'unsupported exercise kind');
    if (targetLanguage === 'en' && !englishSectionKinds(section).includes(kind as string))
      return fail(where, 'kind', 'exercise kind is not permitted in this section');
    if (item.id && item.id !== 'pending' && seenIds.has(item.id)) return fail(where, 'id', 'duplicate item id');
    const prompt = typeof item.prompt === 'string' ? item.prompt.trim() : '';
    const answers = strings(item.answers);
    if (!prompt || prompt.length > 500) return fail(where, 'prompt', 'missing or oversized prompt');
    const fingerprint = normalize(prompt).slice(0, 160);
    if (seenPrompts.has(fingerprint)) return fail(where, 'duplicate', 'repeated prompt inside the generation');
    const options = strings(item.options);
    if (kind === 'choice') {
      if (options.length < 3) return fail(where, 'options', 'fewer than 3 options');
      if (new Set(options.map(o => normalize(o))).size !== options.length) return fail(where, 'options', 'duplicated options');
      if (answers.length !== 1) return fail(where, 'answer', 'choice needs exactly one correct answer');
      const correct = strings(item.answers)[0] ?? '';
      if (!options.includes(correct)) return fail(where, 'answer', 'the correct answer is not among the options');
    } else {
      if (!answers.length) return fail(where, 'answer', 'no accepted answers');
      if (answers.length > 4) return fail(where, 'answer', 'too many accepted answers');
      if (kind === 'order' && (answers.length !== 1 || WORDS(answers[0] ?? '') < 3))
        return fail(where, 'answer', 'order exercises need exactly one answer of at least three words');
    }
    const explain = typeof item.explain === 'string' ? item.explain.trim() : '';
    if (explain.length < 8) return fail(where, 'explain', 'missing or too short');
    if (!looksLikeTargetLanguage(`${prompt} ${answers.join(' ')}`, targetLanguage))
      return fail(where, 'language', 'the item does not look like the target language');
    seenPrompts.add(fingerprint);
    if (item.id) seenIds.add(item.id);
    bucket.push({
      id: item.id ?? 'pending', section: section as LevelTestSection, kind, prompt,
      unitID: item.unitID ?? undefined, passageId: item.passageId ?? undefined, speech: item.speech ?? undefined,
      options: kind === 'choice' ? options : [], answers, explain,
    });
    return true;
  };

  const recognitionBucket: LevelTestContentItem[] = [], writingBucket: LevelTestContentItem[] = [];
  recognition.forEach((item, index) => acceptItem('recognition', recognitionBucket, item, index));
  writing.forEach((item, index) => acceptItem('writing', writingBucket, item, index));
  clean.recognition = recognitionBucket;
  clean.writing = writingBucket;

  const seenPassageTexts = { reading: new Set<string>(), listening: new Set<string>() };
  const acceptPassage = (kind: 'reading' | 'listening', minWords: number, maxWords: number,
                         minItems: number, value: unknown, index: number): boolean => {
    const where = `${kind}[${index}]`;
    const fields = record(value);
    if (!fields) return fail(where, 'passage', 'expected a passage object');
    const passage = fields as RawGeneratedPassage;
    const text = typeof passage.text === 'string' ? passage.text.trim() : '';
    const words = text ? WORDS(text) : 0;
    const bucket: LevelTestContentItem[] = [];
    arrayed(passage.items).forEach((item, position) => acceptItem(kind, bucket, item, position));
    if (words < minWords || words > maxWords)
      return fail(where, 'passage', `passage length out of range (${words} words)`);
    if (bucket.length < minItems) return fail(where, 'passage', 'fewer valid questions than required');
    const fingerprint = normalize(text);
    if (targetLanguage === 'en' && seenPassageTexts[kind].has(fingerprint))
      return fail(where, 'duplicate_passage', 'repeated passage text within the same section');
    if (targetLanguage === 'en') seenPassageTexts[kind].add(fingerprint);
    const target = kind === 'reading' ? clean.readingPassages : clean.listeningPassages;
    target.push({ title: typeof passage.title === 'string' ? passage.title : '', text, items: bucket });
    return true;
  };
  const readingWords = blueprint?.sections.reading?.words ?? [30, 220];
  const listeningWords = blueprint?.sections.listening?.words ?? [20, 200];
  readingPassages.forEach((passage, index) => acceptPassage('reading', readingWords[0], readingWords[1], shape.readingPerPassage, passage, index));
  listeningPassages.forEach((passage, index) => acceptPassage('listening', listeningWords[0], listeningWords[1], shape.listeningPerPassage, passage, index));

  // Section minimums: the exam shape must be coverable by the validated pool.
  const recognitionTarget = targetLanguage === 'en' && blueprint ? blueprint.poolTarget.recognition : shape.recognition;
  const writingTarget = targetLanguage === 'en' && blueprint ? blueprint.poolTarget.writing : shape.writing;
  const readingTarget = targetLanguage === 'en' && blueprint ? blueprint.poolTarget.readingPassages : shape.readingPassages;
  const listeningTarget = targetLanguage === 'en' && blueprint ? blueprint.poolTarget.listeningPassages : shape.listeningPassages;
  if (clean.recognition.length < Math.max(MINIMUMS.recognition, recognitionTarget))
    errors.push({ section: 'recognition', code: 'minimum', detail: `${clean.recognition.length} valid items` });
  if (clean.writing.length < Math.max(MINIMUMS.writing, writingTarget))
    errors.push({ section: 'writing', code: 'minimum', detail: `${clean.writing.length} valid items` });
  if (clean.readingPassages.length < Math.max(MINIMUMS.readingPassages, readingTarget))
    errors.push({ section: 'reading', code: 'minimum', detail: `${clean.readingPassages.length} valid passages` });
  if (clean.listeningPassages.length < Math.max(MINIMUMS.listeningPassages, listeningTarget))
    errors.push({ section: 'listening', code: 'minimum', detail: `${clean.listeningPassages.length} valid passages` });

  // Disjoint blocks per exam are enforced at composition; here we also refuse
  // pools where reading and listening drew on the same source text.
  const readingTexts = new Set(clean.readingPassages.map(p => normalize(p.text ?? '')));
  for (const passage of clean.listeningPassages)
    if (readingTexts.has(normalize(passage.text ?? '')))
      errors.push({ section: 'listening', code: 'overlap', detail: 'a listening passage duplicates a reading passage' });

  return { ok: errors.length === 0, errors, pool: clean };

  function fail(section: string, code: string, detail: string): boolean {
    errors.push({ section, code, detail });
    return false;
  }
}

function record(value: unknown): Record<string, unknown> | undefined {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
    ? value as Record<string, unknown> : undefined;
}

function arrayed(value: unknown): unknown[] {
  return Array.isArray(value) ? value : [];
}

function englishSectionKinds(section: ExamSectionName): string[] {
  switch (section) {
    case 'recognition': return ['choice', 'gap'];
    case 'writing': return ['gap', 'written', 'order'];
    case 'reading':
    case 'listening': return ['choice', 'gap'];
  }
}

function strings(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((entry): entry is string => typeof entry === 'string' && entry.trim().length > 0) : [];
}

/** Stopword heuristic: count target-language vs instruction-language markers. */
const TARGET_MARKERS: Record<string, RegExp> = {
  en: /\b(the|is|are|was|were|has|have|do|does|did|will|would|can)\b/gi,
  es: /\b(el|la|los|las|que|de|para|con|está|son|fue|había|pero|muy)\b/gi,
  fr: /\b(le|la|les|est|était|pour|avec|dans|plus|que|qui|ne)\b/gi,
  de: /\b(der|die|das|ist|war|für|mit|und|nicht|auch|sich|haben)\b/gi,
  it: /\b(il|lo|la|gli|le|è|era|per|con|non|più|sono)\b/gi,
  pt: /\b(o|a|os|as|é|foi|para|com|não|mais|uma|pelo)\b/gi,
  nb: /\b(er|var|og|for|med|ikke|den|det|til|på|har|kan)\b/gi,
  zh: /[\u4e00-\u9fff]/g,
};
const INSTRUCTION_MARKERS: Record<string, RegExp> = {
  en: /\b(the|is|are|was|has|have)\b/gi,
  es: /\b(el|la|los|las|que|de|para|con)\b/gi,
  fr: /\b(le|la|les|est|pour|avec|dans)\b/gi,
  de: /\b(der|die|das|ist|für|mit|und)\b/gi,
  it: /\b(il|lo|la|le|è|per|con|non)\b/gi,
  pt: /\b(o|a|os|as|é|para|com|não)\b/gi,
  nb: /\b(er|og|for|med|ikke|den|det|til)\b/gi,
  zh: /[\u4e00-\u9fff]/g,
};

function looksLikeTargetLanguage(text: string, targetLanguage: string): boolean {
  const target = TARGET_MARKERS[targetLanguage];
  if (!target) return true;
  if (targetLanguage === 'zh') return target.test(text);
  const hits = (text.match(target) ?? []).length;
  if (hits >= 2) return true;
  const instruction = INSTRUCTION_MARKERS[targetLanguage];
  if (instruction) {
    const instructionHits = (text.match(instruction) ?? []).length;
    return instructionHits === 0 ? hits >= 1 : hits >= instructionHits;
  }
  return hits >= 1;
}
