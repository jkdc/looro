import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { createApiLifecycle } from '../src/api-lifecycle.js';

test('runtime starts workers after listen and closes resources once in order', async () => {
  const events: string[] = [];
  const lifecycle = createApiLifecycle({
    listen: async () => { events.push('listen'); },
    startWorkers: () => { events.push('workers'); },
    shutdownSteps: [() => { events.push('timers'); }, async () => { events.push('scheduler'); },
      async () => { events.push('http'); }, async () => { events.push('providers'); },
      async () => { events.push('telemetry'); }, async () => { events.push('database'); }],
  });
  await lifecycle.start();
  assert.deepEqual(events, ['listen', 'workers']);
  await Promise.all([lifecycle.shutdown(), lifecycle.shutdown()]);
  await lifecycle.shutdown();
  assert.deepEqual(events, ['listen', 'workers', 'timers', 'scheduler', 'http', 'providers', 'telemetry', 'database']);
});

test('runtime closes already-open resources when listening fails and preserves the startup error', async () => {
  const events: string[] = [];
  const failure = new Error('listen failed');
  const lifecycle = createApiLifecycle({
    listen: async () => { events.push('listen'); throw failure; },
    startWorkers: () => { events.push('workers'); },
    shutdownSteps: [() => { events.push('timers'); }, async () => { events.push('database'); }],
  });
  await assert.rejects(lifecycle.start(), error => error === failure);
  assert.deepEqual(events, ['listen', 'timers', 'database']);
});

test('runtime shutdown waits for pending work before closing later resources', async () => {
  const events: string[] = [];
  let releasePending!: () => void;
  const pending = new Promise<void>(resolve => { releasePending = resolve; });
  const lifecycle = createApiLifecycle({
    listen: async () => {}, startWorkers: () => {},
    shutdownSteps: [() => { events.push('await-pending'); return pending; }, () => { events.push('close-database'); }],
  });
  const closing = lifecycle.shutdown();
  await Promise.resolve();
  assert.deepEqual(events, ['await-pending']);
  releasePending();
  await closing;
  assert.deepEqual(events, ['await-pending', 'close-database']);
});

test('runtime attempts later resource cleanup after an earlier close step fails', async () => {
  const events: string[] = [];
  const lifecycle = createApiLifecycle({
    listen: async () => {}, startWorkers: () => {},
    shutdownSteps: [() => { events.push('stop-scheduler'); throw new Error('scheduler close failed'); },
      () => { events.push('close-http'); }, () => { events.push('close-database'); }],
  });
  await assert.rejects(lifecycle.shutdown(), AggregateError);
  assert.deepEqual(events, ['stop-scheduler', 'close-http', 'close-database']);
});

test('importing the API process entry does not start or terminate the process', () => {
  const compiledMain = new URL('../src/main.js', import.meta.url);
  const isCompiled = existsSync(fileURLToPath(compiledMain));
  const mainURL = isCompiled ? compiledMain.href : new URL('../src/main.ts', import.meta.url).href;
  const loaderArgs = isCompiled ? [] : ['--import', 'tsx'];
  const result = spawnSync(process.execPath, [...loaderArgs, '--input-type=module', '-e', `await import(${JSON.stringify(mainURL)})`], {
    cwd: process.cwd(), encoding: 'utf8', timeout: 5_000,
    env: { ...process.env, DATABASE_URL: '' },
  });
  assert.equal(result.error, undefined, result.error?.message);
  assert.equal(result.status, 0, result.stderr);
  assert.equal(result.stderr, '');
});
