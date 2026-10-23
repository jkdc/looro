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
