import { createHash } from 'node:crypto';
import type { FastifyInstance, FastifyRequest } from 'fastify';
import { authenticate } from './auth.js';
import { transaction, type Database } from './db.js';
import { ServiceError } from './errors.js';
import { identifySnapshot, type ExamSnapshot, type SnapshotContent } from './level-test-snapshot.js';

const MAX_BATCH_RECORDS = 10;
const MAX_BODY_BYTES = 256_000;
const MAX_SNAPSHOT_BYTES = 96_000;
const MAX_PAGE_SIZE = 100;
const DEFAULT_PAGE_SIZE = 20;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const PUBLIC_EXAM_ID = /^([0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}):v([1-5])$/i;
const SNAPSHOT_KEYS = new Set(['schemaVersion', 'languageID', 'level', 'version', 'weekId', 'expiresAt',
  'thresholds', 'passages', 'readingPassageIds', 'listeningPassageIds', 'items', 'variant', 'examId',
  'contentId', 'contentHash', 'contentRevision', 'gradingVersion', 'source', 'generationId']);
const ATTEMPT_KEYS = new Set(['attemptId', 'examId', 'state', 'position', 'revision', 'startedAt',
  'updatedAt', 'completedAt', 'answers', 'result']);
const EVENT_KEYS = new Set(['eventId', 'attemptId', 'baseRevision', 'revision', 'type', 'payload']);

type Row = Record<string, any>;
type SnapshotRecord = { exam: ExamSnapshot; content: Record<string, unknown>; metadata: Record<string, unknown> };
type AttemptRecord = { attempt: Record<string, unknown>; snapshot: SnapshotRecord };

function isRecord(value: unknown): value is Record<string, any> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}
function badSnapshot(): never { throw new ServiceError('invalid_exam_snapshot', 400); }
function badAttempt(): never { throw new ServiceError('invalid_exam_attempt', 400); }
function exactKeys(value: Record<string, unknown>, keys: Set<string>): boolean {
  return Object.keys(value).every(key => keys.has(key));
}
function canonical(value: any): any {
  if (Array.isArray(value)) return value.map(canonical);
  if (isRecord(value)) return Object.fromEntries(Object.entries(value).filter(([, item]) => item !== undefined)
    .sort(([a], [b]) => a < b ? -1 : a > b ? 1 : 0).map(([key, item]) => [key, canonical(item)]));
  return value;
}
function stableJSON(value: unknown): string { return JSON.stringify(canonical(value)); }
function isoDate(value: unknown): value is string {
  if (typeof value !== 'string') return false;
  const date = new Date(value);
  return Number.isFinite(date.getTime()) && date.toISOString() === value;
}
function positiveInteger(value: unknown): value is number { return Number.isSafeInteger(value) && Number(value) > 0; }
function nonNegativeInteger(value: unknown): value is number { return Number.isSafeInteger(value) && Number(value) >= 0; }

