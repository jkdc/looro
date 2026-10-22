import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { connectDatabase } from '../src/db.js';
import { runLevelTestGenerationJob } from '../src/level-tests-generation-jobs.js';

const databaseURL = process.env.TEST_DATABASE_URL;
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
