import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes, randomUUID } from 'node:crypto';
import { connectDatabase } from '../src/db.js';
import { migrate } from '../src/migrate.js';
import { createApp } from '../src/app.js';
import { deleteAccount, digest } from '../src/auth.js';
import { composeWeekly } from '../src/level-tests.js';
import { identifySnapshot } from '../src/level-test-snapshot.js';

const url = process.env.TEST_DATABASE_URL;
if (url && !new URL(url).pathname.endsWith('_test')) throw new Error('Integration tests require a dedicated database ending in _test.');
const db = url ? connectDatabase(url) : null;
before(async () => { if (db) await migrate(db); });
after(async () => { if (db) await db.end(); });
const integration = (name: string, fn: () => Promise<void>) => test(name,
  { skip: !db && 'Set TEST_DATABASE_URL to run PostgreSQL integration tests.' }, fn);

async function account() {
  const accountID = randomUUID();
  await db!.query('INSERT INTO accounts(id,email) VALUES($1,$2)', [accountID, `${accountID}@exam.test`]);
  await db!.query('INSERT INTO wallets(account_id) VALUES($1)', [accountID]);
  await db!.query('INSERT INTO minute_wallets(account_id) VALUES($1)', [accountID]);
  const accessToken = randomBytes(32).toString('base64url');
  await db!.query('INSERT INTO auth_sessions(id,account_id,token_hash,expires_at) VALUES($1,$2,$3,now()+interval \'1 hour\')',
    [randomUUID(), accountID, digest(accessToken)]);
  return { accountID, authorization: `Bearer ${accessToken}` };
}

function bundledSnapshot() {
  const exam = composeWeekly('en', 'B1', new Date('2026-10-07T12:00:00Z'))!;
  return identifySnapshot(exam, 'bundled');
}

function attempt(snapshot: ReturnType<typeof bundledSnapshot>) {
  const now = new Date('2026-10-07T12:00:00Z').toISOString();
  return { attemptId: randomUUID(), examId: snapshot.examId, state: 'in_progress',
    position: 1, revision: 1, startedAt: now, updatedAt: now, answers: [], result: null };
}

