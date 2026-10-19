import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  createLevelTestScheduler, nextScheduledRunUtc, parseLevelTestSchedulerConfig, runLevelTestCombinations,
  type SchedulerClock,
} from '../src/level-tests-scheduler.js';

class FakeClock implements SchedulerClock {
  private time: number;
  private nextID = 1;
  private timers = new Map<number, { at: number; callback: () => void }>();
  constructor(now: Date) { this.time = now.getTime(); }
  now() { return new Date(this.time); }
  setTimeout(callback: () => void, delayMs: number): unknown {
    const id = this.nextID++;
    this.timers.set(id, { at: this.time + delayMs, callback });
    return id;
  }
  clearTimeout(handle: unknown) { this.timers.delete(handle as number); }
  async advance(ms: number) {
    this.time += ms;
    for (;;) {
      const due = [...this.timers.entries()].filter(([, timer]) => timer.at <= this.time)
        .sort((a, b) => a[1].at - b[1].at)[0];
      if (!due) break;
      this.timers.delete(due[0]);
      due[1].callback();
      for (let i = 0; i < 10; i++) await Promise.resolve();
    }
  }
  get pendingTimers() { return this.timers.size; }
}

const enabled = () => parseLevelTestSchedulerConfig({
  LEVELTEST_GENERATE_ENABLED: 'true',
});

test('next scheduled run is the next configured ISO weekday and hour in UTC', () => {
  assert.equal(nextScheduledRunUtc(new Date('2026-10-19T03:59:59.000Z'), 4, 1).toISOString(),
    '2026-10-19T04:00:00.000Z');
  assert.equal(nextScheduledRunUtc(new Date('2026-10-19T04:00:00.000Z'), 4, 1).toISOString(),
    '2026-10-26T04:00:00.000Z');
  assert.equal(nextScheduledRunUtc(new Date('2026-10-20T05:00:00.000Z'), 4, 1).toISOString(),
    '2026-10-26T04:00:00.000Z');
});

test('scheduler configuration is disabled by default and rejects invalid or duplicate bounds', () => {
  assert.equal(parseLevelTestSchedulerConfig({}).enabled, false);
  assert.deepEqual(enabled(), { enabled: true, hour: 4, day: 1, languages: ['en', 'es'], levels: ['A1', 'A2', 'B1'], timeoutMs: 900_000 });
  for (const env of [
    { LEVELTEST_GENERATE_ENABLED: 'yes' },
    { LEVELTEST_GENERATE_HOUR: '24' },
    { LEVELTEST_GENERATE_DAY: '0' },
    { LEVELTEST_GENERATE_LANGS: 'en,en' },
    { LEVELTEST_GENERATE_LANGS: 'fr' },
    { LEVELTEST_GENERATE_LEVELS: 'A1,A1' },
    { LEVELTEST_GENERATE_LEVELS: 'C2' },
    { LEVELTEST_GENERATE_TIMEOUT_MS: '1000' },
    { LEVELTEST_VARIANTS: '0' },
    { LEVELTEST_PUBLISH_MODE: 'unsafe' },
    { LEVELTEST_VERIFY: 'sometimes' },
  ]) assert.throws(() => parseLevelTestSchedulerConfig(env));
});

test('disabled scheduler creates no timer and does not run generation', async () => {
  const clock = new FakeClock(new Date('2026-10-19T05:00:00Z'));
  let runs = 0;
  const scheduler = createLevelTestScheduler({ config: parseLevelTestSchedulerConfig({}), clock,
    runWeek: async () => { runs += 1; } });
  scheduler.start();
  await clock.advance(0);
  await scheduler.stop();
  assert.equal(runs, 0);
  assert.equal(clock.pendingTimers, 0);
});

test('startup after the weekly instant recovers only the current ISO week then schedules the next run', async () => {
  const clock = new FakeClock(new Date('2026-10-19T05:00:00Z'));
  const weeks: number[] = [];
  const scheduler = createLevelTestScheduler({ config: enabled(), clock,
    runWeek: async weekId => { weeks.push(weekId); } });
  scheduler.start();
  await clock.advance(0);
  assert.deepEqual(weeks, [202643]);
  assert.equal(clock.pendingTimers, 1);
  await scheduler.stop();
  assert.equal(clock.pendingTimers, 0);
});