function validateSnapshot(value: unknown): SnapshotRecord {
  if (!isRecord(value) || !exactKeys(value, SNAPSHOT_KEYS) ||
      value.schemaVersion !== 1 || !positiveInteger(value.version) ||
      !positiveInteger(value.contentRevision) || !positiveInteger(value.gradingVersion) ||
      !Number.isSafeInteger(value.weekId) || value.weekId < 200001 || !isoDate(value.expiresAt) ||
      typeof value.languageID !== 'string' || !/^[a-z]{2,3}(?:-[A-Z]{2})?$/.test(value.languageID) ||
      typeof value.level !== 'string' || !/^[A-C][12]$/.test(value.level) ||
      !['bundled', 'deterministic', 'llm'].includes(value.source) ||
      typeof value.examId !== 'string' || value.examId.length < 1 || value.examId.length > 128 ||
      typeof value.contentHash !== 'string' || !/^[a-f0-9]{64}$/.test(value.contentHash) ||
      value.contentId !== `sha256:${value.contentHash}` || !isRecord(value.thresholds) ||
      Object.keys(value.thresholds).length < 1 || Object.values(value.thresholds).some(threshold =>
        typeof threshold !== 'number' || !Number.isFinite(threshold) || threshold < 0 || threshold > 100) ||
      !Array.isArray(value.passages) || value.passages.length > 10 ||
      !Array.isArray(value.readingPassageIds) || !Array.isArray(value.listeningPassageIds) ||
      !Array.isArray(value.items) || value.items.length < 1 || value.items.length > 100) badSnapshot();

  const bytes = Buffer.byteLength(JSON.stringify(value));
  if (bytes > MAX_SNAPSHOT_BYTES) badSnapshot();
  const passageIDs = new Set<string>();
  for (const passage of value.passages) {
    if (!isRecord(passage) || !exactKeys(passage, new Set(['id', 'title', 'text'])) ||
        typeof passage.id !== 'string' || passage.id.length < 1 || passage.id.length > 128 ||
        passageIDs.has(passage.id) || typeof passage.title !== 'string' || passage.title.length > 256 ||
        typeof passage.text !== 'string' || passage.text.length < 1 || passage.text.length > 20_000) badSnapshot();
    passageIDs.add(passage.id);
  }
  for (const passageIDsList of [value.readingPassageIds, value.listeningPassageIds]) {
    if (passageIDsList.length > 10 || new Set(passageIDsList).size !== passageIDsList.length ||
        passageIDsList.some(id => typeof id !== 'string' || !passageIDs.has(id))) badSnapshot();
  }
  const questionIDs = new Set<string>();
  for (const item of value.items) {
    if (!isRecord(item) || !exactKeys(item, new Set(['id', 'section', 'kind', 'prompt', 'options', 'answers', 'explain', 'unitID', 'passageId', 'speech'])) ||
        typeof item.id !== 'string' || item.id.length < 1 || item.id.length > 128 || questionIDs.has(item.id) ||
        !['recognition', 'reading', 'listening', 'writing'].includes(item.section) ||
        !['choice', 'gap', 'written', 'order'].includes(item.kind) || typeof item.prompt !== 'string' ||
        item.prompt.length < 1 || item.prompt.length > 500 || !Array.isArray(item.answers) || item.answers.length < 1 ||
        item.answers.length > 4 || item.answers.some((answer: unknown) => typeof answer !== 'string' || answer.length > 500) ||
        (item.options !== undefined && (!Array.isArray(item.options) || item.options.length > 10 ||
          item.options.some((option: unknown) => typeof option !== 'string' || option.length > 500))) ||
        (item.passageId !== undefined && !passageIDs.has(item.passageId))) badSnapshot();
    if (item.kind === 'choice' && (!Array.isArray(item.options) || item.options.length < 2 ||
        item.answers.some((answer: string) => !item.options.includes(answer)))) badSnapshot();
    questionIDs.add(item.id);
  }
  if (!Array.isArray(value.readingPassageIds) || value.items.some((item: Row) =>
    (item.section === 'reading' || item.section === 'listening') && !item.passageId)) badSnapshot();

  if (value.source === 'llm') {
    if (typeof value.generationId !== 'string' || !UUID.test(value.generationId) ||
        !Number.isInteger(value.variant) || value.variant < 1 || value.variant > 5 ||
        value.examId !== `${value.generationId}:v${value.variant}`) badSnapshot();
  } else if (value.generationId !== undefined || value.variant !== undefined || value.examId !== value.contentId) badSnapshot();

  const identified = identifySnapshot(value as SnapshotContent, value.source, value.generationId);
  if (identified.contentHash !== value.contentHash || identified.contentId !== value.contentId || identified.examId !== value.examId)
    badSnapshot();
  const { examId, contentId, contentHash, contentRevision, gradingVersion, source, generationId, ...content } = value;
  return { exam: value as ExamSnapshot, content, metadata: {
    examId, contentId, contentHash, contentRevision, gradingVersion, source,
    ...(generationId === undefined ? {} : { generationId }),
    ...(value.variant === undefined ? {} : { variant: value.variant }),
  } };
}

