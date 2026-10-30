import {test} from 'node:test';
import assert from 'node:assert/strict';
import {summarizeContainer,assertOwnedContainer,assertEffectiveLimits} from '../load/resource-sampler.mjs';
const inspect={Id:'id123',Config:{Labels:{'com.docker.compose.project':'palique-resource-test','com.docker.compose.service':'api-load'}},HostConfig:{NanoCpus:400000000,Memory:1073741824},State:{OOMKilled:false},RestartCount:2};
test('sampler preserves effective quotas, units, throttling and explicitly missing metrics',()=>{
  const r=summarizeContainer(inspect,{CPUPerc:'12.5%',MemUsage:'10MiB / 1GiB',NetIO:'2kB / 3kB',BlockIO:'1MB / 2MB'},'usage_usec 1000\nnr_throttled 4\nthrottled_usec 200\n');
  assert.equal(r.cpus,.4);assert.equal(r.memoryLimitBytes,1073741824);assert.equal(r.memoryUsedBytes,10*1024**2);
  assert.equal(r.cpuPercent,12.5);assert.equal(r.cpuUsageUsec,1000);assert.equal(r.throttledUsec,200);assert.equal(r.throttledPeriods,4);
  assert.equal(r.restarts,2);assert.equal(r.oomKilled,false);assert.deepEqual(r.networkBytes,[2000,3000]);
  const absent=summarizeContainer(inspect,{},null);assert.equal(absent.throttledUsec,null);assert.equal(absent.memoryUsedBytes,null);assert.equal(absent.cpuPercent,null);
});
test('sampler accepts only owned identities and rejects mismatched effective limits',()=>{
  assert.equal(assertOwnedContainer(inspect,'palique-resource-test',['api-load']),true);
  assert.throws(()=>assertOwnedContainer(inspect,'other',['api-load']));
  assert.throws(()=>assertOwnedContainer(inspect,'palique-resource-test',['database-load']));
  assert.equal(assertEffectiveLimits(inspect,{cpus:.4,memoryBytes:1073741824}),true);
  assert.throws(()=>assertEffectiveLimits(inspect,{cpus:1,memoryBytes:1073741824}));
  assert.throws(()=>assertEffectiveLimits({...inspect,HostConfig:{}},{cpus:.4,memoryBytes:1073741824}));
});
