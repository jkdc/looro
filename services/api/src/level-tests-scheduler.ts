import { weekIndex } from './level-tests.js';

const SUPPORTED_LANGUAGES = ['en', 'es'] as const;
const SUPPORTED_LEVELS = ['A1', 'A2', 'B1'] as const;
export const LEVEL_TEST_JOB_TIMEOUT_MS = 15 * 60_000;

export interface LevelTestSchedulerConfig {
  enabled: boolean;
  hour: number;
  day: number;
  languages: string[];
  levels: string[];
  timeoutMs: number;
}
export interface SchedulerClock {
  now(): Date;
  setTimeout(callback: () => void, delayMs: number): unknown;
  clearTimeout(handle: unknown): void;
}
export interface LevelTestScheduler {
  start(): void;
  isStarted(): boolean;
  stop(): Promise<void>;
}
interface SchedulerDeps {
  config: LevelTestSchedulerConfig;
  clock?: SchedulerClock;
  runWeek: (weekId: number, signal: AbortSignal, timeoutMs: number) => Promise<void>;
  timeoutMs?: number;
}

function boundedInteger(raw: string | undefined, fallback: number, min: number, max: number, name: string): number {
  if (raw === undefined) return fallback;
  if (!/^(0|[1-9][0-9]*)$/.test(raw)) throw new Error(`invalid_${name}`);
  const value = Number(raw);
  if (!Number.isSafeInteger(value) || value < min || value > max) throw new Error(`invalid_${name}`);
  return value;
}

function list<T extends string>(raw: string | undefined, defaults: readonly T[], allowed: readonly T[], name: string): T[] {
  if (raw === undefined) return [...defaults];
  const values = raw.split(',').map(item => item.trim());
  if (!values.length || values.some(item => !item) || new Set(values).size !== values.length ||
      values.length > allowed.length || values.some(item => !allowed.includes(item as T)))
    throw new Error(`invalid_${name}`);
  return values as T[];
}

export function parseLevelTestSchedulerConfig(env: NodeJS.ProcessEnv): LevelTestSchedulerConfig {
  const rawEnabled = env.LEVELTEST_GENERATE_ENABLED ?? 'false';
  if (rawEnabled !== 'true' && rawEnabled !== 'false') throw new Error('invalid_LEVELTEST_GENERATE_ENABLED');
  const config: LevelTestSchedulerConfig = {
    enabled: rawEnabled === 'true',
    hour: boundedInteger(env.LEVELTEST_GENERATE_HOUR, 4, 0, 23, 'LEVELTEST_GENERATE_HOUR'),
    day: boundedInteger(env.LEVELTEST_GENERATE_DAY, 7, 1, 7, 'LEVELTEST_GENERATE_DAY'),
    languages: list(env.LEVELTEST_GENERATE_LANGS, SUPPORTED_LANGUAGES, SUPPORTED_LANGUAGES, 'LEVELTEST_GENERATE_LANGS'),
    levels: list(env.LEVELTEST_GENERATE_LEVELS, SUPPORTED_LEVELS, SUPPORTED_LEVELS, 'LEVELTEST_GENERATE_LEVELS'),
    timeoutMs: boundedInteger(env.LEVELTEST_GENERATE_TIMEOUT_MS, LEVEL_TEST_JOB_TIMEOUT_MS, 30_000, 30 * 60_000, 'LEVELTEST_GENERATE_TIMEOUT_MS'),
  };
  const variants = boundedInteger(env.LEVELTEST_VARIANTS, 3, 1, 5, 'LEVELTEST_VARIANTS');
  void variants;
  const publishMode = env.LEVELTEST_PUBLISH_MODE ?? 'review';
  if (!['review', 'auto'].includes(publishMode)) throw new Error('invalid_LEVELTEST_PUBLISH_MODE');
  const verifyMode = env.LEVELTEST_VERIFY ?? 'flag';
  if (!['flag', 'strict', 'off'].includes(verifyMode)) throw new Error('invalid_LEVELTEST_VERIFY');
  return config;
}

function isoDay(date: Date): number { return date.getUTCDay() || 7; }

function targetInCurrentIsoWeek(now: Date, hour: number, day: number): Date {
  const monday = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate(), 0, 0, 0, 0));
  monday.setUTCDate(monday.getUTCDate() - isoDay(now) + 1);
  monday.setUTCDate(monday.getUTCDate() + day - 1);
  monday.setUTCHours(hour);
  return monday;
}

