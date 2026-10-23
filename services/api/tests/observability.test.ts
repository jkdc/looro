// Observability tests. Sections are appended by successive observability tasks;
// keep one clearly marked section per module.
//
// Section 1: structured logger (src/log.ts)
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { log } from '../src/log.js';

test('log emits one JSON line per call with allow-listed scalar fields only', () => {
  const original = console.info;
  const lines: string[] = [];
  console.info = (value: string) => { lines.push(value); };
  try {
    log.info('http_request', { request_id: 'abc', status: 200, duration_ms: 12.4, optional: undefined, nothing: null });
    assert.equal(lines.length, 1);
    const parsed = JSON.parse(lines[0]!) as Record<string, unknown>;
    assert.equal(parsed['event'], 'http_request');
    assert.equal(parsed['level'], 'info');
    assert.equal(parsed['request_id'], 'abc');
    assert.equal(parsed['status'], 200);
    assert.equal(parsed['duration_ms'], 12.4); // numbers preserved, never stringified
    assert.equal('optional' in parsed, false); // undefined dropped
    assert.equal(parsed['nothing'], null);
    assert.ok(typeof parsed['timestamp'] === 'string' && !Number.isNaN(Date.parse(parsed['timestamp'] as string)));
  } finally { console.info = original; }
});

//
// Section 2: domain metric registry (src/metrics.ts)
// Static import on purpose: paliqueMetrics is a live binding (D3), and only a
// real import binding reflects initDomainMetrics' rebinding. Destructuring a
// dynamic import's namespace would snapshot the pre-init no-op instruments.
import { MeterProvider, MetricReader } from '@opentelemetry/sdk-metrics';
import { initDomainMetrics, paliqueMetrics } from '../src/metrics.js';

// sdk-metrics@3.x dropped the InMemoryMetricReader helper; this pull-style
// reader keeps the same collect()-to-inspect semantics for tests.
class InMemoryMetricReader extends MetricReader {
  protected onShutdown(): Promise<void> { return Promise.resolve(); }
  protected onForceFlush(): Promise<void> { return Promise.resolve(); }
}

async function collectSum(reader: InMemoryMetricReader, name: string): Promise<Map<string, number>> {
  const { resourceMetrics } = await reader.collect();
  const out = new Map<string, number>();
  for (const scope of resourceMetrics.scopeMetrics)
    for (const metric of scope.metrics) if (metric.descriptor.name === name)
      for (const point of metric.dataPoints ?? []) {
        const labels = Object.entries(point.attributes).map(([k, v]) => `${k}=${String(v)}`).sort().join(',');
        out.set(labels, (out.get(labels) ?? 0) + Number(point.value));
      }
  return out;
}

test('domain metrics record through the instruments with contract attributes', async () => {
  const reader = new InMemoryMetricReader();
  const provider = new MeterProvider({ readers: [reader] });
  initDomainMetrics(provider.getMeter('test'));
  paliqueMetrics.requestsRejected.add(1, { reason: 'rate_limit' });
  paliqueMetrics.generationJobs.add(1, { language: 'en', level: 'A1', status: 'finished' });
  paliqueMetrics.generationLastSuccess.record(1_700_000_000);
  paliqueMetrics.llmTokens.add(12, { provider: 'glm', direction: 'output' });
  const rejected = await collectSum(reader, 'palique.requests.rejected');
  assert.equal(rejected.get('reason=rate_limit'), 1);
  const jobs = await collectSum(reader, 'palique.leveltest.generation.jobs');
  assert.equal(jobs.get('language=en,level=A1,status=finished'), 1);
  const tokens = await collectSum(reader, 'palique.llm.tokens');
  assert.equal(tokens.get('direction=output,provider=glm'), 12);
});

//
// Section 3: OTel init/shutdown and pool gauges (src/observability.ts)
// Static import per file convention (see the Section 2 note on live bindings).
import { addDatabasePoolObservability, initObservability, shutdownObservability } from '../src/observability.js';

// No-op contract: tests, the smoke stack and CI never start the telemetry
// stack, so init must install nothing until OTEL_ENABLED is exactly 'true'.
test('observability stays no-op unless OTEL_ENABLED is true', async () => {
  initObservability({});                        // disabled
  initObservability({ OTEL_ENABLED: 'false' }); // disabled
  await shutdownObservability();                // resolves without providers
});

