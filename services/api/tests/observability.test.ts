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

test('log fields cannot replace envelope fields or emit sensitive names', () => {
  const original = console.info;
  const lines: string[] = [];
  console.info = (value: string) => { lines.push(value); };
  try {
    log.info('safe_event', {
      timestamp: 'forged', level: 'fatal', event: 'forged', route: '/safe', status: 200,
      path: '/users/pii-account-123', query: 'token=private-token-456', authorization: 'Bearer private-token-456',
      body: 'private-body-789', error: 'private-error-012', api_key: 'private-key-789',
      account_id: 'private-account-321', email: 'private@example.invalid', error_code: 'service_unavailable',
    });
    const line = JSON.parse(lines[0]!) as Record<string, unknown>;
    assert.equal(line.event, 'safe_event');
    assert.equal(line.level, 'info');
    assert.notEqual(line.timestamp, 'forged');
    assert.equal(line.route, '/safe');
    assert.equal(line.status, 200);
    assert.equal(line.error_code, 'service_unavailable');
    assert.doesNotMatch(lines[0]!, /pii-account-123|private-token-456|private-body-789|private-error-012|private-key-789|private-account-321|private@example/);
    for (const forbidden of ['path', 'query', 'authorization', 'body', 'error', 'api_key', 'account_id', 'email']) assert.equal(forbidden in line, false);
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

async function collectHistogramCount(reader: InMemoryMetricReader, name: string): Promise<number> {
  const { resourceMetrics } = await reader.collect();
  for (const scope of resourceMetrics.scopeMetrics)
    for (const metric of scope.metrics) if (metric.descriptor.name === name)
      return (metric.dataPoints ?? []).reduce((count, point) =>
        count + Number((point.value as { count?: number }).count ?? 0), 0);
  return 0;
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
import { addDatabasePoolObservability, initObservability, observabilityResourceAttributes, shutdownObservability, startGenerationMetricsRefresh } from '../src/observability.js';

test('API and generation CLI have distinct stable OTel service instance identities', () => {
  assert.deepEqual(observabilityResourceAttributes({}), {
    'service.name': 'palique-api',
    'service.instance.id': 'api',
  });
  assert.deepEqual(observabilityResourceAttributes({ OTEL_SERVICE_INSTANCE_ID: 'leveltest-generate' }), {
    'service.name': 'palique-api',
    'service.instance.id': 'leveltest-generate',
  });
});

test('generation failure timestamps refresh from durable jobs only while telemetry is enabled', async () => {
  let calls = 0;
  const db = { query: async () => { calls += 1; return { rows: [] }; } } as never;
  const stop = startGenerationMetricsRefresh(db, 5, true);
  await new Promise(resolve => setTimeout(resolve, 18));
  stop();
  const observedCalls = calls;
  await new Promise(resolve => setTimeout(resolve, 12));
  assert.ok(observedCalls >= 2);
  assert.equal(calls, observedCalls);

  calls = 0;
  const disabledStop = startGenerationMetricsRefresh(db, 5, false);
  await new Promise(resolve => setTimeout(resolve, 12));
  disabledStop();
  assert.equal(calls, 0);
});

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
import { context, trace } from '@opentelemetry/api';
import { AsyncLocalStorageContextManager } from '../src/request-context.js';

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
    assert.equal(response.headers['x-request-id'], response.json().error.requestId);
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

test('direct provider 502 keeps its JSON contract and adds safe request correlation', async () => {
  const app = createApp({ db: {} as never, auth: {} });
  app.get('/__provider-failure', async (_request, reply) => reply.code(502).send({ ok: false, error: 'llm_unreachable' }));
  const original = console.error;
  const lines: string[] = [];
  console.error = value => { lines.push(value); };
  try {
    const response = await app.inject({ method: 'GET', url: '/__provider-failure' });
    assert.equal(response.statusCode, 502);
    assert.deepEqual(response.json(), { ok: false, error: 'llm_unreachable' });
    assert.ok(typeof response.headers['x-request-id'] === 'string' && response.headers['x-request-id'].length > 0);
    const failure = lines.map(line => JSON.parse(line) as Record<string, unknown>).find(entry => entry.event === 'request_failed');
    assert.ok(failure);
    assert.equal(failure.request_id, response.headers['x-request-id']);
    assert.equal(failure.error_code, 'server_error');
  } finally { console.error = original; await app.close(); }
});

test('request telemetry omits sensitive path data and isolates active spans across requests', async () => {
  const spans: Array<{ name: string; options: Record<string, unknown>; attributes: Record<string, unknown>; status?: unknown }> = [];
  trace.setGlobalTracerProvider({ getTracer: () => ({ startSpan: (name: string, options: Record<string, unknown>) => {
    const captured = { name, options, attributes: { ...(options.attributes as Record<string, unknown>) }, status: undefined as unknown };
    spans.push(captured);
    return { setAttribute: (key: string, value: unknown) => { captured.attributes[key] = value; },
      setStatus: (status: { code: number }) => { captured.status = status; }, spanContext: () => ({ traceId: 'trace-id', spanId: `span-${spans.indexOf(captured)}` }), end: () => {},
      addEvent: () => {}, recordException: () => {} };
  } }) } as never);
  context.setGlobalContextManager(new AsyncLocalStorageContextManager());
  const app = createApp({ db: {} as never, auth: {} });
  app.get('/__pii/:account', async () => { throw new Error('private-error-012'); });
  app.get('/__ctx/:id', async request => ({ active: trace.getActiveSpan()?.spanContext().spanId, id: request.params && (request.params as { id: string }).id }));
  const oldInfo = console.info, oldError = console.error;
  const lines: string[] = [];
  console.info = value => { lines.push(value); }; console.error = value => { lines.push(value); };
  try {
    await app.inject({ method: 'GET', url: '/__pii/pii-account-123?query=private-query-456',
      headers: { authorization: 'private-token-789', 'x-marker': 'private-header-012' }, payload: { marker: 'private-body-345' } });
    await app.inject({ method: 'GET', url: '/__unmatched/pii-unmatched-678?query=private-query-456' });
    const output = lines.join('\n');
    for (const marker of ['pii-account-123', 'pii-unmatched-678', 'private-query-456', 'private-token-789',
      'private-header-012', 'private-body-345', 'private-error-012']) assert.equal(output.includes(marker), false, marker);
    assert.equal(spans.length, 2);
    for (const span of spans) {
      assert.equal('url.path' in span.attributes, false);
      assert.equal(span.options.kind, 1); // SpanKind.SERVER
    }
    assert.equal(spans[0]!.attributes['http.route'], '/__pii/:account');
    assert.equal(spans[0]!.status && (spans[0]!.status as { code: number }).code, 2); // SpanStatusCode.ERROR
    assert.equal(spans[1]!.attributes['http.route'], 'unmatched');
    const [first, second] = await Promise.all([
      app.inject({ method: 'GET', url: '/__ctx/one' }), app.inject({ method: 'GET', url: '/__ctx/two' }),
    ]);
    assert.deepEqual(first.json(), { active: 'span-2', id: 'one' });
    assert.deepEqual(second.json(), { active: 'span-3', id: 'two' });
  } finally { console.info = oldInfo; console.error = oldError; await app.close(); }
});

//
// Section 5: LLM chat completion instrumentation (src/llm-config.ts)
// Static import per file convention (see the Section 2 note on live bindings).
import { chatCompletion, LlmTransportError, type ResolvedLlmConfig } from '../src/llm-config.js';

const llmConfig: ResolvedLlmConfig = { provider: 'glm', baseUrl: 'http://llm.test', model: 'm', apiKey: 'k' };

test('chatCompletion records ok outcome with tokens and duration', async () => {
  const reader = new InMemoryMetricReader();
  const provider = new MeterProvider({ readers: [reader] });
  initDomainMetrics(provider.getMeter('test'));
  const reply = await chatCompletion(llmConfig,
    [{ role: 'user', content: 'hi' }],
    async () => new Response(JSON.stringify({ choices: [{ message: { content: 'ok' } }],
      usage: { prompt_tokens: 10, completion_tokens: 5 } }), { status: 200 }));
  assert.equal(reply.tokensIn, 10);
  const requests = await collectSum(reader, 'palique.llm.requests');
  assert.equal(requests.get('provider=glm,status=ok'), 1);
  const tokens = await collectSum(reader, 'palique.llm.tokens');
  assert.equal(tokens.get('direction=input,provider=glm'), 10);
  assert.equal(tokens.get('direction=output,provider=glm'), 5);
  assert.equal(await collectHistogramCount(reader, 'palique.llm.request.duration'), 1);
});

test('chatCompletion records failed outcome with the transport error code', async () => {
  const reader = new InMemoryMetricReader();
  const provider = new MeterProvider({ readers: [reader] });
  initDomainMetrics(provider.getMeter('test'));
  // The re-thrown error must stay the original LlmTransportError (no-retry
  // contract): callers match on it to classify outcomes as unknown.
  await assert.rejects(
    chatCompletion(llmConfig, [{ role: 'user', content: 'hi' }], async () => { throw new Error('socket down'); }),
    (error: unknown) => error instanceof LlmTransportError && error.code === 'llm_outcome_unknown');
  const requests = await collectSum(reader, 'palique.llm.requests');
  assert.equal(requests.get('error_code=llm_outcome_unknown,provider=glm,status=failed'), 1);
  assert.equal(await collectHistogramCount(reader, 'palique.llm.request.duration'), 1);
});

test('chatCompletion records one duration for HTTP, JSON, empty-reply, and cancellation failures', async () => {
  const failures: Array<{ name: string; fetchFn: typeof fetch; signal?: AbortSignal }> = [
    { name: 'http', fetchFn: async () => new Response('', { status: 503 }) },
    { name: 'json', fetchFn: async () => new Response('{', { status: 200 }) },
    { name: 'empty', fetchFn: async () => new Response(JSON.stringify({ choices: [{ message: { content: '  ' } }] }), { status: 200 }) },
  ];
  const controller = new AbortController();
  controller.abort();
  failures.push({ name: 'cancelled', fetchFn: async () => { throw new Error('cancelled'); }, signal: controller.signal });

  for (const failure of failures) {
    const reader = new InMemoryMetricReader();
    const provider = new MeterProvider({ readers: [reader] });
    initDomainMetrics(provider.getMeter(`test-${failure.name}`));
    await assert.rejects(chatCompletion(llmConfig, [{ role: 'user', content: 'hi' }], failure.fetchFn, failure.signal));
    const requests = await collectSum(reader, 'palique.llm.requests');
    assert.equal([...requests.values()].reduce((sum, value) => sum + value, 0), 1, failure.name);
    assert.equal(await collectHistogramCount(reader, 'palique.llm.request.duration'), 1, failure.name);
    assert.equal((await collectSum(reader, 'palique.llm.tokens')).size, 0, failure.name);
  }
});

test('chatCompletion does not invent token measurements when provider usage is absent', async () => {
  const reader = new InMemoryMetricReader();
  const provider = new MeterProvider({ readers: [reader] });
  initDomainMetrics(provider.getMeter('test'));
  const reply = await chatCompletion(llmConfig, [{ role: 'user', content: 'hi' }], async () =>
    new Response(JSON.stringify({ choices: [{ message: { content: 'ok' } }] }), { status: 200 }));
  assert.equal(reply.tokensIn, undefined);
  assert.equal(reply.tokensOut, undefined);
  assert.equal((await collectSum(reader, 'palique.llm.tokens')).size, 0);
});

//
// Section 6: generation job wrapper instrumentation (src/level-tests-generation-jobs.ts)
// Idempotent no-op outcomes ('busy', 'exists') and early state checks run
// against a scripted PoolClient fake — no database needed for the mapping.
import { runLevelTestGenerationJob } from '../src/level-tests-generation-jobs.js';
import { type GenerateOptions } from '../src/level-tests-llm.js';
import type { Database } from '../src/db.js';

/** Pool fake whose clients replay scripted query results in order. */
const fakeDb = (results: { rows: unknown[] }[]): Database => {
  let next = 0;
  return {
    connect: async () => ({
      query: async () => results[next++] ?? { rows: [] },
      release: () => {},
    }),
  } as unknown as Database;
};

const jobOptions: GenerateOptions = { languageID: 'en', level: 'A1', weekIndex: 0, origin: 'automatic_worker' };

test('generation job wrapper counts failed and unknown outcomes', async () => {
  const reader = new InMemoryMetricReader();
  const provider = new MeterProvider({ readers: [reader] });
  initDomainMetrics(provider.getMeter('test'));

  // Automatic attempts past the cap return 'failed' without generating.
  const failed = await runLevelTestGenerationJob(
    fakeDb([{ rows: [{ locked: true }] }, { rows: [{ id: 'j1', state: 'failed', attempts: 2 }] }]), {}, jobOptions);
  assert.equal(failed.status, 'failed');
  // A prior 'unknown' run short-circuits to reconciliation_required.
  const unknown = await runLevelTestGenerationJob(
    fakeDb([{ rows: [{ locked: true }] }, { rows: [{ id: 'j2', state: 'unknown', attempts: 1 }] }]), {}, jobOptions);
  assert.equal(unknown.status, 'unknown');

  const jobs = await collectSum(reader, 'palique.leveltest.generation.jobs');
  assert.equal(jobs.get('language=en,level=A1,status=failed'), 1);
  assert.equal(jobs.get('language=en,level=A1,status=unknown'), 1);
  // Last-success only ever fires on finished outcomes.
  const lastSuccess = await collectSum(reader, 'palique.leveltest.generation.last_success_unix_seconds');
  assert.equal(lastSuccess.size, 0);
  assert.equal(await collectHistogramCount(reader, 'palique.leveltest.generation.job.duration'), 2);
});

test('generation job wrapper records thrown execution failures and preserves the original error', async () => {
  const reader = new InMemoryMetricReader();
  const provider = new MeterProvider({ readers: [reader] });
  initDomainMetrics(provider.getMeter('test'));
  const expected = new Error('database unavailable');
  const db = { connect: async () => { throw expected; } } as unknown as Database;

  await assert.rejects(runLevelTestGenerationJob(db, {}, jobOptions), error => error === expected);

  const jobs = await collectSum(reader, 'palique.leveltest.generation.jobs');
  assert.equal(jobs.get('language=en,level=A1,status=failed'), 1);
  assert.equal(await collectHistogramCount(reader, 'palique.leveltest.generation.job.duration'), 1);
  assert.ok([...await collectSum(reader, 'palique.leveltest.generation.last_failure_unix_seconds')].length > 0);
});

test('generation job wrapper records query and finalization failures without replacing errors', async () => {
  const reader = new InMemoryMetricReader();
  const provider = new MeterProvider({ readers: [reader] });
  initDomainMetrics(provider.getMeter('test'));
  const lockError = new Error('lock query failed');
  const lockFailureDb = { connect: async () => ({ query: async () => { throw lockError; }, release: () => {} }) } as unknown as Database;
  await assert.rejects(runLevelTestGenerationJob(lockFailureDb, {}, jobOptions), error => error === lockError);

  const finalizeError = new Error('job finalization failed');
  const client = {
    query: async (sql: string) => {
      if (sql.includes('pg_try_advisory_lock')) return { rows: [{ locked: true }] };
      if (sql.includes('UPDATE level_test_generation_jobs')) throw finalizeError;
      return { rows: [] };
    },
    release: () => {},
  };
  const finalizeFailureDb = { connect: async () => client, query: async () => ({ rows: [] }) } as unknown as Database;
  await assert.rejects(runLevelTestGenerationJob(finalizeFailureDb, {}, jobOptions), error => error === finalizeError);

  const jobs = await collectSum(reader, 'palique.leveltest.generation.jobs');
  assert.equal(jobs.get('language=en,level=A1,status=failed'), 2);
  assert.equal(await collectHistogramCount(reader, 'palique.leveltest.generation.job.duration'), 2);
});

test('generation job wrapper passes busy and exists through without recording metrics', async () => {
  const reader = new InMemoryMetricReader();
  const provider = new MeterProvider({ readers: [reader] });
  initDomainMetrics(provider.getMeter('test'));

  const busy = await runLevelTestGenerationJob(fakeDb([{ rows: [{ locked: false }] }]), {}, jobOptions);
  assert.equal(busy.status, 'busy');
  const exists = await runLevelTestGenerationJob(
    fakeDb([{ rows: [{ locked: true }] }, { rows: [] }, { rows: [{ status: 'finished', variants: [] }] }]), {}, jobOptions);
  assert.equal(exists.status, 'exists');

  assert.equal((await collectSum(reader, 'palique.leveltest.generation.jobs')).size, 0);
  assert.equal((await collectSum(reader, 'palique.leveltest.generation.last_success_unix_seconds')).size, 0);
  assert.equal(await collectHistogramCount(reader, 'palique.leveltest.generation.job.duration'), 0);
});

//
// Section 7: bounded boot seeding of the durable success/failure timestamps
// (src/observability.ts). Last failure powers the recent-failure alert after a
// restart; last success remains a dashboard signal, not a missed-run alert.
import { initGenerationMetrics } from '../src/observability.js';

test('last-success and last-failure gauges are seeded from the database at boot', async () => {
  const reader = new InMemoryMetricReader();
  const provider = new MeterProvider({ readers: [reader] });
  initDomainMetrics(provider.getMeter('test'));
  const finishedAt = new Date('2026-10-20T04:00:00Z');
  const failedAt = new Date('2026-10-21T04:00:00Z');
  await initGenerationMetrics({ query: async () => ({ rows: [{ success_at: finishedAt, failure_at: failedAt }] }) } as unknown as Database, { enabled: true });
  const seeded = await collectSum(reader, 'palique.leveltest.generation.last_success_unix_seconds');
  assert.equal(seeded.get(''), finishedAt.getTime() / 1000);
  const failure = await collectSum(reader, 'palique.leveltest.generation.last_failure_unix_seconds');
  assert.equal(failure.get(''), failedAt.getTime() / 1000);
});

test('last-success gauge stays absent when nothing has ever finished', async () => {
  const reader = new InMemoryMetricReader();
  const provider = new MeterProvider({ readers: [reader] });
  initDomainMetrics(provider.getMeter('test'));
  await initGenerationMetrics({ query: async () => ({ rows: [{ success_at: null, failure_at: null }] }) } as unknown as Database, { enabled: true });
  assert.equal((await collectSum(reader, 'palique.leveltest.generation.last_success_unix_seconds')).size, 0);
  assert.equal((await collectSum(reader, 'palique.leveltest.generation.last_failure_unix_seconds')).size, 0);
});

test('telemetry-disabled boot seeding is a true no-op', async () => {
  const reader = new InMemoryMetricReader();
  const provider = new MeterProvider({ readers: [reader] });
  initDomainMetrics(provider.getMeter('test'));
  let queries = 0;
  await initGenerationMetrics({ query: async () => { queries += 1; throw new Error('db down'); } } as unknown as Database, { enabled: false });
  assert.equal(queries, 0);
  assert.equal((await collectSum(reader, 'palique.leveltest.generation.last_success_unix_seconds')).size, 0);
});

test('telemetry-enabled boot seeding absorbs query failure and bounds a hanging query', async () => {
  const reader = new InMemoryMetricReader();
  const provider = new MeterProvider({ readers: [reader] });
  initDomainMetrics(provider.getMeter('test'));
  await initGenerationMetrics({ query: async () => { throw new Error('database unavailable'); } } as unknown as Database,
    { enabled: true, timeoutMs: 20 });
  let queries = 0;
  await initGenerationMetrics({ query: async () => {
    queries += 1;
    return new Promise(() => {});
  } } as unknown as Database, { enabled: true, timeoutMs: 20 });
  assert.equal(queries, 1);
  assert.equal((await collectSum(reader, 'palique.leveltest.generation.last_failure_unix_seconds')).size, 0);
});