integration('private attempt upload is authenticated, owner-scoped, and restores its frozen snapshot', async () => {
  const owner = await account(), other = await account(), snapshot = bundledSnapshot(), uploaded = attempt(snapshot);
  const app = createApp({ db: db!, auth: {} });
  try {
    const unauthenticated = await app.inject({ method: 'POST', url: '/v1/level-test-attempts/sync', payload: {
      snapshots: [snapshot], attempts: [uploaded],
    } });
    assert.equal(unauthenticated.statusCode, 401);

    const upload = await app.inject({ method: 'POST', url: '/v1/level-test-attempts/sync',
      headers: { authorization: owner.authorization }, payload: { snapshots: [snapshot], attempts: [uploaded] } });
    assert.equal(upload.statusCode, 200, upload.body);
    assert.deepEqual(upload.json().accepted, [{ attemptId: uploaded.attemptId, revision: 1 }]);
    const identicalRetry = await app.inject({ method: 'POST', url: '/v1/level-test-attempts/sync',
      headers: { authorization: owner.authorization }, payload: { snapshots: [snapshot], attempts: [uploaded] } });
    assert.equal(identicalRetry.statusCode, 200, identicalRetry.body);
    const conflictingRetry = await app.inject({ method: 'POST', url: '/v1/level-test-attempts/sync',
      headers: { authorization: owner.authorization }, payload: { snapshots: [snapshot],
        attempts: [{ ...uploaded, position: 2 }] } });
    assert.equal(conflictingRetry.statusCode, 409);

    const foreignList = await app.inject({ method: 'GET', url: '/v1/level-test-attempts',
      headers: { authorization: other.authorization } });
    assert.equal(foreignList.statusCode, 200);
    assert.deepEqual(foreignList.json().attempts, []);
    const foreignDetail = await app.inject({ method: 'GET', url: `/v1/level-test-attempts/${uploaded.attemptId}`,
      headers: { authorization: other.authorization } });
    assert.equal(foreignDetail.statusCode, 404);

    const detail = await app.inject({ method: 'GET', url: `/v1/level-test-attempts/${uploaded.attemptId}`,
      headers: { authorization: owner.authorization } });
    assert.equal(detail.statusCode, 200, detail.body);
    assert.deepEqual(detail.json(), { attempt: uploaded, snapshot });
    const privatePublicLookup = await app.inject({ method: 'GET',
      url: `/v1/level-tests/exams/${encodeURIComponent(snapshot.examId)}` });
    assert.equal(privatePublicLookup.statusCode, 404);

    await assert.rejects(db!.query(`DELETE FROM level_test_attempt_snapshots WHERE account_id=$1 AND content_id=$2`,
      [owner.accountID, snapshot.contentId]));
    await assert.rejects(db!.query(`UPDATE level_test_attempt_creation_receipts SET request_hash=$3
      WHERE account_id=$1 AND attempt_id=$2`, [owner.accountID, uploaded.attemptId, '0'.repeat(64)]));
    await assert.rejects(db!.query(`UPDATE level_test_attempt_creation_receipts SET ack_json='{}'::jsonb
      WHERE account_id=$1 AND attempt_id=$2`, [owner.accountID, uploaded.attemptId]));
    await deleteAccount(db!, owner.accountID, undefined, undefined, owner.authorization);
    const deletedRows = await db!.query(`SELECT
      (SELECT count(*)::int FROM level_test_attempts WHERE account_id=$1) AS attempts,
      (SELECT count(*)::int FROM level_test_attempt_snapshot_ids WHERE account_id=$1) AS identities,
      (SELECT count(*)::int FROM level_test_attempt_snapshots WHERE account_id=$1) AS snapshots,
      (SELECT count(*)::int FROM level_test_attempt_creation_receipts WHERE account_id=$1) AS receipts`, [owner.accountID]);
    assert.deepEqual(deletedRows.rows[0], { attempts: 0, identities: 0, snapshots: 0, receipts: 0 });
  } finally { await app.close(); }
});

integration('uploads accept the deterministic wire shape (optional keys absent)', async () => {
  const owner = await account(), snapshot = bundledSnapshot(), uploaded = attempt(snapshot);
  // Emulate the wire format: JSON drops keys whose value is undefined (a
  // deterministic exam carries no variant/generationId and its items omit
  // unitID/passageId/speech), so the parsed payload below is exactly what a
  // client uploads for a bundled/deterministic exam.
  const wire = JSON.parse(JSON.stringify({ snapshots: [snapshot], attempts: [uploaded] }));
  const snapshotKeys = Object.keys(wire.snapshots[0]);
  assert.ok(!snapshotKeys.includes('variant'), 'deterministic wire must not carry variant');
  const app = createApp({ db: db!, auth: {} });
  try {
    const upload = await app.inject({ method: 'POST', url: '/v1/level-test-attempts/sync',
      headers: { authorization: owner.authorization }, payload: wire });
    assert.equal(upload.statusCode, 200, upload.body);
    assert.deepEqual(upload.json().accepted, [{ attemptId: uploaded.attemptId, revision: 1 }]);
  } finally { await app.close(); }
});

