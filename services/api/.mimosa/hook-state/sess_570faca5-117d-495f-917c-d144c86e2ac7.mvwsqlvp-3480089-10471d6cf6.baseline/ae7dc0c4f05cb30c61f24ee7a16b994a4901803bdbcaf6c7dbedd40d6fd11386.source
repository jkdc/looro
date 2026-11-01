import { transaction, type Database } from './db.js';
import { ServiceError } from './errors.js';

export const PROGRESS_PATH = '/v1/progress';
export const PROGRESS_BODY_LIMIT = 65_536;
export const PROGRESS_BATCH = 200;
export const PROGRESS_PAGE_SIZE = 1000;

// Unlike usage_events, this ledger carries content slugs (course/lesson/theme
// ids) because it serves the user's own progress screen. The client uploads
// cumulative counters and the server merges monotonically — last_at/attempts/
// best_score keep the max, first_at the min — so replays and multi-device
// syncs converge instead of double-counting. Every entry belongs to one
// learning language; 'legacy' holds pre-language rows.
export const PROGRESS_KINDS = ['lesson', 'conversation_theme', 'exercise_set', 'listening',
  'reading', 'pronunciation', 'recall_round', 'words_practice', 'assessment', 'scenario_practice'] as const;
export type ProgressKind = (typeof PROGRESS_KINDS)[number];

export const PROGRESS_LANGUAGES = ['nb', 'es', 'en', 'fr', 'de', 'it', 'pt', 'zh', 'legacy'] as const;

const FUTURE_SKEW_MILLISECONDS = 48 * 3_600_000;
const SLUG = /^[a-z0-9][a-z0-9_-]{0,63}$/;
const invalid = 'invalid_progress_batch';

export interface ProgressEntryInput { kind: ProgressKind; itemID: string; at: Date; attempts: number; bestScore: number; language: string }
export interface ProgressBatch { entries: ProgressEntryInput[] }
export interface ProgressV2EntryInput { kind: ProgressKind; itemID: string; language: string; firstAt: Date; lastAt: Date; attempts: number; bestScore: number }
export interface ProgressV2Batch { entries: ProgressV2EntryInput[] }

// Throws are inline (not a never-returning helper): tsgo's control-flow
// analysis keeps type narrowing only when the throw sits in the branch itself.
export function parseProgressBatch(body: unknown, now = Date.now()): ProgressBatch {
  if (!body || typeof body !== 'object' || Array.isArray(body) || Buffer.isBuffer(body)) throw new ServiceError(invalid);
  const input = body as Record<string, unknown>;
  if (Object.keys(input).some(key => key !== 'entries')) throw new ServiceError(invalid);
  const rawEntries = input.entries;
  if (!Array.isArray(rawEntries) || rawEntries.length < 1 || rawEntries.length > PROGRESS_BATCH) throw new ServiceError(invalid);
  const entries = rawEntries.map(entry => {
    if (!entry || typeof entry !== 'object' || Array.isArray(entry)) throw new ServiceError(invalid);
    const source = entry as Record<string, unknown>;
    if (Object.keys(source).some(key => !['kind', 'itemID', 'at', 'attempts', 'bestScore', 'language'].includes(key))) throw new ServiceError(invalid);
    const kind = source.kind, itemID = source.itemID, at = source.at, attempts = source.attempts, bestScore = source.bestScore,
      language = source.language;
    if (typeof kind !== 'string' || !(PROGRESS_KINDS as readonly string[]).includes(kind)) throw new ServiceError(invalid);
    if (typeof itemID !== 'string' || !SLUG.test(itemID)) throw new ServiceError(invalid);
    if (typeof language !== 'string' || !(PROGRESS_LANGUAGES as readonly string[]).includes(language)) throw new ServiceError(invalid);
    if (typeof at !== 'number' || !Number.isSafeInteger(at) || at <= 0 || at > now + FUTURE_SKEW_MILLISECONDS) throw new ServiceError(invalid);
    if (typeof attempts !== 'number' || !Number.isSafeInteger(attempts) || attempts < 1 || attempts > 10_000) throw new ServiceError(invalid);
    if (typeof bestScore !== 'number' || !Number.isSafeInteger(bestScore) || bestScore < 0 || bestScore > 100) throw new ServiceError(invalid);
    return { kind: kind as ProgressKind, itemID, at: new Date(at), attempts, bestScore, language };
  });
  return { entries };
}

