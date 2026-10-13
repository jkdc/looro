import { createHash } from 'node:crypto';
import { LEVEL_TEST_BANKS, type LevelTestContentBank, type LevelTestContentItem,
  type LevelTestContentLevel, type LevelTestContentPassage } from './level-tests-content.generated.js';
import { identifySnapshot, type ExamSnapshot } from './level-test-snapshot.js';
type ContentSection = LevelTestContentItem['section'];

/** Weekly level-verification endpoint (level-tests v2): composes THIS week's
    test for a language+level with the SAME deterministic rotation the apps
    use — sha256(version|SECTION|id) stable order, ISO week picks a block,
    reading passages picked before listening so the two blocks stay disjoint.
    Golden tests on both sides (Kotlin + TypeScript) pin the shared contract,
    so a client and this server always serve the identical exam for a week.
    Grading stays on device: this endpoint delivers content, never scores. */

const RECOGNITION_COUNT = 12;
const READING_PASSAGES = 2;
const READING_PER_PASSAGE = 3;
const LISTENING_PASSAGES = 2;
const LISTENING_PER_PASSAGE = 2;
const WRITING_COUNT = 3;

export type ComposedItem = LevelTestContentItem;
export type ComposedLevelTest = ExamSnapshot;

const stableHash = (version: number, value: string): string =>
  createHash('sha256').update(`${version}|${value}`).digest('hex');

// Hex-digest comparisons are plain codepoint order, mirroring Kotlin's
// lexicographic string compareTo (localeCompare would drift with ICU).
const byString = (a: string, b: string): number => (a < b ? -1 : a > b ? 1 : 0);

const stableOrder = (items: LevelTestContentItem[], version: number): LevelTestContentItem[] =>
  [...items].sort((a, b) => byString(stableHash(version, `${a.section.toUpperCase()}|${a.id}`),
    stableHash(version, `${b.section.toUpperCase()}|${b.id}`)));

const stableBlock = (items: LevelTestContentItem[], version: number, count: number, weekIndex: number): LevelTestContentItem[] => {
  const ordered = stableOrder(items, version);
  const blocks: LevelTestContentItem[][] = [];
  for (let start = 0; start < ordered.length; start += count) blocks.push(ordered.slice(start, start + count));
  const block = blocks[weekIndex % blocks.length] ?? ordered;
  // A trailing remainder chunk is smaller than the exam needs: fall back to
  // the head of the stable order, which always has a full block.
  return block.length === count ? block : ordered.slice(0, count);
};

const stablePassages = (pool: LevelTestContentItem[], passages: LevelTestContentPassage[], version: number,
                        section: ContentSection, perPassage: number): LevelTestContentPassage[] =>
  passages.filter(passage => pool.filter(item => item.passageId === passage.id && item.section === section).length >= perPassage)
    .sort((a, b) => byString(stableHash(version, `${section.toUpperCase()}|${a.id}`),
      stableHash(version, `${section.toUpperCase()}|${b.id}`)));

/** ISO week-based year × 100 + week, mirroring Kotlin's IsoFields. */
export function weekIndex(now: Date = new Date()): number {
  const date = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate()));
  const dayNum = (date.getUTCDay() + 6) % 7;
  date.setUTCDate(date.getUTCDate() - dayNum + 3);
  const firstThursday = new Date(Date.UTC(date.getUTCFullYear(), 0, 4));
  const firstDayNum = (firstThursday.getUTCDay() + 6) % 7;
  firstThursday.setUTCDate(firstThursday.getUTCDate() - firstDayNum + 3);
  const week = 1 + Math.round((date.getTime() - firstThursday.getTime()) / (7 * 24 * 3600 * 1000));
  return date.getUTCFullYear() * 100 + week;
}

/** The Monday 00:00 UTC right after the current week — when the test rotates. */
export function expiresAt(now: Date = new Date()): string {
  const date = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate()));
  const dayNum = (date.getUTCDay() + 6) % 7;
  date.setUTCDate(date.getUTCDate() - dayNum + 7);
  return date.toISOString();
}

export function levelTestLevels(languageID: string): LevelTestContentLevel[] {
  const bank = LEVEL_TEST_BANKS.find(candidate => candidate.languageID === languageID);
  return bank?.levels ?? [];
}

export function composeWeekly(languageID: string, level: string, now: Date = new Date()): ComposedLevelTest | undefined {
  const content = levelTestLevels(languageID).find(entry => entry.level === level);
  if (!content) return undefined;
  const weekId = weekIndex(now);
  const version = content.version;

  const recognition = stableBlock(content.pool.filter(item => item.section === 'recognition'), version, RECOGNITION_COUNT, weekId);
  const writing = stableBlock(content.pool.filter(item => item.section === 'writing'), version, WRITING_COUNT, weekId);

  const readingReady = stablePassages(content.pool, content.passages, version, 'reading', READING_PER_PASSAGE);
  const readingBlocks: LevelTestContentPassage[][] = [];
  for (let start = 0; start < readingReady.length; start += READING_PASSAGES) readingBlocks.push(readingReady.slice(start, start + READING_PASSAGES));
  const readingBlock = readingBlocks[weekId % readingBlocks.length] ?? readingReady;
  const readingPassages = readingBlock.length === READING_PASSAGES ? readingBlock : readingReady.slice(0, READING_PASSAGES);
  const readingIds = readingPassages.map(passage => passage.id);
  const readingItems = content.pool
    .filter(item => item.section === 'reading' && item.passageId != null && readingIds.includes(item.passageId))
    .sort((a, b) => byString(a.passageId ?? '', b.passageId ?? '') ||
      byString(stableHash(version, a.id), stableHash(version, b.id)));

  const listeningReady = stablePassages(content.pool, content.passages, version, 'listening', LISTENING_PER_PASSAGE)
    .filter(passage => !readingIds.includes(passage.id));
  const listeningBlocks: LevelTestContentPassage[][] = [];
  for (let start = 0; start < listeningReady.length; start += LISTENING_PASSAGES) listeningBlocks.push(listeningReady.slice(start, start + LISTENING_PASSAGES));
  const listeningBlock = listeningBlocks[weekId % listeningBlocks.length] ?? listeningReady;
  const listeningPassages = listeningBlock.length === LISTENING_PASSAGES ? listeningBlock : listeningReady.slice(0, LISTENING_PASSAGES);
  const listeningIds = listeningPassages.map(passage => passage.id);
  const listeningItems = content.pool
    .filter(item => item.section === 'listening' && item.passageId != null && listeningIds.includes(item.passageId))
    .sort((a, b) => byString(a.passageId ?? '', b.passageId ?? '') ||
      byString(stableHash(version, a.id), stableHash(version, b.id)));

  return identifySnapshot({ schemaVersion: 1, languageID, level, version, weekId, expiresAt: expiresAt(now),
    thresholds: content.thresholds, passages: content.passages,
    readingPassageIds: readingIds, listeningPassageIds: listeningIds,
    items: [...recognition, ...readingItems, ...listeningItems, ...writing] as ComposedItem[] }, 'deterministic');
}

