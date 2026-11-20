import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { trace } from '@opentelemetry/api';
import { createServer } from 'node:http';
import type { AddressInfo } from 'node:net';
import { connectDatabase } from '../src/db.js';
import { runLevelTestGenerationJob } from '../src/level-tests-generation-jobs.js';

const databaseURL = process.env.TEST_DATABASE_URL;

for (const failure of ['rejection', 'zero_rows']) test(`final run closure recovers known usage after checkpoint ${failure}`,
  { skip: !databaseURL }, async () => {
    const db = connectDatabase(databaseURL!);
    const weekIndex = failure === 'rejection' ? 987012 : 987013;
    let calls = 0, checkpoints = 0;
    const provider = createServer((request, response) => {
      request.resume(); calls += 1;
      response.end(JSON.stringify({ choices: [{ message: { content: '[]' } }],
        usage: { prompt_tokens: 17, completion_tokens: 4 } }));
    });
    await new Promise<void>(resolve => provider.listen(0, '127.0.0.1', resolve));
    // Inject one transient external-DB failure; final persistence still uses real PostgreSQL.
    const faulty = new Proxy(db, { get(target, key) {
      if (key === 'connect') return async () => {
        const client = await target.connect();
        return new Proxy(client, { get(connection, property) {
          if (property === 'query') return async (sql: string, params?: unknown[]) => {
            if (sql.includes('SET tokens_in=') && ++checkpoints === 2) {
              if (failure === 'rejection') throw new Error('temporary checkpoint failure');
              return { rows: [], rowCount: 0 };
            }
            return connection.query(sql, params);
          };
          const value = Reflect.get(connection, property, connection);
          return typeof value === 'function' ? value.bind(connection) : value;
        } });
      };
      const value = Reflect.get(target, key, target);
      return typeof value === 'function' ? value.bind(target) : value;
    } }) as typeof db;
    const env = { LEVELTEST_LLM_PROVIDER: 'openai', LEVELTEST_LLM_API_KEY: 'local-test-only',
      LEVELTEST_LLM_URL: `http://127.0.0.1:${(provider.address() as AddressInfo).port}/v1` };
    try {
      const result = await runLevelTestGenerationJob(faulty, env, { languageID: 'en', level: 'A1', weekIndex, origin: 'automatic_worker' });
      assert.equal(result.status, 'unknown');
      assert.equal(calls, 1);
      const row = (await db.query("SELECT * FROM level_test_generation_jobs WHERE language_id='en' AND level='A1' AND week_index=$1", [weekIndex])).rows[0]!;
      assert.equal(row.state, 'unknown');
      assert.equal(row.usage_state, 'complete');
      assert.equal(Number(row.tokens_in), 17);
      assert.equal(Number(row.tokens_out), 4);
      assert.equal((await runLevelTestGenerationJob(faulty, env, { languageID: 'en', level: 'A1', weekIndex, origin: 'automatic_worker' })).status, 'unknown');
      assert.equal(calls, 1);
    } finally {
      provider.closeAllConnections();
      await new Promise<void>((resolve, reject) => provider.close(error => error ? reject(error) : resolve()));
      await db.query("DELETE FROM level_test_generation_jobs WHERE language_id='en' AND level='A1' AND week_index=$1", [weekIndex]);
      await db.end();
    }
  });