export function nextScheduledRunUtc(after: Date, hour: number, day: number): Date {
  if (!Number.isFinite(after.getTime()) || !Number.isInteger(hour) || hour < 0 || hour > 23 ||
      !Number.isInteger(day) || day < 1 || day > 7) throw new Error('invalid_scheduler_time');
  const next = targetInCurrentIsoWeek(after, hour, day);
  if (next.getTime() <= after.getTime()) next.setUTCDate(next.getUTCDate() + 7);
  return next;
}

export async function runLevelTestCombinations(
  config: Pick<LevelTestSchedulerConfig, 'languages' | 'levels'>,
  weekId: number,
  schedulerSignal: AbortSignal,
  runJob: (languageID: string, level: string, weekId: number, signal: AbortSignal) => Promise<unknown>,
  onFailure: (languageID: string, level: string, code: string) => void,
  clock: SchedulerClock = systemClock,
  timeoutMs = LEVEL_TEST_JOB_TIMEOUT_MS,
): Promise<void> {
  for (const languageID of config.languages) for (const level of config.levels) {
    if (schedulerSignal.aborted) return;
    const controller = new AbortController();
    const abortFromScheduler = () => controller.abort(schedulerSignal.reason);
    schedulerSignal.addEventListener('abort', abortFromScheduler, { once: true });
    const timeout = clock.setTimeout(() => controller.abort(new Error('generation_timeout')), timeoutMs);
    try {
      await runJob(languageID, level, weekId, controller.signal);
    } catch (error) {
      if (!schedulerSignal.aborted) {
        const code = error instanceof Error && /^[a-z0-9_]{1,80}$/.test(error.message)
          ? error.message : 'generation_failed';
        onFailure(languageID, level, code);
      }
    } finally {
      clock.clearTimeout(timeout);
      schedulerSignal.removeEventListener('abort', abortFromScheduler);
    }
  }
}

const systemClock: SchedulerClock = {
  now: () => new Date(),
  setTimeout: (callback, delayMs) => setTimeout(callback, delayMs),
  clearTimeout: handle => clearTimeout(handle as ReturnType<typeof setTimeout>),
};

export function createLevelTestScheduler(deps: SchedulerDeps): LevelTestScheduler {
  const clock = deps.clock ?? systemClock;
  const timeoutMs = deps.timeoutMs ?? deps.config.timeoutMs;
  if (!Number.isSafeInteger(timeoutMs) || timeoutMs < 1_000 || timeoutMs > 30 * 60_000)
    throw new Error('invalid_LEVELTEST_GENERATE_TIMEOUT');
  let started = false;
  let timer: unknown;
  let activeController: AbortController | undefined;
  let activeRun: Promise<void> | undefined;

  const arm = (target: Date) => {
    if (!started) return;
    if (timer !== undefined) clock.clearTimeout(timer);
    const delay = Math.max(0, target.getTime() - clock.now().getTime());
    timer = clock.setTimeout(() => {
      timer = undefined;
      const now = clock.now();
      if (now.getTime() < target.getTime()) {
        arm(nextScheduledRunUtc(now, deps.config.hour, deps.config.day));
        return;
      }
      activeController = new AbortController();
      const controller = activeController;
      const weekId = weekIndex(now);
      const runTimeout = clock.setTimeout(() => controller.abort(new Error('generation_timeout')), timeoutMs);
      activeRun = (async () => {
        try { await deps.runWeek(weekId, controller.signal, timeoutMs); }
        catch { /* Each combination reports a safe code; keep the scheduler alive. */ }
        finally {
          clock.clearTimeout(runTimeout);
          if (activeController === controller) activeController = undefined;
          activeRun = undefined;
          if (started) arm(nextScheduledRunUtc(clock.now(), deps.config.hour, deps.config.day));
        }
      })();
    }, delay);
  };

  return {
    isStarted: () => started && deps.config.enabled,
    start() {
      if (started) return;
      started = true;
      if (!deps.config.enabled) return;
      const now = clock.now();
      const target = targetInCurrentIsoWeek(now, deps.config.hour, deps.config.day);
      // Startup recovery only checks this week's durable job keys; it never backfills old weeks.
      arm(now.getTime() >= target.getTime() ? now : target);
    },
    async stop() {
      started = false;
      if (timer !== undefined) { clock.clearTimeout(timer); timer = undefined; }
      activeController?.abort(new Error('scheduler_shutdown'));
      await activeRun;
    },
  };
}