function validateAttempt(value: unknown, snapshots: Map<string, SnapshotRecord>): Record<string, any> {
  if (!isRecord(value) || !exactKeys(value, ATTEMPT_KEYS) || !UUID.test(value.attemptId) ||
      typeof value.examId !== 'string' || value.examId.length < 1 || value.examId.length > 128 ||
      !['in_progress', 'completed'].includes(value.state) || !nonNegativeInteger(value.position) || value.position > 1000 ||
      !positiveInteger(value.revision) || !isoDate(value.startedAt) || !isoDate(value.updatedAt) ||
      new Date(value.updatedAt) < new Date(value.startedAt) || !Array.isArray(value.answers) || value.answers.length > 100 ||
      (value.state === 'in_progress' && (value.completedAt !== undefined && value.completedAt !== null || value.result !== undefined && value.result !== null)) ||
      (value.state === 'completed' && (!isoDate(value.completedAt) || new Date(value.completedAt) < new Date(value.startedAt) || !isRecord(value.result)))) badAttempt();
  const snapshot = snapshots.get(value.examId);
  if (!snapshot) badAttempt();
  const questionIDs = new Set(snapshot.exam.items.map(item => item.id));
  const answerIDs = new Set<string>();
  for (const answer of value.answers) {
    if (!isRecord(answer) || !exactKeys(answer, new Set(['questionId', 'revision', 'response'])) ||
        typeof answer.questionId !== 'string' || !questionIDs.has(answer.questionId) ||
        !positiveInteger(answer.revision) || answer.revision > value.revision ||
        !Object.hasOwn(answer, 'response')) badAttempt();
    const key = `${answer.revision}:${answer.questionId}`;
    if (answerIDs.has(key)) badAttempt();
    answerIDs.add(key);
  }
  return value;
}

function validateBatch(value: unknown): { snapshots: SnapshotRecord[]; attempts: Record<string, any>[] } {
  if (!isRecord(value) || !exactKeys(value, new Set(['snapshots', 'attempts'])) ||
      !Array.isArray(value.snapshots) || value.snapshots.length < 1 || value.snapshots.length > MAX_BATCH_RECORDS ||
      !Array.isArray(value.attempts) || value.attempts.length < 1 || value.attempts.length > MAX_BATCH_RECORDS ||
      Buffer.byteLength(JSON.stringify(value)) > MAX_BODY_BYTES) throw new ServiceError('invalid_request', 400);
  const snapshots = value.snapshots.map(validateSnapshot);
  const byExamID = new Map<string, SnapshotRecord>();
  for (const snapshot of snapshots) {
    if (byExamID.has(snapshot.exam.examId)) badSnapshot();
    byExamID.set(snapshot.exam.examId, snapshot);
  }
  const attempts = value.attempts.map((attempt: unknown) => validateAttempt(attempt, byExamID));
  if (new Set(attempts.map(attempt => attempt.attemptId)).size !== attempts.length) badAttempt();
  const referenced = new Set(attempts.map(attempt => attempt.examId));
  if (referenced.size !== byExamID.size || [...byExamID.keys()].some(examID => !referenced.has(examID))) badAttempt();
  return { snapshots, attempts };
}

function parseCursor(cursor: unknown): { updatedAt: string; attemptId: string } | undefined {
  if (cursor === undefined) return undefined;
  if (typeof cursor !== 'string' || cursor.length < 1 || cursor.length > 512 || !/^[A-Za-z0-9_-]+$/.test(cursor))
    throw new ServiceError('invalid_exam_cursor', 400);
  try {
    const decoded = Buffer.from(cursor, 'base64url').toString('utf8');
    if (Buffer.from(decoded).toString('base64url') !== cursor) throw new Error('invalid_cursor_encoding');
    const value = JSON.parse(decoded);
    if (!isRecord(value) || Object.keys(value).length !== 2 || !isoDate(value.updatedAt) || !UUID.test(value.attemptId))
      throw new Error('invalid_cursor_shape');
    return { updatedAt: value.updatedAt, attemptId: value.attemptId };
  } catch { throw new ServiceError('invalid_exam_cursor', 400); }
}

