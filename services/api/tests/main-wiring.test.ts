import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const compiledMain = new URL('../src/main.js', import.meta.url);
const isCompiled = existsSync(fileURLToPath(compiledMain));
const mainEntry = isCompiled ? compiledMain : new URL('../src/main.ts', import.meta.url);
const registerHooks = new URL('./support/main-wiring-register.mjs', import.meta.url);

function runMainProbe(failure?: string, signal?: string) {
  const loaderArgs = isCompiled ? [] : ['--import', 'tsx'];
  const result = spawnSync(process.execPath, [...loaderArgs, '--import', fileURLToPath(registerHooks), fileURLToPath(mainEntry)], {
    cwd: process.cwd(), encoding: 'utf8', timeout: 10_000,
    env: {
      ...process.env,
      DATABASE_URL: 'postgres://probe.invalid/test',
      PUBLIC_ORIGIN: 'http://localhost:8080',
      MAIN_WIRING_FAILURE: failure ?? '',
      MAIN_WIRING_SIGNAL: signal ?? '',
      MAIN_WIRING_ENTRY: mainEntry.href,
    },
  });
  assert.equal(result.error, undefined, result.error?.message);
  const output = result.stdout.split('\n').find(line => line.startsWith('MAIN_WIRING_EVENTS='));
  assert.ok(output, `child did not report composition events; stderr: ${result.stderr}`);
  return { status: result.status, events: JSON.parse(output.slice('MAIN_WIRING_EVENTS='.length)) as string[] };
}

function has(events: string[], event: string) {
  assert.ok(events.includes(event), `expected event ${event}; received: ${events.join(', ')}`);
}

function before(events: string[], first: string, second: string) {
  assert.ok(events.indexOf(first) >= 0, `missing ${first}; received: ${events.join(', ')}`);
  assert.ok(events.indexOf(second) >= 0, `missing ${second}; received: ${events.join(', ')}`);
  assert.ok(events.indexOf(first) < events.indexOf(second), `expected ${first} before ${second}; received: ${events.join(', ')}`);
}

test('main composition starts configured workers only after listening succeeds', () => {
  const result = runMainProbe();
  assert.equal(result.status, 0);
  before(result.events, 'http_listen', 'minute_worker_start');
  before(result.events, 'minute_worker_start', 'generation_worker_start');
  before(result.events, 'generation_worker_start', 'api_started');
  assert.ok(!result.events.includes('startup_failed'), result.events.join(', '));
});

test('main composition closes initialized resources when generation metrics initialization fails', () => {
  const result = runMainProbe('generation_metrics');
  assert.equal(result.status, 1);
  has(result.events, 'startup_failed');
  before(result.events, 'observability_shutdown', 'database_close');
  assert.ok(!result.events.includes('api_started'), result.events.join(', '));
  assert.ok(!result.events.includes('minute_worker_start'), result.events.join(', '));
  assert.ok(!result.events.includes('generation_worker_start'), result.events.join(', '));
  assert.ok(!result.events.includes('http_close'), result.events.join(', '));
});

test('main composition stops metrics refresh and closes resources when pruning fails', () => {
  const result = runMainProbe('prune_authentication');
  assert.equal(result.status, 1);
  has(result.events, 'startup_failed');
  before(result.events, 'metrics_refresh_stop', 'observability_shutdown');
  before(result.events, 'observability_shutdown', 'database_close');
  assert.ok(!result.events.includes('api_started'), result.events.join(', '));
  assert.ok(!result.events.includes('minute_worker_start'), result.events.join(', '));
  assert.ok(!result.events.includes('generation_worker_start'), result.events.join(', '));
  assert.ok(!result.events.includes('http_close'), result.events.join(', '));
});

test('main composition closes initialized resources when app construction fails', () => {
  const result = runMainProbe('app_construction');
  assert.equal(result.status, 1);
  has(result.events, 'startup_failed');
  before(result.events, 'metrics_refresh_stop', 'observability_shutdown');
  before(result.events, 'observability_shutdown', 'database_close');
  assert.ok(!result.events.includes('api_started'), result.events.join(', '));
  assert.ok(!result.events.includes('minute_worker_start'), result.events.join(', '));
  assert.ok(!result.events.includes('generation_worker_start'), result.events.join(', '));
  assert.ok(!result.events.includes('http_close'), result.events.join(', '));
});

test('main composition closes timers, app, telemetry, and database when listening fails', () => {
  const result = runMainProbe('http_listen');
  assert.equal(result.status, 1);
  has(result.events, 'startup_failed');
  has(result.events, 'timers_clear');
  before(result.events, 'metrics_refresh_stop', 'http_close');
  before(result.events, 'http_close', 'observability_shutdown');
  before(result.events, 'observability_shutdown', 'database_close');
  assert.ok(!result.events.includes('api_started'), result.events.join(', '));
  assert.ok(!result.events.includes('minute_worker_start'), result.events.join(', '));
  assert.ok(!result.events.includes('generation_worker_start'), result.events.join(', '));
});

test('main composition registers signal handlers and shuts resources down on SIGTERM', () => {
  const result = runMainProbe(undefined, 'SIGTERM');
  assert.equal(result.status, 0);
  has(result.events, 'signal_handler_registered:SIGTERM');
  has(result.events, 'signal_handler_registered:SIGINT');
  before(result.events, 'api_started', 'timers_clear');
  before(result.events, 'timers_clear', 'http_close');
  before(result.events, 'http_close', 'observability_shutdown');
  before(result.events, 'observability_shutdown', 'database_close');
});