integration('attempt and event timestamps reject far-future clocks (R3-001)', async () => {
  const owner = await account(), snapshot = bundledSnapshot();
  const app = createApp({ db: db!, auth: {} });
  try {
    const farFuture = new Date(Date.now() + 90 * 24 * 3_600_000).toISOString();
    const pinned = await app.inject({ method: 'POST', url: '/v1/level-test-attempts/sync',
      headers: { authorization: owner.authorization },
      payload: { snapshots: [snapshot], attempts: [{ ...attempt(snapshot), updatedAt: farFuture }] } });
    assert.equal(pinned.statusCode, 400, pinned.body);
    const honest = await app.inject({ method: 'POST', url: '/v1/level-test-attempts/sync',
      headers: { authorization: owner.authorization },
      payload: { snapshots: [snapshot], attempts: [attempt(snapshot)] } });
    assert.equal(honest.statusCode, 200, honest.body);
    const localAttemptID = (honest.json().accepted as { attemptId: string }[])[0]!.attemptId;
    const questionID = (snapshot.items as { id: string }[])[0]!.id;
    const futureEvent = await app.inject({ method: 'POST', url: '/v1/level-test-attempts/events',
      headers: { authorization: owner.authorization },
      payload: { events: [{ eventId: randomUUID(), attemptId: localAttemptID, baseRevision: 1, revision: 2,
        type: 'progress', payload: { questionId: questionID, response: { answer: 'x' }, position: 1, updatedAt: farFuture } }] } });
    assert.equal(futureEvent.statusCode, 400, futureEvent.body);
    // The bounded attempt still progresses with an honest timestamp.
    const okEvent = await app.inject({ method: 'POST', url: '/v1/level-test-attempts/events',
      headers: { authorization: owner.authorization },
      payload: { events: [{ eventId: randomUUID(), attemptId: localAttemptID, baseRevision: 1, revision: 2,
        type: 'progress', payload: { questionId: questionID, response: { answer: 'x' }, position: 1,
          updatedAt: new Date().toISOString() } }] } });
    assert.equal(okEvent.statusCode, 200, okEvent.body);
  } finally { await app.close(); }
});

integration('snapshot uploads reject invalid hashes and dangling attempt references atomically', async () => {
  const owner = await account(), snapshot = bundledSnapshot(), uploaded = attempt(snapshot), app = createApp({ db: db!, auth: {} });
  try {
    const invalidSnapshot = { ...snapshot, contentHash: '0'.repeat(64) };
    const invalidHash = await app.inject({ method: 'POST', url: '/v1/level-test-attempts/sync',
      headers: { authorization: owner.authorization }, payload: { snapshots: [invalidSnapshot], attempts: [uploaded] } });
    assert.equal(invalidHash.statusCode, 400);

    const dangling = await app.inject({ method: 'POST', url: '/v1/level-test-attempts/sync',
      headers: { authorization: owner.authorization }, payload: { snapshots: [], attempts: [uploaded] } });
    assert.equal(dangling.statusCode, 400);
    const malformed = structuredClone(snapshot);
    const choice = malformed.items.find((item) => item.kind === 'choice');
    assert.ok(choice && choice.kind === 'choice');
    delete (choice as { options?: unknown }).options;
    const malformedSnapshot = identifySnapshot(malformed, 'bundled');
    const malformedUpload = await app.inject({ method: 'POST', url: '/v1/level-test-attempts/sync',
      headers: { authorization: owner.authorization }, payload: { snapshots: [malformedSnapshot], attempts: [attempt(malformedSnapshot)] } });
    assert.equal(malformedUpload.statusCode, 400);
    const rows = await db!.query('SELECT count(*)::int AS count FROM level_test_attempts WHERE account_id=$1', [owner.accountID]);
    assert.equal(rows.rows[0]?.count, 0);
  } finally { await app.close(); }
});

integration('published exam IDs resolve immutable approved variants but not retired generations', async () => {
  const generationID = randomUUID(), suffix = generationID.replaceAll('-', '').slice(0, 6);
  const weekIndex = (2100 + Number.parseInt(suffix.slice(0, 4), 16) % 7900) * 100 +
    (1 + Number.parseInt(suffix.slice(4), 16) % 52);
  const exam = composeWeekly('en', 'B1', new Date('2026-10-07T12:00:00Z'))!;
  const variant = identifySnapshot({ ...exam, weekId: weekIndex, variant: 1 }, 'llm', generationID);
  await db!.query(`INSERT INTO level_test_weeks(id,language_id,level,week_index,status,pool,variants,generator,model,prompt_version)
    VALUES($1,'en','B1',$2,'approved','{}'::jsonb,$3::jsonb,'llm:test','test-model',1)`,
    [generationID, weekIndex, JSON.stringify([variant])]);
  const app = createApp({ db: db!, auth: {} });
  try {
    const examId = `${generationID}:v1`;
    const approved = await app.inject({ method: 'GET', url: `/v1/level-tests/exams/${encodeURIComponent(examId)}` });
    assert.equal(approved.statusCode, 200, approved.body);
    assert.deepEqual(approved.json(), variant);

    await db!.query("UPDATE level_test_weeks SET status='retired' WHERE id=$1", [generationID]);
    const retired = await app.inject({ method: 'GET', url: `/v1/level-tests/exams/${encodeURIComponent(examId)}` });
    assert.equal(retired.statusCode, 404);
    await assert.rejects(db!.query("UPDATE level_test_weeks SET variants='[]'::jsonb WHERE id=$1", [generationID]));
  } finally { await app.close(); }
});