function parseLimit(value: unknown): number {
  if (value === undefined) return DEFAULT_PAGE_SIZE;
  if (typeof value !== 'string' || !/^\d{1,3}$/.test(value)) throw new ServiceError('invalid_exam_limit', 400);
  const limit = Number(value);
  if (!Number.isInteger(limit) || limit < 1 || limit > MAX_PAGE_SIZE) throw new ServiceError('invalid_exam_limit', 400);
  return limit;
}

function asISO(value: string | Date): string { return value instanceof Date ? value.toISOString() : new Date(value).toISOString(); }

type AttemptEvent = { eventId: string; attemptId: string; baseRevision: number; revision: number;
  type: 'progress' | 'completed' | 'takeover'; payload: Record<string, unknown> };

function validateEvent(value: unknown): AttemptEvent {
  if (!isRecord(value) || !exactKeys(value, EVENT_KEYS) || !UUID.test(value.eventId) || !UUID.test(value.attemptId) ||
      !positiveInteger(value.baseRevision) || value.revision !== value.baseRevision + 1 ||
      !['progress', 'completed', 'takeover'].includes(value.type) || !isRecord(value.payload))
    throw new ServiceError('invalid_exam_event', 400);
  const payload = value.payload;
  if (value.type === 'progress') {
    if (!exactKeys(payload, new Set(['questionId', 'response', 'position', 'updatedAt'])) ||
        typeof payload.questionId !== 'string' || payload.questionId.length < 1 || payload.questionId.length > 128 ||
        !isRecord(payload.response) || !nonNegativeInteger(payload.position) || payload.position > 1000 ||
        !isoDate(payload.updatedAt)) throw new ServiceError('invalid_exam_event', 400);
  } else if (value.type === 'completed' && (!exactKeys(payload, new Set(['result', 'completedAt'])) || !isRecord(payload.result) ||
      !isoDate(payload.completedAt))) throw new ServiceError('invalid_exam_event', 400);
  else if (value.type === 'takeover' && (!exactKeys(payload, new Set(['updatedAt'])) || !isoDate(payload.updatedAt)))
    throw new ServiceError('invalid_exam_event', 400);
  return value as AttemptEvent;
}

