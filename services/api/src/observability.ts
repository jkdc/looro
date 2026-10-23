// OpenTelemetry wiring: explicit tracer/meter providers plus OTLP/HTTP
// exporters. No NodeSDK on purpose — the API owns exactly what it installs
// and can shut every piece down deterministically.
import { metrics, trace } from '@opentelemetry/api';
import { OTLPMetricExporter } from '@opentelemetry/exporter-metrics-otlp-http';
import { OTLPTraceExporter } from '@opentelemetry/exporter-trace-otlp-http';
import { resourceFromAttributes } from '@opentelemetry/resources';
import { MeterProvider, PeriodicExportingMetricReader } from '@opentelemetry/sdk-metrics';
import { BatchSpanProcessor, TracerProvider } from '@opentelemetry/sdk-trace';
import { ATTR_SERVICE_NAME } from '@opentelemetry/semantic-conventions';
import type { Database } from './db.js';
import { initDomainMetrics, paliqueMetrics } from './metrics.js';

let shutdown: (() => Promise<void>) | undefined;

// No-op unless OTEL_ENABLED=true (tests, smoke stack and CI never start the
// telemetry stack). Idempotent: a second call keeps the first providers.
export function initObservability(env: NodeJS.ProcessEnv = process.env): void {
  if (env.OTEL_ENABLED !== 'true' || shutdown) return;
  const endpoint = (env.OTEL_EXPORTER_OTLP_ENDPOINT ?? 'http://otel-collector:4318').replace(/\/$/, '');
  const resource = resourceFromAttributes({ [ATTR_SERVICE_NAME]: env.OTEL_SERVICE_NAME ?? 'palique-api' });
  const tracerProvider = new TracerProvider({
    resource,
    spanProcessors: [new BatchSpanProcessor({
      exporter: new OTLPTraceExporter({ url: `${endpoint}/v1/traces` }),
    })],
  });
  // sdk-trace@3.x dropped NodeTracerProvider.register(); global registration
  // goes through the API package instead.
  trace.setGlobalTracerProvider(tracerProvider);
  const meterProvider = new MeterProvider({
    resource,
    readers: [new PeriodicExportingMetricReader({
      exporter: new OTLPMetricExporter({ url: `${endpoint}/v1/metrics` }),
      exportIntervalMillis: 15_000,
    })],
  });
  metrics.setGlobalMeterProvider(meterProvider);
  initDomainMetrics(metrics.getMeter('palique-api'));
  shutdown = () => Promise.allSettled([tracerProvider.shutdown(), meterProvider.shutdown()]).then(() => {});
}

export function shutdownObservability(): Promise<void> { return shutdown?.() ?? Promise.resolve(); }

/** Seeds the last-success gauge from the database so the staleness alert has a
    series on the first scrape after a restart (D8): a gauge born empty cannot
    alert, which is the blind spot the metric exists to close. */
export async function initGenerationMetrics(db: Database): Promise<void> {
  try {
    const { rows } = await db.query(
      `SELECT max(COALESCE(finished_at, updated_at)) AS at
         FROM level_test_generation_jobs WHERE state = 'finished'`) as { rows: { at: Date | null }[] };
    const at = rows[0]?.at;
    if (at) paliqueMetrics.generationLastSuccess.record(new Date(at).getTime() / 1000);
  } catch { /* Telemetry must never block boot. */ }
}

// Gauges are recomputed at every export from live pool counters. Registered
// AFTER initDomainMetrics so the callback binds to the real instruments.
export function addDatabasePoolObservability(db: Database): void {
  paliqueMetrics.dbPoolMaxConnections.record(db.options.max ?? 10);
  paliqueMetrics.dbPoolConnections.addCallback(observer => {
    observer.observe(db.idleCount, { state: 'idle' });
    observer.observe(db.totalCount - db.idleCount, { state: 'in_use' });
    observer.observe(db.waitingCount, { state: 'waiting' });
  });
}
