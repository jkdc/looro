import { validAssessmentContract } from './level-test-assessment.js';
import { createHash } from 'node:crypto';
import type { ExamModelEntry } from './level-test-blueprint.js';

export const FROZEN_BLUEPRINT_VERSION = 2;

/** Canonical object-key order is independent of PostgreSQL JSONB order. */
function canonical(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(canonical);
  if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value)
    .sort(([a], [b]) => a < b ? -1 : a > b ? 1 : 0).map(([key, entry]) => [key, canonical(entry)]));
  return value;
}

export function freezeBlueprint(model: ExamModelEntry) {
  const contract = structuredClone(model);
  const hash = createHash('sha256').update(JSON.stringify(canonical(contract))).digest('hex');
  return { contract, hash, version: FROZEN_BLUEPRINT_VERSION };
}

/** Never reconstruct a missing historical contract from today's model. */
export function readFrozenBlueprint(value: unknown, version: unknown, hash: unknown,
  languageID: string, level: string): ExamModelEntry | undefined {
  try {
    if (version !== FROZEN_BLUEPRINT_VERSION || typeof hash !== 'string' || !/^[a-f0-9]{64}$/.test(hash) ||
        !value || typeof value !== 'object' || Array.isArray(value)) return undefined;
    const model = value as ExamModelEntry;
    if (model.languageID !== languageID || model.level !== level || typeof model.canDoAnchor !== 'string' ||
        !Array.isArray(model.forbidden) || !model.forbidden.every(entry => typeof entry === 'string')) return undefined;
    const positiveInteger = (n: unknown) => Number.isSafeInteger(n) && (n as number) > 0;
    if (!model.examShape || !model.poolTarget || !model.sections ||
        !['recognition', 'writing', 'readingPassages', 'readingPerPassage', 'listeningPassages', 'listeningPerPassage']
          .every(key => positiveInteger(model.examShape[key as keyof typeof model.examShape])) ||
        !['recognition', 'writing', 'readingPassages', 'listeningPassages']
          .every(key => positiveInteger(model.poolTarget[key as keyof typeof model.poolTarget]))) return undefined;
    for (const section of ['recognition', 'writing', 'reading', 'listening'] as const) {
      const entry = model.sections[section];
      if (!entry || typeof entry !== 'object' || Array.isArray(entry)) return undefined;
      if (entry.skills && (!Array.isArray(entry.skills) || !entry.skills.every(skill => typeof skill === 'string'))) return undefined;
      if (entry.words && (!Array.isArray(entry.words) || entry.words.length !== 2 ||
          !entry.words.every(positiveInteger) || entry.words[0] > entry.words[1])) return undefined;
    }
    if (model.assessment && !validAssessmentContract(model.assessment,model)) return undefined;
    if (freezeBlueprint(model).hash !== hash) return undefined;
    return structuredClone(model);
  } catch { return undefined; }
}