async function applyAttemptEvent(db: Database, accountID: string, event: AttemptEvent) {
  const eventHash = createHash('sha256').update(stableJSON(event)).digest('hex');
  return transaction(db, async sql => {
    await sql.query('SELECT pg_advisory_xact_lock(hashtextextended($1, 0))', [`${accountID}:${event.eventId}`]);
    const priorEvent = (await sql.query(`SELECT event_hash,ack_json FROM level_test_attempt_events
      WHERE account_id=$1 AND event_id=$2`, [accountID, event.eventId])).rows[0];
    if (priorEvent) {
      if (priorEvent.event_hash !== eventHash) return { status: 'event_conflict' as const };
      return { status: 'accepted' as const, ack: priorEvent.ack_json };
    }
    const attemptRow = (await sql.query(`SELECT attempt_json FROM level_test_attempts
      WHERE account_id=$1 AND attempt_id=$2 FOR UPDATE`, [accountID, event.attemptId])).rows[0];
    if (!attemptRow) return { status: 'not_found' as const };
    const current = attemptRow.attempt_json as Record<string, any>;
    if (current.revision !== event.baseRevision) return { status: 'stale' as const, attempt: current };
    if (current.state !== 'in_progress') return { status: 'stale' as const, attempt: current };

    let updated: Record<string, any>;
    if (event.type === 'progress') {
      const snapshotRow = (await sql.query(`SELECT s.content_json FROM level_test_attempts a
        JOIN level_test_attempt_snapshot_ids i ON i.account_id=a.account_id AND i.exam_id=a.exam_id
        JOIN level_test_attempt_snapshots s ON s.account_id=i.account_id AND s.content_id=i.content_id
        WHERE a.account_id=$1 AND a.attempt_id=$2`, [accountID, event.attemptId])).rows[0];
      const snapshot = snapshotRow?.content_json as Record<string, any> | undefined;
      if (!snapshot?.items?.some((item: Record<string, unknown>) => item.id === event.payload.questionId))
        throw new ServiceError('invalid_exam_event', 400);
      const answers = Array.isArray(current.answers) ? current.answers.filter((answer: Record<string, unknown>) =>
        answer.questionId !== event.payload.questionId) : [];
      answers.push({ questionId: event.payload.questionId, revision: event.revision, response: event.payload.response });
      updated = { ...current, position: event.payload.position, revision: event.revision,
        updatedAt: event.payload.updatedAt, answers };
    } else if (event.type === 'completed') {
      const completedAt = event.payload.completedAt as string;
      if (new Date(completedAt) < new Date(current.startedAt)) throw new ServiceError('invalid_exam_event', 400);
      updated = { ...current, state: 'completed', revision: event.revision, updatedAt: completedAt,
        completedAt, result: event.payload.result };
    } else {
      const updatedAt = event.payload.updatedAt as string;
      if (new Date(updatedAt) < new Date(current.updatedAt)) throw new ServiceError('invalid_exam_event', 400);
      updated = { ...current, revision: event.revision, updatedAt };
    }
    const changed = await sql.query(`UPDATE level_test_attempts SET state=$3,position=$4,revision=$5,updated_at=$6,
        completed_at=$7,answers=$8::jsonb,result=$9::jsonb,attempt_json=$10::jsonb
      WHERE account_id=$1 AND attempt_id=$2 AND revision=$11 RETURNING attempt_id`,
    [accountID, event.attemptId, updated.state, updated.position, updated.revision, updated.updatedAt,
      updated.completedAt ?? null, JSON.stringify(updated.answers), updated.result == null ? null : JSON.stringify(updated.result),
      JSON.stringify(updated), event.baseRevision]);
    if (!changed.rowCount) return { status: 'stale' as const, attempt: current };
    const ack = { eventId: event.eventId, attemptId: event.attemptId, revision: event.revision, status: 'accepted' };
    await sql.query(`INSERT INTO level_test_attempt_events(account_id,event_id,attempt_id,revision,event_hash,ack_json)
      VALUES($1,$2,$3,$4,$5,$6::jsonb)`, [accountID, event.eventId, event.attemptId, event.revision,
      eventHash, JSON.stringify(ack)]);
    return { status: 'accepted' as const, ack };
  });
}