test('scheduler recalculates its next one-shot timer after a delayed run completes', async () => {
  const clock = new FakeClock(new Date('2026-10-19T03:00:00Z'));
  let finishRun!: () => void;
  let calls = 0;
  const scheduler = createLevelTestScheduler({ config: enabled(), clock,
    runWeek: async () => { calls += 1; await new Promise<void>(resolve => { finishRun = resolve; }); } });
  scheduler.start();
  await clock.advance(60 * 60 * 1000);
  await Promise.resolve(); await Promise.resolve();
  assert.equal(calls, 1);
  await clock.advance(14 * 24 * 60 * 60 * 1000);
  finishRun();
  await Promise.resolve(); await Promise.resolve(); await Promise.resolve();
  assert.equal(clock.pendingTimers, 1);
  await scheduler.stop();
});

test('per-combination failure does not prevent remaining language-level jobs', async () => {
  const calls: string[] = [];
  const errors: string[] = [];
  await runLevelTestCombinations({ languages: ['en', 'es'], levels: ['A1', 'A2'] },
    202643, new AbortController().signal, async (languageID, level) => {
      calls.push(`${languageID}/${level}`);
      if (languageID === 'en' && level === 'A1') throw new Error('private provider detail');
    }, (_languageID, _level, code) => errors.push(code));
  assert.deepEqual(calls, ['en/A1', 'en/A2', 'es/A1', 'es/A2']);
  assert.deepEqual(errors, ['generation_failed']);
});

test('shutdown aborts and drains active generation before stop resolves', async () => {
  const clock = new FakeClock(new Date('2026-10-19T05:00:00Z'));
  let signal: AbortSignal | undefined;
  let settled = false;
  const scheduler = createLevelTestScheduler({ config: enabled(), clock,
    runWeek: async (_weekId, activeSignal) => {
      signal = activeSignal;
      await new Promise<void>(resolve => activeSignal.addEventListener('abort', () => resolve(), { once: true }));
      settled = true;
    } });
  scheduler.start();
  await clock.advance(0);
  await scheduler.stop();
  assert.equal(signal?.aborted, true);
  assert.equal(settled, true);
  assert.equal(clock.pendingTimers, 0);
});

test('generation timeout aborts the active week and re-arms one future timer', async () => {
  const clock = new FakeClock(new Date('2026-10-19T05:00:00Z'));
  let signal: AbortSignal | undefined;
  const scheduler = createLevelTestScheduler({ config: enabled(), clock, timeoutMs: 1_000,
    runWeek: async (weekId, activeSignal, timeoutMs) => {
      await runLevelTestCombinations({ languages: ['en'], levels: ['A1'] }, weekId, activeSignal, async (_languageID, _level, _week, jobSignal) => {
        signal = jobSignal;
        await new Promise<void>(resolve => jobSignal.addEventListener('abort', () => resolve(), { once: true }));
      }, () => {}, clock, timeoutMs);
    } });
  scheduler.start();
  await clock.advance(0);
  await clock.advance(1_000);
  assert.equal(signal?.aborted, true);
  assert.equal(clock.pendingTimers, 1);
  await scheduler.stop();
});


test('scheduler enforces a single timeout across the complete week run', async () => {
  const clock = new FakeClock(new Date('2026-10-19T05:00:00Z'));
  let signal: AbortSignal | undefined;
  const scheduler = createLevelTestScheduler({ config: enabled(), clock, timeoutMs: 1_000,
    runWeek: async (_weekId, activeSignal) => {
      signal = activeSignal;
      await new Promise<void>(resolve => activeSignal.addEventListener('abort', () => resolve(), { once: true }));
    } });
  scheduler.start();
  await clock.advance(0);
  await clock.advance(1_000);
  assert.equal(signal?.aborted, true);
  assert.equal(clock.pendingTimers, 1);
  await scheduler.stop();
});
