import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { test } from 'node:test';
import { connectDatabase, transaction } from '../src/db.js';
import { claimDailyJob, parseDailyConfig, renewDailyJobLease } from '../src/daily-jobs.js';
import { reserveDailyCost } from '../src/daily-budget.js';

const databaseURL = process.env.TEST_DATABASE_URL;

test('daily generation is disabled by default and English is the only enabled language', () => {
  const config = parseDailyConfig({});
  assert.equal(config.generationEnabled, false);
  assert.deepEqual(config.enabledLanguages, ['en']);
  assert.equal(config.autoPublishEnabled, false);
});

test('generation settings reject unsupported languages and unsafe booleans', () => {
  assert.throws(() => parseDailyConfig({ DAILY_ENABLED_LANGUAGES: 'en,fr' }), /invalid/i);
  assert.throws(() => parseDailyConfig({ DAILY_GENERATION_ENABLED: 'yes' }), /invalid/i);
});

test('expired leases do not retry an operation whose provider result was already returned', async () => {
  const statements: string[] = [];
  const tx = { query: async (sql: string) => {
    statements.push(sql);
    return { rows: [{ state: 'unknown' }] };
  } } as any;
  const { recoverExpiredDailyJobs } = await import('../src/daily-jobs.js');
  await recoverExpiredDailyJobs(tx, new Date('2026-11-08T12:00:00Z'));
  assert.match(statements[1]!, /o\.state IN \('unknown','succeeded'\)/);
  assert.match(statements[1]!, /THEN 'unknown'/);
  assert.match(statements[1]!, /o\.state='failed'/);
});

test('expired review job recovery exposes terminal review state to the learner poll route',async()=>{
  const statements:string[]=[];
  const tx={query:async(sql:string)=>{statements.push(sql);return sql.includes('UPDATE daily_jobs')?{rows:[{id:randomUUID(),kind:'review',attempt_id:randomUUID(),state:'unknown'}]}:{rows:[],rowCount:1};}} as any;
  const {recoverExpiredDailyJobs}=await import('../src/daily-jobs.js');
  await recoverExpiredDailyJobs(tx,new Date('2026-11-08T12:00:00Z'));
  assert.ok(statements.some(sql=>sql.includes('UPDATE daily_attempts SET review_state')));
});

test('safe pre-send review reclaim resets its learner status for the next worker',async()=>{
  const statements:string[]=[];
  const tx={query:async(sql:string)=>{statements.push(sql);return sql.includes('UPDATE daily_jobs')?{rows:[{id:randomUUID(),kind:'review',attempt_id:randomUUID(),state:'queued'}]}:{rows:[],rowCount:1};}} as any;
  const {recoverExpiredDailyJobs}=await import('../src/daily-jobs.js');
  await recoverExpiredDailyJobs(tx,new Date('2026-11-08T12:00:00Z'));
  assert.ok(statements.some(sql=>sql.includes('UPDATE daily_attempts SET review_state')));
});

test('daily lease heartbeat is fenced to the current owner and reports a lost lease', async () => {
  const jobId=randomUUID(),now=new Date('2026-11-08T12:00:00Z');
  let values:unknown[]=[];
  const tx={query:async(_sql:string,args:unknown[])=>{values=args;return {rowCount:1};}} as any;
  await renewDailyJobLease(tx,jobId,'worker-a',now,120_000);
  assert.deepEqual(values,[jobId,'worker-a',now,120_000]);
  assert.rejects(renewDailyJobLease({query:async()=>({rowCount:0})} as any,jobId,'worker-a',now,120_000),/daily_job_lease_lost/);
});

test('provider send fences the job row before switching its operation to sending',async()=>{
  const statements:string[]=[];
  const tx={query:async(sql:string)=>{statements.push(sql);if(sql.includes('SELECT job_id'))return {rows:[{job_id:'11111111-1111-4111-8111-111111111111'}]};
    if(sql.includes('SELECT id FROM daily_jobs'))return {rows:[{id:'11111111-1111-4111-8111-111111111111'}]};return {rowCount:1};}} as any;
  const {startDailyProviderOperation}=await import('../src/daily-jobs.js');
  await startDailyProviderOperation(tx,'22222222-2222-4222-8222-222222222222','worker-a',new Date('2026-11-08T12:00:00Z'));
  assert.match(statements[1]!,/FROM daily_jobs[\s\S]*FOR UPDATE/);
  assert.match(statements[2]!,/UPDATE daily_provider_operations[\s\S]*state='sending'/);
});

test('daily worker claim filters by job kind so content and review workers cannot strand each other', async () => {
  let sql = '';
  let values: unknown[] = [];
  const job = { id: randomUUID(), kind: 'review', binding_id: randomUUID(), language: 'en', editorial_date: null,
    attempt_id: randomUUID(), state: 'running', lease_owner: 'review-worker', lease_until: new Date(), config_snapshot: {} };
  const tx = { query: async (statement: string, parameters: unknown[]) => {
    sql = statement; values = parameters; return { rows: [job] };
  } } as any;
  const result = await claimDailyJob(tx, 'review-worker', 30_000, () => new Date('2026-11-07T12:00:00Z'), 'review');
  assert.equal(result?.kind, 'review');
  assert.match(sql, /kind\s*=\s*\$4/);
  assert.deepEqual(values, ['review-worker', new Date('2026-11-07T12:00:00Z'), 30_000, 'review']);
});