async function persistAttemptBatch(db: Database, accountID: string,
    batch: { snapshots: SnapshotRecord[]; attempts: Record<string, any>[] }): Promise<{ attemptId: string; revision: number }[]> {
  return transaction(db, async sql => {
    for (const { exam, content, metadata } of batch.snapshots) {
      const inserted = await sql.query(`INSERT INTO level_test_attempt_snapshots(account_id,content_id,content_hash,content_json)
        VALUES($1,$2,$3,$4::jsonb) ON CONFLICT(account_id,content_id) DO NOTHING RETURNING content_id`,
      [accountID, exam.contentId, exam.contentHash, JSON.stringify(content)]);
      if (!inserted.rowCount) {
        const prior = (await sql.query(`SELECT content_hash,content_json FROM level_test_attempt_snapshots
          WHERE account_id=$1 AND content_id=$2`, [accountID, exam.contentId])).rows[0];
        if (!prior || prior.content_hash !== exam.contentHash || stableJSON(prior.content_json) !== stableJSON(content))
          throw new ServiceError('exam_snapshot_conflict', 409);
      }
      const identity = await sql.query(`INSERT INTO level_test_attempt_snapshot_ids(account_id,exam_id,content_id,metadata_json)
        VALUES($1,$2,$3,$4::jsonb) ON CONFLICT(account_id,exam_id) DO NOTHING RETURNING exam_id`,
      [accountID, exam.examId, exam.contentId, JSON.stringify(metadata)]);
      if (!identity.rowCount) {
        const prior = (await sql.query(`SELECT content_id,metadata_json FROM level_test_attempt_snapshot_ids
          WHERE account_id=$1 AND exam_id=$2`, [accountID, exam.examId])).rows[0];
        if (!prior || prior.content_id !== exam.contentId || stableJSON(prior.metadata_json) !== stableJSON(metadata))
          throw new ServiceError('exam_snapshot_conflict', 409);
      }
    }
    const accepted: { attemptId: string; revision: number }[] = [];
    const snapshots = new Map(batch.snapshots.map(snapshot => [snapshot.exam.examId, snapshot]));
    for (const attempt of batch.attempts) {
      const snapshot = snapshots.get(attempt.examId)!;
      const requestHash = createHash('sha256').update(stableJSON({
        attempt,
        snapshot: { examId: snapshot.exam.examId, contentId: snapshot.exam.contentId,
          contentHash: snapshot.exam.contentHash, metadata: snapshot.metadata },
      })).digest('hex');
      const inserted = await sql.query(`INSERT INTO level_test_attempts(account_id,attempt_id,exam_id,state,position,revision,
          started_at,updated_at,completed_at,answers,result,attempt_json)
        VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10::jsonb,$11::jsonb,$12::jsonb)
        ON CONFLICT(account_id,attempt_id) DO NOTHING RETURNING attempt_id`,
      [accountID, attempt.attemptId, attempt.examId, attempt.state, attempt.position, attempt.revision,
        attempt.startedAt, attempt.updatedAt, attempt.completedAt ?? null,
        JSON.stringify(attempt.answers), attempt.result == null ? null : JSON.stringify(attempt.result), JSON.stringify(attempt)]);
      if (!inserted.rowCount) {
        const receipt = (await sql.query(`SELECT request_hash,ack_json FROM level_test_attempt_creation_receipts
          WHERE account_id=$1 AND attempt_id=$2`, [accountID, attempt.attemptId])).rows[0];
        if (receipt) {
          if (receipt.request_hash !== requestHash) throw new ServiceError('exam_attempt_conflict', 409);
          accepted.push(receipt.ack_json);
          continue;
        }
        const prior = (await sql.query(`SELECT exam_id,attempt_json FROM level_test_attempts
          WHERE account_id=$1 AND attempt_id=$2`, [accountID, attempt.attemptId])).rows[0];
        if (!prior || prior.exam_id !== attempt.examId || stableJSON(prior.attempt_json) !== stableJSON(attempt))
          throw new ServiceError('exam_attempt_conflict', 409);
      }
      const ack = { attemptId: attempt.attemptId, revision: attempt.revision };
      await sql.query(`INSERT INTO level_test_attempt_creation_receipts(account_id,attempt_id,request_hash,ack_json)
        VALUES($1,$2,$3,$4::jsonb) ON CONFLICT(account_id,attempt_id) DO NOTHING`,
      [accountID, attempt.attemptId, requestHash, JSON.stringify(ack)]);
      const storedReceipt = (await sql.query(`SELECT request_hash,ack_json FROM level_test_attempt_creation_receipts
        WHERE account_id=$1 AND attempt_id=$2`, [accountID, attempt.attemptId])).rows[0];
      if (!storedReceipt || storedReceipt.request_hash !== requestHash)
        throw new ServiceError('exam_attempt_conflict', 409);
      accepted.push(storedReceipt.ack_json);
    }
    return accepted;
  });
}

function requestObject(request: FastifyRequest): Record<string, unknown> {
  const body: unknown = request.body;
  if (!isRecord(body)) throw new ServiceError('invalid_request', 400);
  return body;
}

