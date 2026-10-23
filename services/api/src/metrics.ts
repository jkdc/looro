import { metrics } from '@opentelemetry/api';
import type { Counter, Gauge, Histogram, Meter, ObservableGauge } from '@opentelemetry/api';

export interface PaliqueMetrics {
  requestDuration: Histogram;
  requestsRejected: Counter;
  dbPoolConnections: ObservableGauge;
  dbPoolMaxConnections: Gauge;
  generationJobs: Counter;
  generationJobDuration: Histogram;
  generationLastSuccess: Gauge;
  llmRequests: Counter;
  llmRequestDuration: Histogram;
  llmTokens: Counter;
}

function build(meter: Meter): PaliqueMetrics {
  return {
    // RED of every HTTP request. Prometheus series:
    // http_server_request_duration_seconds_{count,sum,bucket}.
    requestDuration: meter.createHistogram('http.server.request.duration', { unit: 's',
      advice: { explicitBucketBoundaries: [0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10] },
      description: 'End-to-end request duration by method, route template and status.' }),
    requestsRejected: meter.createCounter('palique.requests.rejected', { description: 'Requests refused before routing (rate limiter, trusted proxy).' }),
    dbPoolConnections: meter.createObservableGauge('palique.db.pool.connections', { description: 'PostgreSQL pool connections by state.' }),
    dbPoolMaxConnections: meter.createGauge('palique.db.pool.max_connections', { description: 'Configured pool size (pg Pool max).' }),
    generationJobs: meter.createCounter('palique.leveltest.generation.jobs', { description: 'Weekly level-test generation job outcomes.' }),
    generationJobDuration: meter.createHistogram('palique.leveltest.generation.job.duration', { unit: 's',
      advice: { explicitBucketBoundaries: [1, 5, 15, 30, 60, 120, 300, 600, 900] },
      description: 'Wall-clock duration of level-test generation jobs.' }),
    generationLastSuccess: meter.createGauge('palique.leveltest.generation.last_success_unix_seconds',
      { description: 'Unix seconds of the last finished generation job; initialized at boot.' }),
    llmRequests: meter.createCounter('palique.llm.requests', { description: 'LLM chat completions by provider and outcome.' }),
    llmRequestDuration: meter.createHistogram('palique.llm.request.duration', { unit: 's',
      advice: { explicitBucketBoundaries: [0.25, 0.5, 1, 2, 5, 10, 30, 60, 120, 300, 600] },
      description: 'LLM completion latency by provider.' }),
    llmTokens: meter.createCounter('palique.llm.tokens', { description: 'Provider-reported tokens by direction.' }),
  };
}

// Live binding: consumers importing { paliqueMetrics } always see the latest
// instruments, so the provider installation order never freezes a no-op meter.
// Until initObservability installs the real provider this records nothing.
export let paliqueMetrics: PaliqueMetrics = build(metrics.getMeter('palique-api'));

export function initDomainMetrics(meter: Meter): void { paliqueMetrics = build(meter); }
