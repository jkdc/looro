import { transaction, type Database } from './db.js';
import { ServiceError } from './errors.js';

export const PROGRESS_PATH = '/v1/progress';
export const PROGRESS_BODY_LIMIT = 65_536;
export const PROGRESS_BATCH = 200;

// Unlike usage_events, this ledger carries content slugs (course/lesson/theme
// ids) because it serves the user's own progress screen. The client uploads
// cumulative counters and the server merges monotonically — last_at/attempts/
// best_score keep the max, first_at the min — so replays and multi-device
// syncs converge instead of double-counting.
export const PROGRESS_KINDS = ['lesson', 'conversation_theme', 'exercise_set', 'listening',
  'recall_round', 'words_practice', 'assessment'] as const;
export type ProgressKind = (typeof PROGRESS_KINDS)[number];

const FUTURE_SKEW_MILLISECONDS = 48 * 3_600_000;
const SLUG = /^[a-z0-9][a-z0-9_-]{0,63}$/;
const invalid = 'invalid_progress_batch';

export interface ProgressEntryInput { kind: ProgressKind; itemID: string; at: Date; attempts: number; bestScore: number }
export interface ProgressBatch { entries: ProgressEntryInput[] }

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
    if (Object.keys(source).some(key => !['kind', 'itemID', 'at', 'attempts', 'bestScore'].includes(key))) throw new ServiceError(invalid);
    const kind = source.kind, itemID = source.itemID, at = source.at, attempts = source.attempts, bestScore = source.bestScore;
    if (typeof kind !== 'string' || !(PROGRESS_KINDS as readonly string[]).includes(kind)) throw new ServiceError(invalid);
    if (typeof itemID !== 'string' || !SLUG.test(itemID)) throw new ServiceError(invalid);
    if (typeof at !== 'number' || !Number.isSafeInteger(at) || at <= 0 || at > now + FUTURE_SKEW_MILLISECONDS) throw new ServiceError(invalid);
    if (typeof attempts !== 'number' || !Number.isSafeInteger(attempts) || attempts < 1 || attempts > 10_000) throw new ServiceError(invalid);
    if (typeof bestScore !== 'number' || !Number.isSafeInteger(bestScore) || bestScore < 0 || bestScore > 100) throw new ServiceError(invalid);
    return { kind: kind as ProgressKind, itemID, at: new Date(at), attempts, bestScore };
  });
  return { entries };
}

export async function mergeProgress(db: Database, account: string, body: unknown): Promise<{ merged: number }> {
  const batch = parseProgressBatch(body);
  // $1 account, then five per entry: kind, item id, attempts, best score, at.
  const values: unknown[] = [account];
  const rows = batch.entries.map((entry, index) => {
    const base = 2 + index * 5;
    values.push(entry.kind, entry.itemID, entry.attempts, entry.bestScore, entry.at);
    return `($1,$${base},$${base + 1},$${base + 4},$${base + 4},$${base + 2},$${base + 3})`;
  });
  await transaction(db, async sql => {
    await sql.query(`INSERT INTO user_progress(account_id,kind,item_id,first_at,last_at,attempts,best_score)
      VALUES ${rows.join(',')}
      ON CONFLICT (account_id, kind, item_id) DO UPDATE SET
        first_at = LEAST(user_progress.first_at, EXCLUDED.first_at),
        last_at = GREATEST(user_progress.last_at, EXCLUDED.last_at),
        attempts = GREATEST(user_progress.attempts, EXCLUDED.attempts),
        best_score = GREATEST(user_progress.best_score, EXCLUDED.best_score)`, values);
  });
  return { merged: batch.entries.length };
}

export interface ProgressRow { kind: string; itemID: string; firstAt: number; lastAt: number; attempts: number; bestScore: number }

export async function listProgress(db: Database, account: string): Promise<{ progress: ProgressRow[] }> {
  const rows = (await db.query(`SELECT kind,item_id,first_at,last_at,attempts,best_score FROM user_progress
    WHERE account_id=$1 ORDER BY last_at DESC LIMIT 1000`, [account])).rows;
  return { progress: rows.map(row => ({ kind: row.kind, itemID: row.item_id, firstAt: Number(row.first_at),
    lastAt: Number(row.last_at), attempts: row.attempts, bestScore: row.best_score })) };
}