integration('attempt history pages are bounded and reject malformed cursors', async () => {
  const owner = await account(), snapshot = bundledSnapshot(), first = attempt(snapshot), second = attempt(snapshot);
  const app = createApp({ db: db!, auth: {} });
  try {
    const uploaded = await app.inject({ method: 'POST', url: '/v1/level-test-attempts/sync',
      headers: { authorization: owner.authorization }, payload: { snapshots: [snapshot], attempts: [first, second] } });
    assert.equal(uploaded.statusCode, 200, uploaded.body);
    const pageOne = await app.inject({ method: 'GET', url: '/v1/level-test-attempts?limit=1',
      headers: { authorization: owner.authorization } });
    assert.equal(pageOne.statusCode, 200, pageOne.body);
    assert.equal(pageOne.json().attempts.length, 1);
    assert.equal(typeof pageOne.json().nextCursor, 'string');
    const pageTwo = await app.inject({ method: 'GET',
      url: `/v1/level-test-attempts?limit=1&cursor=${pageOne.json().nextCursor}`,
      headers: { authorization: owner.authorization } });
    assert.equal(pageTwo.statusCode, 200, pageTwo.body);
    assert.equal(pageTwo.json().attempts.length, 1);
    assert.equal(pageTwo.json().nextCursor, null);
    const tooLarge = await app.inject({ method: 'GET', url: '/v1/level-test-attempts?limit=101',
      headers: { authorization: owner.authorization } });
    assert.equal(tooLarge.statusCode, 400);
    const invalidCursor = await app.inject({ method: 'GET', url: '/v1/level-test-attempts?cursor=not-a-cursor',
      headers: { authorization: owner.authorization } });
    assert.equal(invalidCursor.statusCode, 400);
  } finally { await app.close(); }
});

