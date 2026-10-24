import { EXAM_MODELS } from './level-tests-content.generated.js';

export type ExamSectionName = 'recognition' | 'reading' | 'listening' | 'writing';
export type ExerciseKind = 'choice' | 'gap' | 'written' | 'order';

export interface ExamShape {
  recognition: number;
  writing: number;
  readingPassages: number;
  readingPerPassage: number;
  listeningPassages: number;
  listeningPerPassage: number;
}

export interface CandidatePoolTarget {
  recognition: number;
  writing: number;
  readingPassages: number;
  listeningPassages: number;
}

export interface ExamSectionBlueprint {
  mix?: Partial<Record<ExerciseKind, number>>;
  skills?: string[];
  taskFamily?: string;
  options?: number;
  words?: [number, number];
  register?: string;
  format?: string;
  questions?: number;
  promptLanguage?: string;
  response?: string;
  /** Legacy wildcard blueprint fields retained while other languages migrate. */
  registro?: string;
  formato?: string;
  promptInInstructionLanguage?: boolean;
  answers?: string;
}

export interface ExamModelEntry {
  level: string;
  languageID: string;
  canDoAnchor: string;
  poolTarget: CandidatePoolTarget;
  examShape: ExamShape;
  sections: Partial<Record<ExamSectionName, ExamSectionBlueprint>>;
  forbidden: string[];
}

const models = EXAM_MODELS as unknown as { levels: ExamModelEntry[] };

/** Resolve an exact language override first, preserving wildcard behavior for existing languages. */
export function resolveExamBlueprint(languageID: string, level: string): ExamModelEntry | undefined {
  return models.levels.find(entry => entry.level === level && entry.languageID === languageID)
    ?? models.levels.find(entry => entry.level === level && entry.languageID === '*');
}
