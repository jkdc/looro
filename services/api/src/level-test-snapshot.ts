import { createHash } from 'node:crypto';
import type { LevelTestContentItem, LevelTestContentPassage } from './level-tests-content.generated.js';

/** Existing wire fields remain additive-compatible with schema version 1.
    Content revision and grading revision describe different, immutable contracts. */
export interface SnapshotContent {
  schemaVersion: number; languageID: string; level: string; version: number;
  weekId: number; expiresAt: string; thresholds: Record<string, number>;
  passages: LevelTestContentPassage[]; readingPassageIds: string[];
  listeningPassageIds: string[]; items: LevelTestContentItem[]; variant?: number;
}
export interface SnapshotMetadata {
  examId: string; contentId: string; contentHash: string;
  contentRevision: number; gradingVersion: number;
  source: 'deterministic' | 'llm'; generationId?: string;
}
export type ExamSnapshot = SnapshotContent & SnapshotMetadata;

// Sort object keys recursively; array order is learner-visible and MUST remain intact.
// JSON serialization omits undefined object fields, matching the response/storage bytes.
function canonical(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(canonical);
  if (value !== null && typeof value === 'object') return Object.fromEntries(
    Object.entries(value).filter(([, entry]) => entry !== undefined)
      .sort(([a], [b]) => a < b ? -1 : a > b ? 1 : 0)
      .map(([key, entry]) => [key, canonical(entry)]));
  return value;
}

/** Detach from mutable bundled pools before hashing. A generation UUID identifies
    publication lineage; contentId deduplicates identical payloads across generations.
    This does not register a historical lookup route or confer publication authority. */
export function identifySnapshot<T extends SnapshotContent>(exam: T,
    source: SnapshotMetadata['source'], generationId?: string): T & SnapshotMetadata {
  const { examId: _examId, contentId: _contentId, contentHash: _contentHash,
    generationId: _generationId, source: _source, contentRevision = exam.version,
    gradingVersion = 1, ...payload } = structuredClone(exam) as T & Partial<SnapshotMetadata>;
  const content = { ...payload, contentRevision, gradingVersion, source };
  const contentHash = createHash('sha256').update(JSON.stringify(canonical(content))).digest('hex');
  const contentId = `sha256:${contentHash}`;
  return { ...content, contentHash, contentId,
    examId: generationId ? `${generationId}:v${exam.variant}` : contentId,
    ...(generationId ? { generationId } : {}) } as T & SnapshotMetadata;
}