export function registerLevelTestAttemptRoutes(app: FastifyInstance, db: Database): void {
  app.get('/v1/level-tests/exams/:examId', async request => {
    let examID: string;
    try { examID = decodeURIComponent((request.params as { examId: string }).examId); }
    catch { throw new ServiceError('exam_not_found', 404); }
    const match = PUBLIC_EXAM_ID.exec(examID);
    if (!match) throw new ServiceError('exam_not_found', 404);
    const row = (await db.query(`SELECT variants -> $2::int AS snapshot FROM level_test_weeks
      WHERE id=$1 AND status='approved'`, [match[1], Number(match[2]) - 1])).rows[0];
    if (!isRecord(row?.snapshot) || row.snapshot.examId !== examID) throw new ServiceError('exam_not_found', 404);
    return row.snapshot;
  });

  app.post('/v1/level-test-attempts/sync', { bodyLimit: MAX_BODY_BYTES }, async request => {
    const accountID = await authenticate(db, request.headers.authorization);
    const batch = validateBatch(requestObject(request));
    const accepted = await persistAttemptBatch(db, accountID, batch);
    return { accepted };
  });

  app.post('/v1/level-test-attempts/events', { bodyLimit: MAX_BODY_BYTES }, async (request, reply) => {
    const accountID = await authenticate(db, request.headers.authorization);
    const body = requestObject(request);
    if (!exactKeys(body, new Set(['events'])) || !Array.isArray(body.events) || body.events.length !== 1)
      throw new ServiceError('invalid_request', 400);
    const event = validateEvent(body.events[0]);
    const result = await applyAttemptEvent(db, accountID, event);
    if (result.status === 'not_found') throw new ServiceError('exam_attempt_not_found', 404);
    if (result.status === 'stale') return reply.code(409).send({ error: 'exam_attempt_conflict', conflict: { attempt: result.attempt } });
    if (result.status === 'event_conflict') throw new ServiceError('exam_event_conflict', 409);
    return { acks: [result.ack] };
  });

  app.get('/v1/level-test-attempts', async request => {
    const accountID = await authenticate(db, request.headers.authorization);
    const query = request.query as Record<string, unknown>;
    const limit = parseLimit(query.limit), cursor = parseCursor(query.cursor);
    const result = cursor
      ? await db.query(`SELECT attempt_json,updated_at,attempt_id FROM level_test_attempts
          WHERE account_id=$1 AND (updated_at,attempt_id)<($2::timestamptz,$3::uuid)
          ORDER BY updated_at DESC,attempt_id DESC LIMIT $4`, [accountID, cursor.updatedAt, cursor.attemptId, limit + 1])
      : await db.query(`SELECT attempt_json,updated_at,attempt_id FROM level_test_attempts
          WHERE account_id=$1 ORDER BY updated_at DESC,attempt_id DESC LIMIT $2`, [accountID, limit + 1]);
    const rows = result.rows as Row[];
    const page = rows.slice(0, limit);
    const nextCursor = rows.length > limit ? Buffer.from(JSON.stringify({
      updatedAt: asISO(page[page.length - 1]!.updated_at), attemptId: page[page.length - 1]!.attempt_id,
    })).toString('base64url') : null;
    return { attempts: page.map(row => row.attempt_json), nextCursor };
  });

  app.get('/v1/level-test-attempts/:attemptId', async request => {
    const accountID = await authenticate(db, request.headers.authorization);
    const attemptID = (request.params as { attemptId: string }).attemptId;
    if (!UUID.test(attemptID)) throw new ServiceError('exam_attempt_not_found', 404);
    const row = (await db.query(`SELECT a.attempt_json,s.content_json,i.metadata_json
      FROM level_test_attempts a
      JOIN level_test_attempt_snapshot_ids i ON i.account_id=a.account_id AND i.exam_id=a.exam_id
      JOIN level_test_attempt_snapshots s ON s.account_id=i.account_id AND s.content_id=i.content_id
      WHERE a.account_id=$1 AND a.attempt_id=$2`, [accountID, attemptID])).rows[0];
    if (!row) throw new ServiceError('exam_attempt_not_found', 404);
    return { attempt: row.attempt_json, snapshot: { ...row.content_json, ...row.metadata_json } };
  });
}
