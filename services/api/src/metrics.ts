import { metrics } from '@opentelemetry/api';
import type { Counter, Gauge, Histogram, Meter, ObservableGauge } from '@opentelemetry/api';

export interface PaliqueMetrics {
  processEventLoopDelay: ObservableGauge;
  processEventLoopUtilization: ObservableGauge;
  processMemory: ObservableGauge;
  proxyInFlight: ObservableGauge;
  proxyRejected: Counter;
  proxyProviderDuration: Histogram;
  proxyUnknownAttempts: Counter;
  proxyPolicyRejected: Counter;
  requestDuration: Histogram;
  requestsRejected: Counter;
  dbPoolConnections: ObservableGauge;
  dbPoolMaxConnections: Gauge;
  generationJobs: Counter;
  generationJobDuration: Histogram;
  generationLastSuccess: Gauge;
  generationLastFailure: Gauge;
  dailyGenerationJobs: Counter;
  dailyReviewJobs: Counter;
  dailyJobDuration: Histogram;
  dailyAttempts: Counter;
  dailyWordsImports: Counter;
  llmRequests: Counter;
  llmRequestDuration: Histogram;
  llmTokens: Counter;
}

function build(meter: Meter): PaliqueMetrics {
  return {
    processEventLoopDelay: meter.createObservableGauge('palique.process.event_loop.delay', { unit: 's' }),
    processEventLoopUtilization: meter.createObservableGauge('palique.process.event_loop.utilization'),
    processMemory: meter.createObservableGauge('palique.process.memory', { unit: 'By' }),
    proxyInFlight: meter.createObservableGauge('palique.proxy.in_flight'),
    proxyRejected: meter.createCounter('palique.proxy.rejected'),
    proxyProviderDuration: meter.createHistogram('palique.proxy.provider.duration', { unit: 's' }),
    proxyUnknownAttempts: meter.createCounter('palique.proxy.unknown_attempts'),
    proxyPolicyRejected: meter.createCounter('palique.proxy.request_policy_rejected', { description: 'Proxy calls refused by the request policy, by allowlist field.' }),
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
    generationLastFailure: meter.createGauge('palique.leveltest.generation.last_failure_unix_seconds',
      { description: 'Unix seconds of the last failed generation execution; initialized at boot.' }),
    dailyGenerationJobs: meter.createCounter('palique.daily.generation.jobs', { description: 'Daily package generation job outcomes.' }),
    dailyReviewJobs: meter.createCounter('palique.daily.review.jobs', { description: 'Daily learner review job outcomes.' }),
    dailyJobDuration: meter.createHistogram('palique.daily.job.duration', { unit: 's',
      advice: { explicitBucketBoundaries: [1, 5, 15, 30, 60, 120, 300, 600, 900] },
      description: 'Daily generation and review job duration.' }),
    dailyAttempts: meter.createCounter('palique.daily.attempts', { description: 'Daily practice attempt state transitions.' }),
    dailyWordsImports: meter.createCounter('palique.daily.words.imports', { description: 'Content-free daily Words import outcomes.' }),
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