for (const interrupted of [false, true]) test(`durable failed-run usage survives ${interrupted ? 'an unknown transport outcome' : 'deterministic rejection'}`,
  { skip: !databaseURL }, async () => {
    const db = connectDatabase(databaseURL!);
    const weekIndex = interrupted ? 987011 : 987010;
    let calls = 0;
    const provider = createServer((request, response) => {
      request.resume();
      calls += 1;
      if (interrupted && calls === 2) { request.socket.destroy(); return; }
      response.setHeader('content-type', 'application/json');
      response.end(JSON.stringify({ choices: [{ message: { content: '[]' } }],
        usage: { prompt_tokens: 2, completion_tokens: 3 } }));
    });
    await new Promise<void>(resolve => provider.listen(0, '127.0.0.1', resolve));
    try {
      const env = { LEVELTEST_LLM_PROVIDER: 'openai', LEVELTEST_LLM_API_KEY: 'local-test-only',
        LEVELTEST_LLM_URL: `http://127.0.0.1:${(provider.address() as AddressInfo).port}/v1` };
      const result = await runLevelTestGenerationJob(db, env, { languageID: 'en', level: 'A1', weekIndex, origin: 'automatic_worker' });
      assert.equal(result.status, interrupted ? 'unknown' : 'failed');
      assert.equal(calls, interrupted ? 2 : 8);
      const row = (await db.query("SELECT * FROM level_test_generation_jobs WHERE language_id='en' AND level='A1' AND week_index=$1", [weekIndex])).rows[0]!;
      assert.equal(row.state, interrupted ? 'unknown' : 'failed');
      assert.equal(row.usage_state, interrupted ? 'partial' : 'complete');
      assert.equal(Number(row.tokens_in), interrupted ? 2 : 16);
      assert.equal(Number(row.tokens_out), interrupted ? 3 : 24);
      assert.ok(row.finished_at instanceof Date);
      if (interrupted) {
        assert.equal((await runLevelTestGenerationJob(db, env, { languageID: 'en', level: 'A1', weekIndex, origin: 'automatic_worker' })).status, 'unknown');
        assert.equal(calls, 2);
      }
    } finally {
      provider.closeAllConnections();
      await new Promise<void>((resolve, reject) => provider.close(error => error ? reject(error) : resolve()));
      await db.query("DELETE FROM level_test_generation_jobs WHERE language_id='en' AND level='A1' AND week_index=$1", [weekIndex]);
      await db.end();
    }
  });
test('generation job lock excludes a competing CLI or scheduler before provider calls', { skip: !databaseURL }, async () => {
  const db = connectDatabase(databaseURL!);
  const languageID = 'en', level = 'A1', weekIndex = 987654;
  const key = `level-test-generation:${languageID}:${level}:${weekIndex}`;
  const holder = await db.connect();
  try {
    await db.query('DELETE FROM level_test_generation_jobs WHERE language_id=$1 AND level=$2 AND week_index=$3', [languageID, level, weekIndex]);
    await holder.query('SELECT pg_advisory_lock(hashtextextended($1,0))', [key]);
    const result = await runLevelTestGenerationJob(db, {}, { languageID, level, weekIndex, origin: 'automatic_worker' });
    assert.equal(result.status, 'busy');
    assert.equal((await db.query('SELECT 1 FROM level_test_generation_jobs WHERE language_id=$1 AND level=$2 AND week_index=$3',
      [languageID, level, weekIndex])).rowCount, 0);
  } finally {
    await holder.query('SELECT pg_advisory_unlock(hashtextextended($1,0))', [key]);
    holder.release();
    await db.query('DELETE FROM level_test_generation_jobs WHERE language_id=$1 AND level=$2 AND week_index=$3', [languageID, level, weekIndex]);
    await db.end();
  }
});

test('an unknown durable provider outcome is never automatically reissued', { skip: !databaseURL }, async () => {
  const db = connectDatabase(databaseURL!);
  const languageID = 'en', level = 'A1', weekIndex = 987655;
  try {
    await db.query(`INSERT INTO level_test_generation_jobs(language_id,level,week_index,state,attempts,error_code)
      VALUES($1,$2,$3,'unknown',1,'llm_outcome_unknown')`,
    [languageID, level, weekIndex]);
    const result = await runLevelTestGenerationJob(db, {}, { languageID, level, weekIndex, origin: 'automatic_worker' });
    assert.equal(result.status, 'unknown');
    assert.deepEqual(result.errors, ['reconciliation_required']);
    assert.equal((await db.query('SELECT attempts FROM level_test_generation_jobs WHERE language_id=$1 AND level=$2 AND week_index=$3',
      [languageID, level, weekIndex])).rows[0]?.attempts, 1);
  } finally {
    await db.query('DELETE FROM level_test_generation_jobs WHERE language_id=$1 AND level=$2 AND week_index=$3', [languageID, level, weekIndex]);
    await db.end();
  }
});