integration('attempt events replay their original ACK and reject stale or altered retries', async () => {
  const owner = await account(), other = await account(), snapshot = bundledSnapshot(), uploaded = attempt(snapshot);
  const app = createApp({ db: db!, auth: {} });
  try {
    const headers = { authorization: owner.authorization };
    const initial = await app.inject({ method: 'POST', url: '/v1/level-test-attempts/sync', headers,
      payload: { snapshots: [snapshot], attempts: [uploaded] } });
    assert.equal(initial.statusCode, 200, initial.body);
    const eventId = randomUUID();
    const event = { eventId, attemptId: uploaded.attemptId, baseRevision: 1, revision: 2,
      type: 'progress', payload: { questionId: snapshot.items[0]!.id, response: { value: 'answer' },
        position: 2, updatedAt: '2026-10-07T12:01:00.000Z' } };
    const send = (authorization: string, body: unknown) => app.inject({ method: 'POST',
      url: '/v1/level-test-attempts/events', headers: { authorization }, payload: { events: [body] } });
    const [accepted, replayed] = await Promise.all([
      send(owner.authorization, event), send(owner.authorization, event),
    ]);
    assert.equal(accepted.statusCode, 200, accepted.body);
    assert.equal(replayed.statusCode, 200, replayed.body);
    assert.deepEqual(accepted.json().acks, [{ eventId, attemptId: uploaded.attemptId, revision: 2, status: 'accepted' }]);
    assert.deepEqual(replayed.json(), accepted.json());

    const changedReplay = await send(owner.authorization, { ...event, payload: { ...event.payload, position: 3 } });
    assert.equal(changedReplay.statusCode, 409);
    const staleCompetingEvent = await send(owner.authorization, { ...event, eventId: randomUUID(),
      payload: { ...event.payload, position: 4 } });
    assert.equal(staleCompetingEvent.statusCode, 409);
    assert.equal(staleCompetingEvent.json().conflict.attempt.revision, 2);
    const takeover = await send(owner.authorization, { eventId: randomUUID(), attemptId: uploaded.attemptId,
      baseRevision: 2, revision: 3, type: 'takeover', payload: { updatedAt: '2026-10-07T12:02:00.000Z' } });
    assert.equal(takeover.statusCode, 200, takeover.body);
    const fork = { ...uploaded, attemptId: randomUUID(), revision: 2, position: 4,
      answers: [{ questionId: snapshot.items[0]!.id, revision: 2, response: { value: 'local divergent edit' } }] };
    const forked = await app.inject({ method: 'POST', url: '/v1/level-test-attempts/sync', headers,
      payload: { snapshots: [snapshot], attempts: [fork] } });
    assert.equal(forked.statusCode, 200, forked.body);
    const forkDetail = await app.inject({ method: 'GET', url: `/v1/level-test-attempts/${fork.attemptId}`, headers });
    assert.deepEqual(forkDetail.json().attempt, fork);
    const foreignReplay = await send(other.authorization, event);
    assert.equal(foreignReplay.statusCode, 404);
    const detail = await app.inject({ method: 'GET', url: `/v1/level-test-attempts/${uploaded.attemptId}`, headers });
    assert.equal(detail.statusCode, 200);
    assert.equal(detail.json().attempt.position, 2);
    assert.equal(detail.json().attempt.revision, 3);
    assert.equal(detail.json().attempt.answers.length, 1);
  } finally { await app.close(); }
});

integration('completed attempts reject later answer events and completion is replay-safe', async () => {
  const owner = await account(), snapshot = bundledSnapshot(), uploaded = attempt(snapshot);
  const app = createApp({ db: db!, auth: {} });
  try {
    const headers = { authorization: owner.authorization };
    const initial = await app.inject({ method: 'POST', url: '/v1/level-test-attempts/sync', headers,
      payload: { snapshots: [snapshot], attempts: [uploaded] } });
    assert.equal(initial.statusCode, 200, initial.body);
    const completion = { eventId: randomUUID(), attemptId: uploaded.attemptId, baseRevision: 1, revision: 2,
      type: 'completed', payload: { result: { score: 80 }, completedAt: '2026-10-07T12:02:00.000Z' } };
    const send = (event: unknown) => app.inject({ method: 'POST', url: '/v1/level-test-attempts/events', headers,
      payload: { events: [event] } });
    const accepted = await send(completion);
    assert.equal(accepted.statusCode, 200, accepted.body);
    assert.deepEqual((await send(completion)).json(), accepted.json());
    const progress = { eventId: randomUUID(), attemptId: uploaded.attemptId, baseRevision: 2, revision: 3,
      type: 'progress', payload: { questionId: snapshot.items[0]!.id, response: { value: 'later' },
        position: 3, updatedAt: '2026-10-07T12:03:00.000Z' } };
    const rejected = await send(progress);
    assert.equal(rejected.statusCode, 409);
    const detail = await app.inject({ method: 'GET', url: `/v1/level-test-attempts/${uploaded.attemptId}`, headers });
    assert.equal(detail.json().attempt.state, 'completed');
    assert.deepEqual(detail.json().attempt.result, { score: 80 });
  } finally { await app.close(); }
});


