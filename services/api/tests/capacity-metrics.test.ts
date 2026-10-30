import { test } from 'node:test';
import assert from 'node:assert/strict';
import { MeterProvider, MetricReader } from '@opentelemetry/sdk-metrics';
class InMemoryMetricReader extends MetricReader {
 protected onShutdown(){return Promise.resolve();}
 protected onForceFlush(){return Promise.resolve();}
}
import * as obs from '../src/observability.js';
import { initDomainMetrics } from '../src/metrics.js';

test('process identities are unique to a process rather than the literal api',()=>{
  const a=obs.observabilityResourceAttributes({});
  assert.match(a['service.instance.id']!,/^[a-f0-9-]{36}$/);
  assert.equal(a['service.instance.id'],obs.observabilityResourceAttributes({})['service.instance.id']);
  assert.equal(obs.observabilityResourceAttributes({OTEL_SERVICE_INSTANCE_ID:'explicit'})['service.instance.id'],'explicit');
});
test('process metrics convert delay to seconds and stop removes callbacks exactly once',async()=>{
  const reader=new InMemoryMetricReader(),provider=new MeterProvider({readers:[reader]});
  initDomainMetrics(provider.getMeter('capacity'));
  let enabled=0,disabled=0,resets=0;
  assert.equal(typeof (obs as any).startProcessMetrics,'function');
  const stop=(obs as any).startProcessMetrics({
    monitor:()=>({enable:()=>enabled++,disable:()=>disabled++,percentile:()=>25_000_000,reset:()=>resets++}),
    utilization:()=>.25,memory:()=>({rss:100,heapUsed:20,heapTotal:40})
  });
  const data=(await reader.collect()).resourceMetrics.scopeMetrics.flatMap(s=>s.metrics);
  const delay=data.find(m=>m.descriptor.name==='palique.process.event_loop.delay')!;
  assert.equal(delay.dataPoints[0]!.value,.025);assert.equal(enabled,1);assert.equal(resets,1);
  stop();stop();assert.equal(disabled,1);
  await reader.collect();
  assert.equal(resets,1); // OTel may retain the last sample; collection must not call the stopped monitor.
  await provider.shutdown();
});