test('an existing week does not leave a finished run behind', { skip: !databaseURL }, async () => {
  const db = connectDatabase(databaseURL!);
  const languageID = 'en', level = 'A1', weekIndex = 987656;
  try {
    await db.query('DELETE FROM level_test_generation_jobs WHERE language_id=$1 AND level=$2 AND week_index=$3', [languageID, level, weekIndex]);
    await db.query(`INSERT INTO level_test_weeks(id, language_id, level, week_index, status, pool, variants, generator, model, prompt_version)
      VALUES($1,$2,$3,$4,'draft','{}'::jsonb,'[]'::jsonb,'llm:test','test-model',1) ON CONFLICT DO NOTHING`,
    [randomUUID(), languageID, level, weekIndex]);
    const result = await runLevelTestGenerationJob(db, {}, { languageID, level, weekIndex, origin: 'automatic_worker' });
    assert.equal(result.status, 'exists');
    // A skipped key is not a run: recording it as `finished` would show the
    // operator a completed generation that never happened.
    assert.equal((await db.query('SELECT 1 FROM level_test_generation_jobs WHERE language_id=$1 AND level=$2 AND week_index=$3',
      [languageID, level, weekIndex])).rowCount, 0);
  } finally {
    await db.query('DELETE FROM level_test_generation_jobs WHERE language_id=$1 AND level=$2 AND week_index=$3', [languageID, level, weekIndex]);
    await db.end();
  }
});

test('a manual attempt opens a new run after the automatic cap', { skip: !databaseURL }, async () => {
  const db = connectDatabase(databaseURL!);
  const languageID = 'en', level = 'A2', weekIndex = 987657;
  try {
    await db.query('DELETE FROM level_test_generation_jobs WHERE language_id=$1 AND level=$2 AND week_index=$3', [languageID, level, weekIndex]);
    await db.query(`INSERT INTO level_test_generation_jobs(language_id,level,week_index,state,attempts,error_code)
      VALUES($1,$2,$3,'failed',2,'generation_failed')`, [languageID, level, weekIndex]);
    const automatic = await runLevelTestGenerationJob(db, {}, { languageID, level, weekIndex, origin: 'automatic_worker' });
    assert.equal(automatic.status, 'failed');
    assert.deepEqual(automatic.errors, ['attempt_limit_reached']);
    assert.equal((await db.query('SELECT count(*)::int AS n FROM level_test_generation_jobs WHERE language_id=$1 AND level=$2 AND week_index=$3',
      [languageID, level, weekIndex])).rows[0]?.n, 1);
    const manual = await runLevelTestGenerationJob(db, {}, { languageID, level, weekIndex, origin: 'user_operator', manual: true });
    assert.equal(manual.status, 'failed');
    // The exhausted automatic attempt stays as history; the manual one is a new
    // run with the next ordinal, never an overwrite of the previous failure.
    const rows = (await db.query(`SELECT state, attempts FROM level_test_generation_jobs
      WHERE language_id=$1 AND level=$2 AND week_index=$3 ORDER BY attempts`, [languageID, level, weekIndex])).rows;
    assert.deepEqual(rows.map(row => [row.state, row.attempts]), [['failed', 2], ['failed', 3]]);
  } finally {
    await db.query('DELETE FROM level_test_generation_jobs WHERE language_id=$1 AND level=$2 AND week_index=$3', [languageID, level, weekIndex]);
    await db.end();
  }
});

test('an automatic attempt does not open a run once the cap is reached', { skip: !databaseURL }, async () => {
  const db = connectDatabase(databaseURL!);
  const languageID = 'es', level = 'A1', weekIndex = 987658;
  try {
    await db.query('DELETE FROM level_test_generation_jobs WHERE language_id=$1 AND level=$2 AND week_index=$3', [languageID, level, weekIndex]);
    await db.query(`INSERT INTO level_test_generation_jobs(language_id,level,week_index,state,attempts,error_code)
      VALUES($1,$2,$3,'failed',2,'generation_failed')`, [languageID, level, weekIndex]);
    const result = await runLevelTestGenerationJob(db, {}, { languageID, level, weekIndex, origin: 'automatic_worker' });
    assert.equal(result.status, 'failed');
    assert.deepEqual(result.errors, ['attempt_limit_reached']);
    const rows = (await db.query('SELECT state, attempts FROM level_test_generation_jobs WHERE language_id=$1 AND level=$2 AND week_index=$3',
      [languageID, level, weekIndex])).rows;
    assert.deepEqual(rows.map(row => [row.state, row.attempts]), [['failed', 2]]);
  } finally {
    await db.query('DELETE FROM level_test_generation_jobs WHERE language_id=$1 AND level=$2 AND week_index=$3', [languageID, level, weekIndex]);
    await db.end();
  }
});