integration('initial creation retry replays its immutable ACK after another device advances the attempt', async () => {
  const owner = await account(), snapshot = bundledSnapshot(), created = attempt(snapshot), app = createApp({ db: db!, auth: {} });
  try {
    const headers = { authorization: owner.authorization };
    const create = (value: typeof created) => app.inject({ method: 'POST', url: '/v1/level-test-attempts/sync', headers,
      payload: { snapshots: [snapshot], attempts: [value] } });
    const first = await create(created);
    assert.equal(first.statusCode, 200, first.body);
    assert.deepEqual(first.json().accepted, [{ attemptId: created.attemptId, revision: 1 }]);

    const advance = await app.inject({ method: 'POST', url: '/v1/level-test-attempts/events', headers,
      payload: { events: [{ eventId: randomUUID(), attemptId: created.attemptId, baseRevision: 1, revision: 2,
        type: 'progress', payload: { questionId: snapshot.items[0]!.id, response: { value: 'device B' },
          position: 2, updatedAt: '2026-10-07T12:01:00.000Z' } }] } });
    assert.equal(advance.statusCode, 200, advance.body);

    const retry = await create(created);
    assert.equal(retry.statusCode, 200, retry.body);
    assert.deepEqual(retry.json().accepted, [{ attemptId: created.attemptId, revision: 1 }]);

    const stale = await app.inject({ method: 'POST', url: '/v1/level-test-attempts/events', headers,
      payload: { events: [{ eventId: randomUUID(), attemptId: created.attemptId, baseRevision: 1, revision: 2,
        type: 'progress', payload: { questionId: snapshot.items[0]!.id, response: { value: 'device A' },
          position: 3, updatedAt: '2026-10-07T12:02:00.000Z' } }] } });
    assert.equal(stale.statusCode, 409);
    assert.equal(stale.json().conflict.attempt.revision, 2);

    const changed = await create({ ...created, position: 7 });
    assert.equal(changed.statusCode, 409);
    const detail = await app.inject({ method: 'GET', url: `/v1/level-test-attempts/${created.attemptId}`, headers });
    assert.equal(detail.statusCode, 200, detail.body);
    assert.equal(detail.json().attempt.revision, 2);
    assert.equal(detail.json().attempt.position, 2);
  } finally { await app.close(); }
});

integration('attempt event timestamps never regress and exact retries preserve the original ACK', async () => {
  const owner = await account(), snapshot = bundledSnapshot(), created = attempt(snapshot), app = createApp({ db: db!, auth: {} });
  try {
    const headers = { authorization: owner.authorization };
    const create = await app.inject({ method: 'POST', url: '/v1/level-test-attempts/sync', headers,
      payload: { snapshots: [snapshot], attempts: [created] } });
    assert.equal(create.statusCode, 200, create.body);
    const send = (event: unknown) => app.inject({ method: 'POST', url: '/v1/level-test-attempts/events', headers,
      payload: { events: [event] } });
    const beforeStart = { eventId: randomUUID(), attemptId: created.attemptId, baseRevision: 1, revision: 2,
      type: 'progress', payload: { questionId: snapshot.items[0]!.id, response: { value: 'before start' },
        position: 1, updatedAt: '2026-10-07T11:59:00.000Z' } };
    const first = await send(beforeStart);
    assert.equal(first.statusCode, 200, first.body);
    assert.deepEqual(await send(beforeStart).then(response => response.json()), first.json());

    const regressed = { eventId: randomUUID(), attemptId: created.attemptId, baseRevision: 2, revision: 3,
      type: 'progress', payload: { questionId: snapshot.items[0]!.id, response: { value: 'before last update' },
        position: 2, updatedAt: '2026-10-07T12:00:30.000Z' } };
    const second = await send(regressed);
    assert.equal(second.statusCode, 200, second.body);

    const completion = { eventId: randomUUID(), attemptId: created.attemptId, baseRevision: 3, revision: 4,
      type: 'completed', payload: { result: { score: 90 }, completedAt: '2026-10-07T12:00:20.000Z' } };
    const completed = await send(completion);
    assert.equal(completed.statusCode, 200, completed.body);
    assert.deepEqual(await send(completion).then(response => response.json()), completed.json());
    const detail = await app.inject({ method: 'GET',
      url: '/v1/level-test-attempts/' + created.attemptId, headers });
    assert.equal(detail.statusCode, 200, detail.body);
    assert.equal(detail.json().attempt.updatedAt, '2026-10-07T12:00:30.000Z');
    assert.equal(detail.json().attempt.completedAt, '2026-10-07T12:00:30.000Z');
    assert.equal(detail.json().attempt.revision, 4);
  } finally { await app.close(); }
});