export async function mergeProgress(db: Database, account: string, body: unknown): Promise<{ merged: number }> {
  const batch = parseProgressBatch(body);
  // $1 account, then six per entry: kind, item id, language, attempts, best score, at.
  const values: unknown[] = [account];
  const rows = batch.entries.map((entry, index) => {
    const base = 2 + index * 6;
    values.push(entry.kind, entry.itemID, entry.language, entry.attempts, entry.bestScore, entry.at);
    return `($1,$${base},$${base + 1},$${base + 2},$${base + 5},$${base + 5},$${base + 3},$${base + 4})`;
  });
  await transaction(db, async sql => {
    await sql.query(`INSERT INTO user_progress(account_id,kind,item_id,language,first_at,last_at,attempts,best_score)
      VALUES ${rows.join(',')}
      ON CONFLICT (account_id, kind, item_id, language) DO UPDATE SET
        first_at = LEAST(user_progress.first_at, EXCLUDED.first_at),
        last_at = GREATEST(user_progress.last_at, EXCLUDED.last_at),
        attempts = GREATEST(user_progress.attempts, EXCLUDED.attempts),
        best_score = GREATEST(user_progress.best_score, EXCLUDED.best_score)`, values);
  });
  return { merged: batch.entries.length };
}

export interface ProgressRow { kind: string; itemID: string; language: string; firstAt: number; lastAt: number; attempts: number; bestScore: number }

export function parseProgressV2Batch(body: unknown, now = Date.now()): ProgressV2Batch {
  if (!body || typeof body !== 'object' || Array.isArray(body) || Buffer.isBuffer(body)) throw new ServiceError(invalid);
  const input = body as Record<string, unknown>;
  if (Object.keys(input).some(key => key !== 'version' && key !== 'entries') || input.version !== 2) throw new ServiceError(invalid);
  const rawEntries = input.entries;
  if (!Array.isArray(rawEntries) || rawEntries.length < 1 || rawEntries.length > PROGRESS_BATCH) throw new ServiceError(invalid);
  const entries = rawEntries.map(entry => {
    if (!entry || typeof entry !== 'object' || Array.isArray(entry) || Buffer.isBuffer(entry)) throw new ServiceError(invalid);
    const source = entry as Record<string, unknown>;
    if (Object.keys(source).some(key => !['kind', 'itemID', 'language', 'firstAt', 'lastAt', 'attempts', 'bestScore'].includes(key)))
      throw new ServiceError(invalid);
    const { kind, itemID, language, firstAt, lastAt, attempts, bestScore } = source;
    if (typeof kind !== 'string' || !(PROGRESS_KINDS as readonly string[]).includes(kind)) throw new ServiceError(invalid);
    if (typeof itemID !== 'string' || !SLUG.test(itemID)) throw new ServiceError(invalid);
    if (typeof language !== 'string' || !(PROGRESS_LANGUAGES as readonly string[]).includes(language)) throw new ServiceError(invalid);
    const validTimestamp = (timestamp: unknown): timestamp is number => typeof timestamp === 'number' && Number.isSafeInteger(timestamp) &&
      timestamp > 0 && timestamp <= now + FUTURE_SKEW_MILLISECONDS;
    if (!validTimestamp(firstAt) || !validTimestamp(lastAt)) throw new ServiceError(invalid);
    if (firstAt > lastAt) throw new ServiceError(invalid);
    if (typeof attempts !== 'number' || !Number.isSafeInteger(attempts) || attempts < 1 || attempts > 10_000) throw new ServiceError(invalid);
    if (typeof bestScore !== 'number' || !Number.isSafeInteger(bestScore) || bestScore < 0 || bestScore > 100) throw new ServiceError(invalid);
    return { kind: kind as ProgressKind, itemID, language, firstAt: new Date(firstAt), lastAt: new Date(lastAt), attempts, bestScore };
  });
  return { entries };
}