test('pool observability publishes max and per-state connections', async () => {
  const reader = new InMemoryMetricReader();
  const provider = new MeterProvider({ readers: [reader] });
  initDomainMetrics(provider.getMeter('test'));
  const fakePool = { options: { max: 10 }, idleCount: 3, totalCount: 7, waitingCount: 1 } as never;
  addDatabasePoolObservability(fakePool);
  const pool = await collectSum(reader, 'palique.db.pool.connections');
  assert.equal(pool.get('state=idle'), 3);
  assert.equal(pool.get('state=in_use'), 4); // totalCount - idleCount
  assert.equal(pool.get('state=waiting'), 1);
  const max = await collectSum(reader, 'palique.db.pool.max_connections');
  assert.equal([...max.values()].reduce((a, b) => a + b, 0), 10);
});

//
// Section 4: RED HTTP, per-request logs and requestId on 5xx (src/app.ts)
// Integration harness identical to tests/admin-usage.test.ts: a real pool from
// TEST_DATABASE_URL, skipped without it so unit-only runs stay green.
import { before, after } from 'node:test';
import { connectDatabase } from '../src/db.js';
import { migrate } from '../src/migrate.js';
import { createApp } from '../src/app.js';

const url = process.env.TEST_DATABASE_URL;
if (url && !new URL(url).pathname.endsWith('_test')) throw new Error('Use an isolated database ending in _test.');
const db = url ? connectDatabase(url) : null;
before(async () => { if (db) await migrate(db); });
after(async () => { if (db) await db.end(); });
const integration = (name: string, fn: () => Promise<void>) => test(name, { skip: !db && 'Set TEST_DATABASE_URL for PostgreSQL tests.' }, fn);

integration('health request emits RED data point and structured log line', async () => {
  const reader = new InMemoryMetricReader();
  const provider = new MeterProvider({ readers: [reader] });
  initDomainMetrics(provider.getMeter('test'));
  const app = createApp({ db: db!, auth: {} });
  const original = console.info;
  const lines: string[] = [];
  console.info = (value: string) => { lines.push(value); };
  try {
    const response = await app.inject({ method: 'GET', url: '/healthz' });
    assert.equal(response.statusCode, 200);
    // collectSum keys are "k=v" label strings; histogram point values are not
    // asserted (the helper sums them to NaN for histograms).
    const durations = await collectSum(reader, 'http.server.request.duration');
    assert.ok([...durations.keys()].some(labels => labels.includes('http.route=/healthz') && labels.includes('http.response.status_code=200')));
    const logged = lines.map(line => JSON.parse(line) as Record<string, unknown>).find(entry => entry['event'] === 'http_request');
    assert.ok(logged && logged['route'] === '/healthz' && typeof logged['request_id'] === 'string');
  } finally { console.info = original; await app.close(); }
});

integration('unexpected 5xx responses include requestId and never internals', async () => {
  const app = createApp({ db: db!, auth: {} });
  app.route({ method: 'GET', url: '/__boom', handler: async () => { throw new Error('secret-internal-detail'); } });
  const original = console.error;
  const lines: string[] = [];
  console.error = (value: string) => { lines.push(value); };
  try {
    const response = await app.inject({ method: 'GET', url: '/__boom' });
    assert.equal(response.statusCode, 500);
    const body = response.json() as { error: { code: string; requestId?: string } };
    assert.equal(body.error.code, 'service_unavailable');
    assert.ok(typeof body.error.requestId === 'string' && body.error.requestId.length > 0);
    // The 4xx shape stays exactly { error: { code } } — no requestId leaks there.
    const invalid = await app.inject({ method: 'GET', url: '/v1/level-tests/current' });
    assert.equal(invalid.statusCode, 400);
    assert.deepEqual(invalid.json(), { error: { code: 'invalid_request' } });
    // No stack frames, no original message, exactly two error fields.
    assert.ok(!response.body.includes('secret-internal-detail'));
    assert.ok(!response.body.includes(' at '));
    assert.deepEqual(Object.keys(body.error).sort(), ['code', 'requestId']);
    const logged = lines.map(line => JSON.parse(line) as Record<string, unknown>).find(entry => entry['event'] === 'request_failed');
    assert.ok(logged, 'a request_failed log line must exist');
    assert.equal(logged['request_id'], body.error.requestId); // body and log correlate
    assert.equal(logged['error_code'], 'service_unavailable');
    assert.equal(logged['status'], 500);
    assert.equal(typeof logged['route'], 'string');
  } finally { console.error = original; await app.close(); }
});
