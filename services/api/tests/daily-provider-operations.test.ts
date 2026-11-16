import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { test } from 'node:test';
import { connectDatabase, transaction } from '../src/db.js';
import { finishDailyProviderOperation, reserveDailyProviderOperation, startDailyProviderOperation } from '../src/daily-jobs.js';

const databaseURL = process.env.TEST_DATABASE_URL;

function fakeTx(operation: Record<string, unknown> | null = null) {
  const calls: { sql: string; values: unknown[] }[] = [];
  const tx = { query: async (sql: string, values: unknown[] = []) => {
    calls.push({ sql, values });
    if (sql.includes('SELECT job_id FROM daily_provider_operations')) return { rows: operation?.job_id ? [{ job_id: operation.job_id }] : [] };
    if (sql.includes('FROM daily_jobs') && sql.includes('FOR UPDATE')) return { rows: [{ state: 'running', lease_owner: 'worker-a', lease_until: new Date('2026-11-08T12:01:00Z') }] };
    if (sql.includes('FROM daily_provider_operations') && sql.includes('FOR UPDATE')) return { rows: operation ? [operation] : [] };
    if (sql.includes('INSERT INTO daily_provider_operations')) return { rows: [{ id: values[0] }] };
    if (sql.startsWith('UPDATE daily_provider_operations')) return { rowCount: 1, rows: [{ id: values[0] }] };
    return { rows: [], rowCount: 1 };
  } } as any;
  return { tx, calls };
}

test('provider operation reserves a stable dispatch before outbound send', async () => {
  const { tx, calls } = fakeTx();
  const jobId = randomUUID(), reservationId = randomUUID();
  const result = await reserveDailyProviderOperation(tx, { jobId, workerId: 'worker-a', phase: 'generate',
    dispatchKey: `${jobId}:generate:v1`, reservationId, now: new Date('2026-11-08T12:00:00Z') });
  assert.equal(result.reused, false);
  assert.match(calls[0]!.sql, /lease_owner,lease_until/);
  assert.deepEqual(calls[0]!.values, [jobId]);
  assert.match(calls[2]!.sql, /INSERT INTO daily_provider_operations/);
  assert.equal(calls[2]!.values[2], 'generate');
  assert.equal(calls[2]!.values[3], `${jobId}:generate:v1`);
  assert.equal(calls[2]!.values[4], reservationId);
});

test('a reserved operation may send once and can be terminally closed without an automatic replay', async () => {
  const id = randomUUID(), jobId = randomUUID();
  const reserved = fakeTx({ id, job_id: jobId, phase: 'review', reservation_id: randomUUID(), state: 'reserved' });
  await startDailyProviderOperation(reserved.tx, id, 'worker-a', new Date('2026-11-08T12:00:00Z'));
  assert.match(reserved.calls.at(-1)!.sql, /state='sending'/);
  const sent = fakeTx();
  await finishDailyProviderOperation(sent.tx, id, 'worker-a', 'unknown', new Date('2026-11-08T12:00:00Z'));
  assert.match(sent.calls[0]!.sql, /SET state=\$3/);
  assert.equal(sent.calls[0]!.values[2], 'unknown');
  await assert.rejects(finishDailyProviderOperation({ query: async () => ({ rowCount: 0 }) } as any, id, 'worker-a','succeeded',
    new Date('2026-11-08T12:00:00Z'),'a'.repeat(64)),
    /daily_provider_operation_not_writable/);
});

test('provider operation rejects malformed dispatch bindings before database access', async () => {
  let calls = 0;
  const tx = { query: async () => { calls++; return { rows: [] }; } } as any;
  await assert.rejects(reserveDailyProviderOperation(tx, { jobId: 'bad', workerId: 'worker-a', phase: 'generate',
    dispatchKey: 'unsafe key', reservationId: randomUUID(), now: new Date() }), /invalid_daily_operation/);
  assert.equal(calls, 0);
});

test('PostgreSQL persists one unknown dispatch and refuses an automatic resend', { skip: !databaseURL && 'Set TEST_DATABASE_URL.' }, async () => {
  const db = connectDatabase(databaseURL!);
  const jobId = randomUUID(), bindingId = randomUUID(), reservationId = randomUUID(), now = new Date();
  const workerId = `worker-${randomUUID()}`;
  const dispatchKey = `${jobId}:review:v1`;
  try {
    await db.query(`INSERT INTO daily_jobs(id,kind,binding_id,language,editorial_date,state,lease_owner,lease_until,config_snapshot)
      VALUES($1,'content',$2,'en',$3,'running',$4,$5,'{}'::jsonb)`,
    [jobId,bindingId,now.toISOString().slice(0,10),workerId,new Date(now.getTime()+60_000)]);
    await db.query(`INSERT INTO daily_cost_reservations(id,job_id,cost_center,budget_date,binding_id,max_nano_usd,state)
      VALUES($1,$2,'content',$3,$4,100,'held')`,[reservationId,jobId,now.toISOString().slice(0,10),bindingId]);
    const binding = { jobId,workerId,phase:'generate' as const,dispatchKey,reservationId,now };
    const operation = await transaction(db,tx=>reserveDailyProviderOperation(tx,binding));
    await transaction(db,tx=>startDailyProviderOperation(tx,operation.id,workerId,now));
    await transaction(db,tx=>finishDailyProviderOperation(tx,operation.id,workerId,'unknown',now));
    await assert.rejects(transaction(db,tx=>reserveDailyProviderOperation(tx,binding)),/daily_operation_not_dispatchable/);
    const row=(await db.query('SELECT state FROM daily_provider_operations WHERE id=$1',[operation.id])).rows[0];
    assert.equal(row.state,'unknown');
  } finally {
    await db.query('DELETE FROM daily_provider_operations WHERE job_id=$1',[jobId]);
    await db.query('DELETE FROM daily_cost_reservations WHERE job_id=$1',[jobId]);
    await db.query('DELETE FROM daily_jobs WHERE id=$1',[jobId]);
    await db.end();
  }
});