integration('LLM snapshots must carry a valid generation lineage and variant identity', async () => {
  const owner = await account();
  const exam = composeWeekly('en', 'B1', new Date('2026-10-07T12:00:00Z'))!;
  const generationID = randomUUID();
  const llm = identifySnapshot({ ...exam, variant: 1 }, 'llm', generationID);
  assert.equal(llm.examId, `${generationID}:v1`);
  const app = createApp({ db: db!, auth: {} });
  const upload = (snapshot: unknown) => app.inject({ method: 'POST', url: '/v1/level-test-attempts/sync',
    headers: { authorization: owner.authorization },
    payload: { snapshots: [snapshot], attempts: [{ ...attempt(llm), examId: (snapshot as { examId: string }).examId }] } });
  try {
    const valid = await upload(llm);
    assert.equal(valid.statusCode, 200, valid.body);

    for (const broken of [
      { ...llm, generationId: 'not-a-uuid' },
      { ...llm, variant: 0, examId: `${generationID}:v0` },
      { ...llm, variant: 6, examId: `${generationID}:v6` },
      { ...llm, examId: `${generationID}:v2` },
      { ...llm, source: 'bundled' }
    ]) {
      const response = await upload(broken);
      assert.equal(response.statusCode, 400, JSON.stringify(broken).slice(0, 80));
      assert.equal(response.json().error?.code, 'invalid_exam_snapshot');
    }
  } finally { await app.close(); }
});

integration('a pre-existing attempt without a receipt conflicts or replays based on its stored bytes', async () => {
  const owner = await account(), snapshot = bundledSnapshot(), created = attempt(snapshot);
  const app = createApp({ db: db!, auth: {} });
  const headers = { authorization: owner.authorization };
  const create = (value: typeof created) => app.inject({ method: 'POST', url: '/v1/level-test-attempts/sync', headers,
    payload: { snapshots: [snapshot], attempts: [value] } });
  try {
    assert.equal((await create(created)).statusCode, 200);
    await db!.query('DELETE FROM level_test_attempt_creation_receipts WHERE account_id=$1 AND attempt_id=$2',
      [owner.accountID, created.attemptId]);

    // Identical bytes with no receipt: the durable attempt itself is the proof.
    const replay = await create(created);
    assert.equal(replay.statusCode, 200, replay.body);
    assert.deepEqual(replay.json().accepted, [{ attemptId: created.attemptId, revision: 1 }]);
    assert.equal((await db!.query(`SELECT count(*)::int AS n FROM level_test_attempt_creation_receipts
      WHERE account_id=$1 AND attempt_id=$2`, [owner.accountID, created.attemptId])).rows[0]!.n, 1);

    await db!.query('DELETE FROM level_test_attempt_creation_receipts WHERE account_id=$1 AND attempt_id=$2',
      [owner.accountID, created.attemptId]);
    const altered = await create({ ...created, position: 9 });
    assert.equal(altered.statusCode, 409);
    assert.equal(altered.json().error?.code, 'exam_attempt_conflict');
  } finally { await app.close(); }
});

integration('unparseable exam ids never fall through to a public variant lookup', async () => {
  const app = createApp({ db: db!, auth: {} });
  try {
    for (const raw of ['%', '%ZZ', '%E0%A4%A']) {
      const response = await app.inject({ method: 'GET', url: `/v1/level-tests/exams/${raw}` });
      assert.ok(response.statusCode === 404 || response.statusCode === 400, response.body);
      if (response.statusCode === 404) assert.equal(response.json().error?.code, 'exam_not_found');
    }
  } finally { await app.close(); }
});