test('generation error details keep our own messages and drop provider payloads', async () => {
  const { sanitizeGenerationErrorDetails } = await import('../src/level-tests-generation-jobs.js');
  assert.deepEqual(
    sanitizeGenerationErrorDetails(['reading: only 2 passages (needed 4)', 'writing: empty response'], 'generation_failed'),
    ['reading: only 2 passages (needed 4)', 'writing: empty response']);
  // Anything that looks like a provider body, a stack trace or user content is dropped.
  assert.deepEqual(sanitizeGenerationErrorDetails([
    '{"error":"Invalid API key"}', 'TypeError: fetch failed at /app/src', 'reading: Lunes\nMiércoles'], 'generation_failed'),
    ['generation_failed']);
  assert.deepEqual(sanitizeGenerationErrorDetails(undefined, 'llm_outcome_unknown'), ['llm_outcome_unknown']);
  assert.equal(sanitizeGenerationErrorDetails(Array.from({ length: 20 }, (_, i) => `section: code_${i}`), null).length, 10);
});

test('an interrupted running job is reconciled to unknown before a new provider call', { skip: !databaseURL }, async () => {
  const db = connectDatabase(databaseURL!);
  const weekIndex = 987001;
  try {
    await db.query('DELETE FROM level_test_generation_jobs WHERE language_id=$1 AND level=$2 AND week_index=$3',
      ['en', 'A1', weekIndex]);
    await db.query(`INSERT INTO level_test_generation_jobs(id,language_id,level,week_index,state,attempts,started_at,updated_at)
      VALUES($1,'en','A1',$2,'running',1,now(),now())`, [randomUUID(), weekIndex]);
    const result = await runLevelTestGenerationJob(db, {}, { languageID: 'en', level: 'A1', weekIndex, origin: 'automatic_worker' });
    assert.equal(result.status, 'unknown');
    assert.deepEqual(result.errors, ['reconciliation_required']);
    const row = (await db.query(`SELECT state,error_code,error_details,finished_at FROM level_test_generation_jobs
      WHERE language_id='en' AND level='A1' AND week_index=$1`, [weekIndex])).rows[0]!;
    assert.equal(row.state, 'unknown');
    assert.equal(row.error_code, 'interrupted_generation');
    assert.deepEqual(row.error_details, ['interrupted_generation']);
    assert.ok(row.finished_at instanceof Date);
  } finally { await db.query('DELETE FROM level_test_generation_jobs WHERE language_id=$1 AND level=$2 AND week_index=$3',
    ['en', 'A1', weekIndex]); await db.end(); }
});

test('a finished job without a published week is never silently retried', { skip: !databaseURL }, async () => {
  const db = connectDatabase(databaseURL!);
  const weekIndex = 987002;
  try {
    await db.query('DELETE FROM level_test_generation_jobs WHERE language_id=$1 AND level=$2 AND week_index=$3',
      ['en', 'A1', weekIndex]);
    await db.query(`INSERT INTO level_test_generation_jobs(id,language_id,level,week_index,state,attempts,started_at,updated_at,finished_at)
      VALUES($1,'en','A1',$2,'finished',1,now(),now(),now())`, [randomUUID(), weekIndex]);
    const result = await runLevelTestGenerationJob(db, {}, { languageID: 'en', level: 'A1', weekIndex, origin: 'automatic_worker' });
    assert.equal(result.status, 'unknown');
    assert.deepEqual(result.errors, ['finished_without_week']);
    const row = (await db.query(`SELECT state FROM level_test_generation_jobs
      WHERE language_id='en' AND level='A1' AND week_index=$1`, [weekIndex])).rows[0]!;
    assert.equal(row.state, 'finished');
  } finally { await db.query('DELETE FROM level_test_generation_jobs WHERE language_id=$1 AND level=$2 AND week_index=$3',
    ['en', 'A1', weekIndex]); await db.end(); }
});

test('an aborted run is recorded as unknown instead of failed or finished', { skip: !databaseURL }, async () => {
  const db = connectDatabase(databaseURL!);
  const weekIndex = 987003;
  const controller = new AbortController();
  controller.abort();
  try {
    await db.query('DELETE FROM level_test_generation_jobs WHERE language_id=$1 AND level=$2 AND week_index=$3',
      ['en', 'A1', weekIndex]);
    const result = await runLevelTestGenerationJob(db, {}, { languageID: 'en', level: 'A1', weekIndex,
      origin: 'automatic_worker', signal: controller.signal });
    assert.equal(result.status, 'unknown');
    assert.deepEqual(result.errors, ['llm_outcome_unknown']);
    const row = (await db.query(`SELECT state,error_code FROM level_test_generation_jobs
      WHERE language_id='en' AND level='A1' AND week_index=$1`, [weekIndex])).rows[0]!;
    assert.equal(row.state, 'unknown');
    assert.equal(row.error_code, 'llm_outcome_unknown');
  } finally { await db.query('DELETE FROM level_test_generation_jobs WHERE language_id=$1 AND level=$2 AND week_index=$3',
    ['en', 'A1', weekIndex]); await db.end(); }
});