export async function mergeProgressV2(db: Database, account: string, body: unknown): Promise<{ merged: number }> {
  const batch = parseProgressV2Batch(body);
  const values: unknown[] = [account];
  const rows = batch.entries.map((entry, index) => {
    const base = 2 + index * 7;
    values.push(entry.kind, entry.itemID, entry.language, entry.firstAt, entry.lastAt, entry.attempts, entry.bestScore);
    return `($1,$${base},$${base + 1},$${base + 2},$${base + 3},$${base + 4},$${base + 5},$${base + 6})`;
  });
  await transaction(db, async sql => {
    await sql.query(`INSERT INTO user_progress(account_id,kind,item_id,language,first_at,last_at,attempts,best_score)
      VALUES ${rows.join(',')}
      ON CONFLICT (account_id, kind, item_id, language) DO UPDATE SET
        first_at = LEAST(user_progress.first_at, EXCLUDED.first_at),
        last_at = GREATEST(user_progress.last_at, EXCLUDED.last_at),
        attempts = GREATEST(user_progress.attempts, EXCLUDED.attempts),
        best_score = GREATEST(user_progress.best_score, EXCLUDED.best_score)`, values);
  });
  return { merged: batch.entries.length };
}

interface ProgressIdentity { kind: string; itemID: string; language: string }

function encodeProgressCursor(identity: ProgressIdentity): string {
  return Buffer.from(JSON.stringify([2, identity.kind, identity.itemID, identity.language]), 'utf8').toString('base64url');
}

function decodeProgressCursor(cursor: unknown): ProgressIdentity {
  if (typeof cursor !== 'string' || cursor.length < 1 || cursor.length > 512 || !/^[A-Za-z0-9_-]+$/.test(cursor))
    throw new ServiceError('invalid_progress_cursor');
  try {
    const decoded = Buffer.from(cursor, 'base64url').toString('utf8');
    const identity = JSON.parse(decoded);
    if (!Array.isArray(identity) || identity.length !== 4 || identity[0] !== 2 || identity.slice(1).some(value => typeof value !== 'string') ||
        Buffer.from(JSON.stringify(identity), 'utf8').toString('base64url') !== cursor ||
        !(PROGRESS_KINDS as readonly string[]).includes(identity[1]) || !SLUG.test(identity[2]) ||
        !(PROGRESS_LANGUAGES as readonly string[]).includes(identity[3])) throw new Error('invalid cursor');
    return { kind: identity[1], itemID: identity[2], language: identity[3] };
  } catch {
    throw new ServiceError('invalid_progress_cursor');
  }
}

export async function listProgressV2(db: Database, account: string, cursor?: unknown) {
  const after = cursor === undefined ? null : decodeProgressCursor(cursor);
  const rows = (await db.query(`SELECT kind,item_id,language,first_at,last_at,attempts,best_score FROM user_progress
    WHERE account_id=$1 AND ($2::text IS NULL OR
      ROW(kind COLLATE "C", item_id COLLATE "C", language COLLATE "C") >
      ROW($2::text COLLATE "C", $3::text COLLATE "C", $4::text COLLATE "C"))
    ORDER BY kind COLLATE "C", item_id COLLATE "C", language COLLATE "C" LIMIT $5`,
  [account, after?.kind ?? null, after?.itemID ?? null, after?.language ?? null, PROGRESS_PAGE_SIZE + 1])).rows;
  const hasMore = rows.length > PROGRESS_PAGE_SIZE;
  const page = hasMore ? rows.slice(0, PROGRESS_PAGE_SIZE) : rows;
  const progress = page.map(row => ({ kind: row.kind, itemID: row.item_id, language: row.language,
    firstAt: Number(row.first_at), lastAt: Number(row.last_at), attempts: row.attempts, bestScore: row.best_score }));
  const last = page.at(-1);
  const nextCursor = hasMore && last ? encodeProgressCursor({ kind: last.kind, itemID: last.item_id, language: last.language }) : null;
  return { version: 2 as const, progress, nextCursor, complete: nextCursor === null };
}

export async function listProgress(db: Database, account: string): Promise<{ progress: ProgressRow[] }> {
  const rows = (await db.query(`SELECT kind,item_id,language,first_at,last_at,attempts,best_score FROM user_progress
    WHERE account_id=$1 ORDER BY last_at DESC LIMIT 1000`, [account])).rows;
  return { progress: rows.map(row => ({ kind: row.kind, itemID: row.item_id, language: row.language,
    firstAt: Number(row.first_at), lastAt: Number(row.last_at), attempts: row.attempts, bestScore: row.best_score })) };
}