test('two workers cannot claim the same queued job', { skip: !databaseURL && 'Set TEST_DATABASE_URL.' }, async () => {
  const db = connectDatabase(databaseURL!);
  const jobId = randomUUID();
  try {
    await db.query(`INSERT INTO daily_jobs(id,kind,binding_id,language,editorial_date,state,config_snapshot)
      VALUES($1,'content',$2,'en','2026-11-07','queued','{}'::jsonb)`, [jobId, randomUUID()]);
    const claims = await Promise.all([
      transaction(db, tx => claimDailyJob(tx, 'worker-a', 30_000, () => new Date('2026-11-07T12:00:00Z'))),
      transaction(db, tx => claimDailyJob(tx, 'worker-b', 30_000, () => new Date('2026-11-07T12:00:00Z'))),
    ]);
    assert.equal(claims.filter(Boolean).length, 1);
  } finally {
    await db.query('DELETE FROM daily_jobs WHERE id=$1', [jobId]);
    await db.end();
  }
});

test('zero budget rejects a reservation without writing one', { skip: !databaseURL && 'Set TEST_DATABASE_URL.' }, async () => {
  const db = connectDatabase(databaseURL!);
  const bindingId = randomUUID();
  try {
    await db.query(`INSERT INTO daily_budget_limits(cost_center,budget_date,max_nano_usd)
      VALUES('review','2026-11-07',0) ON CONFLICT(cost_center,budget_date) DO UPDATE SET max_nano_usd=0`);
    await assert.rejects(transaction(db, tx => reserveDailyCost(tx,
      { bindingId, jobId: randomUUID(), budgetDate: '2026-11-07' }, 'review', 1n)), /daily_budget_unavailable/);
    assert.equal((await db.query('SELECT 1 FROM daily_cost_reservations WHERE binding_id=$1', [bindingId])).rowCount, 0);
  } finally {
    await db.query('DELETE FROM daily_cost_reservations WHERE binding_id=$1', [bindingId]);
    await db.end();
  }
});

test('expired job without a sent operation is requeued, while a sent operation becomes unknown',
  { skip: !databaseURL && 'Set TEST_DATABASE_URL.' }, async () => {
    const db = connectDatabase(databaseURL!);
    const safeJob = randomUUID(), unknownJob = randomUUID(), instant = new Date('2026-11-07T12:00:00Z');
    try {
      await db.query(`INSERT INTO daily_jobs(id,kind,binding_id,language,editorial_date,state,lease_owner,lease_until,config_snapshot)
        VALUES($1,'content',$2,'en','2026-11-07','running','worker-a',$3,'{}'::jsonb),
              ($4,'content',$5,'en','2026-11-08','running','worker-b',$3,'{}'::jsonb)`,
      [safeJob,randomUUID(),instant,unknownJob,randomUUID()]);
      await db.query(`INSERT INTO daily_provider_operations(id,job_id,phase,dispatch_key,state,sent_at)
        VALUES($1,$2,'generate',$3,'sending',$4)`, [randomUUID(),unknownJob,`dispatch-${unknownJob}`,instant]);
      const recovered = await transaction(db, tx => import('../src/daily-jobs.js').then(m => m.recoverExpiredDailyJobs(tx,instant)));
      assert.deepEqual(recovered, { requeued: 1, unknown: 1 });
      assert.equal((await db.query('SELECT state FROM daily_jobs WHERE id=$1',[safeJob])).rows[0]?.state,'queued');
      assert.equal((await db.query('SELECT state FROM daily_jobs WHERE id=$1',[unknownJob])).rows[0]?.state,'unknown');
    } finally {
      await db.query('DELETE FROM daily_provider_operations WHERE job_id=$1',[unknownJob]);
      await db.query('DELETE FROM daily_jobs WHERE id=ANY($1::uuid[])',[[safeJob,unknownJob]]);
      await db.end();
    }
  });

test('concurrent cost reservations never exceed the locked daily budget', { skip: !databaseURL && 'Set TEST_DATABASE_URL.' }, async () => {
  const db = connectDatabase(databaseURL!); const firstJob=randomUUID(),secondJob=randomUUID();
  const firstBinding=randomUUID(),secondBinding=randomUUID();
  try {
    await db.query(`INSERT INTO daily_budget_limits(cost_center,budget_date,max_nano_usd) VALUES('content','2026-11-07',10)
      ON CONFLICT(cost_center,budget_date) DO UPDATE SET max_nano_usd=10`);
    await db.query(`INSERT INTO daily_jobs(id,kind,binding_id,language,state,config_snapshot)
      VALUES($1,'review',$2,'en','queued','{}'::jsonb),($3,'review',$4,'en','queued','{}'::jsonb)`,
    [firstJob,randomUUID(),secondJob,randomUUID()]);
    const reserve = (jobId:string,bindingId:string) => transaction(db,tx=>reserveDailyCost(tx,
      {jobId,bindingId,budgetDate:'2026-11-07'},'content',7n));
    const results = await Promise.allSettled([reserve(firstJob,firstBinding),reserve(secondJob,secondBinding)]);
    assert.equal(results.filter(x=>x.status==='fulfilled').length,1);
    assert.equal(results.filter(x=>x.status==='rejected').length,1);
    assert.equal((await db.query(`SELECT SUM(max_nano_usd)::text AS total FROM daily_cost_reservations
      WHERE cost_center='content' AND budget_date='2026-11-07' AND state IN ('held','unknown')`)).rows[0]?.total,'7');
  } finally {
    await db.query('DELETE FROM daily_cost_reservations WHERE job_id=ANY($1::uuid[])',[[firstJob,secondJob]]);
    await db.query('DELETE FROM daily_jobs WHERE id=ANY($1::uuid[])',[[firstJob,secondJob]]);
    await db.end();
  }
});
