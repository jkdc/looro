import { test } from 'node:test';
import assert from 'node:assert/strict';
import { resourceProfile, validateResourceBudget, resourceEnvironment } from '../load/resource-profiles.mjs';
const MiB=1024**2;
test('aggregate profiles include every resident service within joint CPU and RAM budgets',()=>{
  for(const [name,cpu,mem] of [['economy',1,4096],['standard',2,8192],['peak',4,12288]] as const){
    const p=resourceProfile(name);
    assert.equal(p.cpuLimit,cpu);assert.equal(p.memoryLimitBytes,mem*MiB);
    assert.equal(Object.keys(p.services).length,8);
    assert.equal(validateResourceBudget(p,Object.keys(p.services)),true);
    const env=resourceEnvironment(p);assert.equal(Number(env.LOAD_API_CPUS),p.services['api-load']!.cpus);
    assert.equal(Number(env.LOAD_API_MEMORY),p.services['api-load']!.memoryBytes);
  }
});
test('invalid profile, missing service, unknown service and aggregate budget excess fail closed',()=>{
  assert.throws(()=>resourceProfile('custom'));
  const p=resourceProfile('economy'),names=Object.keys(p.services);
  assert.throws(()=>validateResourceBudget(p,names.slice(1)));
  assert.throws(()=>validateResourceBudget(p,[...names,'grafana-load']));
  const copy=structuredClone(p);copy.services['api-load']!.cpus=1;
  assert.throws(()=>validateResourceBudget(copy,names));
  const memory=structuredClone(p);memory.services['api-load']!.memoryBytes=4096*MiB;
  assert.throws(()=>validateResourceBudget(memory,names));
  const invalid=structuredClone(p);invalid.services['api-load']!.cpus=NaN;
  assert.throws(()=>validateResourceBudget(invalid,names));
});
test('incomplete resident inventory diagnostics name missing and unexpected services',()=>{
  const p=resourceProfile('economy'),names=Object.keys(p.services);
  assert.throws(()=>validateResourceBudget(p,names.filter(name=>name!=='tempo-load')),/missing=tempo-load/);
  assert.throws(()=>validateResourceBudget(p,[...names,'grafana-load']),/unexpected=grafana-load/);
});
test('profile names cannot resolve inherited object properties',()=>{
  for(const name of ['__proto__','constructor','toString'])assert.throws(()=>resourceProfile(name));
});
test('driver trust mount never exposes Caddy private CA storage',async()=>{
  const {readFile}=await import('node:fs/promises');
  const source=await readFile(new URL('../load/compose.resources.yaml',import.meta.url),'utf8');
  const driver=source.split('  driver-load:')[1]!.split(/\n  [a-z][a-z-]+:/)[0]!;
  assert.doesNotMatch(driver,/load_caddy_data/);
  assert.match(driver,/load_trust:\/trust:ro/);
});
test('telemetry and database allocation balance first-use latency within aggregate caps',()=>{
  for(const [name,tempoCpu,tempoMemory,prometheusCpu,prometheusMemory,databaseCpu,databaseMemory,caddyCpu] of [
    ['economy',.1,768,.05,256,.25,1536,.1],['standard',.2,1536,.1,512,.5,3072,.2],['peak',.4,2304,.2,768,1,4608,.4],
  ] as const){
    const p=resourceProfile(name);assert.equal(p.services['tempo-load']!.cpus,tempoCpu);assert.equal(p.services['tempo-load']!.memoryBytes,tempoMemory*MiB);
    assert.equal(p.services['prometheus-load']!.cpus,prometheusCpu);assert.equal(p.services['prometheus-load']!.memoryBytes,prometheusMemory*MiB);
    assert.equal(p.services['database-load']!.cpus,databaseCpu);assert.equal(p.services['database-load']!.memoryBytes,databaseMemory*MiB);
    assert.equal(p.services['caddy-load']!.cpus,caddyCpu);assert.equal(Object.values(p.services).reduce((sum,v)=>sum+v.cpus,0),p.cpuLimit);
    assert.equal(Object.values(p.services).reduce((sum,v)=>sum+v.memoryBytes,0),p.memoryLimitBytes);
    assert.equal(validateResourceBudget(p,Object.keys(p.services)),true);
  }
});
