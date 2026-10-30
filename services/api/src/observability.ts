// OpenTelemetry wiring: explicit tracer/meter providers plus OTLP/HTTP
// exporters. No NodeSDK on purpose — the API owns exactly what it installs
// and can shut every piece down deterministically.
import { randomUUID } from 'node:crypto';
import { monitorEventLoopDelay, performance } from 'node:perf_hooks';
import { context, metrics, trace } from '@opentelemetry/api';
import { OTLPMetricExporter } from '@opentelemetry/exporter-metrics-otlp-http';
import { OTLPTraceExporter } from '@opentelemetry/exporter-trace-otlp-http';
import { resourceFromAttributes } from '@opentelemetry/resources';
import { MeterProvider, PeriodicExportingMetricReader } from '@opentelemetry/sdk-metrics';
import { BatchSpanProcessor, TracerProvider } from '@opentelemetry/sdk-trace';
import { ATTR_SERVICE_INSTANCE_ID, ATTR_SERVICE_NAME } from '@opentelemetry/semantic-conventions';
import type { Database } from './db.js';
import { initDomainMetrics, paliqueMetrics } from './metrics.js';
import { AsyncLocalStorageContextManager } from './request-context.js';

const processInstanceID = randomUUID();
let shutdown: (() => Promise<void>) | undefined;
const requestContextManager = new AsyncLocalStorageContextManager();
let contextManagerInstalled = false;

export function observabilityResourceAttributes(env: NodeJS.ProcessEnv): Record<string, string> {
  return {
    [ATTR_SERVICE_NAME]: env.OTEL_SERVICE_NAME ?? 'palique-api',
    [ATTR_SERVICE_INSTANCE_ID]: env.OTEL_SERVICE_INSTANCE_ID ?? processInstanceID,
  };
}

// No-op unless OTEL_ENABLED=true (tests, smoke stack and CI never start the
// telemetry stack). Idempotent: a second call keeps the first providers.
export function initObservability(env: NodeJS.ProcessEnv = process.env): void {
  if (env.OTEL_ENABLED !== 'true' || shutdown) return;
  if (!contextManagerInstalled) {
    context.setGlobalContextManager(requestContextManager);
    contextManagerInstalled = true;
  }
  requestContextManager.enable();
  const endpoint = (env.OTEL_EXPORTER_OTLP_ENDPOINT ?? 'http://otel-collector:4318').replace(/\/$/, '');
  const resource = resourceFromAttributes(observabilityResourceAttributes(env));
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
  const stopProcessMetrics = startProcessMetrics();
  shutdown = () => {
    stopProcessMetrics();
    return Promise.allSettled([tracerProvider.shutdown(), meterProvider.shutdown()]).then(() => {
    requestContextManager.disable();
    shutdown = undefined;
  });
  };
}

export function shutdownObservability(): Promise<void> { return shutdown?.() ?? Promise.resolve(); }

/** Seed durable result timestamps when metrics are enabled. The bounded query
    keeps telemetry best-effort and prevents an unavailable database from
    delaying API readiness. */
export async function initGenerationMetrics(db: Database, options: { enabled?: boolean; timeoutMs?: number } = {}): Promise<void> {
  const enabled = options.enabled ?? process.env.OTEL_ENABLED === 'true';
  if (!enabled) return;
  const timeoutMs = options.timeoutMs ?? 2_000;
  let timeout: ReturnType<typeof setTimeout> | undefined;
  try {
    const query = db.query(
      `SELECT max(finished_at) FILTER (WHERE state = 'finished') AS success_at,
              max(finished_at) FILTER (WHERE state = 'failed') AS failure_at
         FROM level_test_generation_jobs`)
      .then(result => ({ result }), () => ({ result: undefined }));
    const deadline = new Promise<{ result: undefined }>(resolve => {
      timeout = setTimeout(() => resolve({ result: undefined }), timeoutMs);
    });
    const { result } = await Promise.race([query, deadline]);
    if (!result) return;
    const row = (result as { rows: { success_at: Date | null; failure_at: Date | null }[] }).rows[0];
    if (row?.success_at) paliqueMetrics.generationLastSuccess.record(new Date(row.success_at).getTime() / 1000);
    if (row?.failure_at) paliqueMetrics.generationLastFailure.record(new Date(row.failure_at).getTime() / 1000);
  } catch { /* Telemetry must never block boot. */ }
  finally { if (timeout) clearTimeout(timeout); }
}

/** Refresh durable generation timestamps so one-shot CLI exporters remain
    represented after the CLI process exits and the collector expires its data. */
export function startGenerationMetricsRefresh(
  db: Database,
  intervalMs = 60_000,
  enabled = process.env.OTEL_ENABLED === 'true',
): () => void {
  if (!enabled) return () => {};
  const timer = setInterval(() => { void initGenerationMetrics(db, { enabled: true }); }, intervalMs);
  timer.unref();
  return () => clearInterval(timer);
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


/** Install process-only gauges; the caller owns their lifecycle. */
export function startProcessMetrics(dependencies?: {
  monitor: () => { enable: () => unknown; disable: () => unknown; percentile: (n:number) => number; reset: () => void };
  utilization: () => number;
  memory: () => { rss:number; heapUsed:number; heapTotal:number };
}): () => void {
  let previous = performance.eventLoopUtilization();
  const source = dependencies ?? {
    monitor: () => monitorEventLoopDelay({ resolution: 20 }),
    utilization: () => { const current=performance.eventLoopUtilization(); const delta=performance.eventLoopUtilization(current,previous); previous=current; return delta.utilization; },
    memory: () => process.memoryUsage(),
  };
  const monitor=source.monitor(); monitor.enable();
  const instruments=paliqueMetrics;
  const delay: Parameters<typeof instruments.processEventLoopDelay.addCallback>[0] = o => {
    const value=monitor.percentile(99)/1e9;
    if(Number.isFinite(value)) o.observe(value);
    monitor.reset();
  };
  const utilization: Parameters<typeof instruments.processEventLoopUtilization.addCallback>[0] = o => o.observe(source.utilization());
  const memory: Parameters<typeof instruments.processMemory.addCallback>[0] = o => {
    const m=source.memory(); o.observe(m.rss,{state:'rss'}); o.observe(m.heapUsed,{state:'heap_used'}); o.observe(m.heapTotal,{state:'heap_total'});
  };
  instruments.processEventLoopDelay.addCallback(delay);
  instruments.processEventLoopUtilization.addCallback(utilization);
  instruments.processMemory.addCallback(memory);
  let stopped=false;
  return () => { if(stopped)return;stopped=true;monitor.disable();
    instruments.processEventLoopDelay.removeCallback(delay);instruments.processEventLoopUtilization.removeCallback(utilization);instruments.processMemory.removeCallback(memory); };
}