test('an unexpected failure inside the provider pipeline is recorded as failed, never lost', { skip: !databaseURL }, async () => {
  const db = connectDatabase(databaseURL!);
  const weekIndex = 987004;
  // Route only the generation reads through a failing connection so the job's
  // own bookkeeping still works; this is the defensive catch for a mid-run
  // database or provider failure that never produced an outcome object.
  const failing = new Proxy(db, {
    get(target, property) {
      if (property === 'query') {
        return (sql: string, params?: unknown[]) => String(sql).includes('level_test_weeks')
          ? Promise.reject(new Error('database unavailable'))
          : target.query(sql, params);
      }
      const value = Reflect.get(target, property, target);
      return typeof value === 'function' ? value.bind(target) : value;
    }
  }) as typeof db;
  try {
    await db.query('DELETE FROM level_test_generation_jobs WHERE language_id=$1 AND level=$2 AND week_index=$3', ['en', 'A1', weekIndex]);
    await db.query('DELETE FROM level_test_weeks WHERE language_id=$1 AND level=$2 AND week_index=$3', ['en', 'A1', weekIndex]);
    const result = await runLevelTestGenerationJob(failing, {}, { languageID: 'en', level: 'A1', weekIndex, origin: 'automatic_worker' });
    assert.equal(result.status, 'failed');
    assert.deepEqual(result.errors, ['generation_failed']);
    const row = (await db.query(`SELECT state,error_code,error_details FROM level_test_generation_jobs
      WHERE language_id='en' AND level='A1' AND week_index=$1`, [weekIndex])).rows[0]!;
    assert.equal(row.state, 'failed');
    assert.equal(row.error_code, 'generation_failed');
    assert.deepEqual(row.error_details, ['generation_failed']);
  } finally {
    await db.query('DELETE FROM level_test_generation_jobs WHERE language_id=$1 AND level=$2 AND week_index=$3', ['en', 'A1', weekIndex]);
    await db.end();
  }
});

test('generation trace uses the durable run identity and a bounded terminal outcome', async () => {
  const runId = randomUUID();
  const spans: { name: string; attributes: Record<string, unknown>; events: string[]; ended: boolean }[] = [];
  trace.setGlobalTracerProvider({ getTracer: () => ({ startSpan: (name: string, options: { attributes?: Record<string, unknown> }) => {
    const span = { name, attributes: { ...options.attributes }, events: [] as string[], ended: false };
    spans.push(span);
    return {
      setAttribute: (key: string, value: unknown) => { span.attributes[key] = value; },
      addEvent: (name: string) => { span.events.push(name); },
      end: () => { span.ended = true; },
    };
  } }) } as never);
  let inserted: unknown;
  const client = { release() {}, async query(sql: string, params: unknown[]) {
    if (sql.includes('pg_try_advisory_lock')) return { rows: [{ locked: true }] };
    if (sql.includes('INSERT INTO level_test_generation_jobs')) inserted = params[0];
    return { rows: [] };
  } };
  const db = { connect: async () => client, query: async () => ({ rows: [] }) } as never;
  try {
    const result = await runLevelTestGenerationJob(db, {}, {
      languageID: 'en', level: 'A1', weekIndex: 202647, origin: 'automatic_worker',
    }, runId);
    assert.equal(result.status, 'failed');
    assert.equal(inserted, runId);
    assert.equal(spans.length, 1);
    assert.equal(spans[0]?.name, 'level_test_generation');
    assert.equal(spans[0]?.attributes['generation.run_id'], runId);
    assert.equal(spans[0]?.attributes['generation.outcome'], 'failed');
    assert.equal(spans[0]?.attributes['generation.prompt_version'], 5);
    assert.equal(spans[0]?.ended, true);
  } finally {
    trace.disable();
  }
});
