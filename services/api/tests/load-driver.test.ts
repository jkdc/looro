import { test } from 'node:test';
import assert from 'node:assert/strict';
// JS driver is copied beside compiled tests in the API image.
// @ts-ignore Native Node module has JSDoc rather than TS declarations.
import { readTask, clientHeaders, report, runPhase } from '../load/driver.mjs';

test('anonymous reads never request undefined and tokens rotate by launch index', () => {
  assert.deepEqual([0,1,2].map(i => readTask(i, [])), [
    { method: 'GET', path: '/v1/pricing' }, { method: 'GET', path: '/v1/minutes/products?provider=stripe' }, { method: 'GET', path: '/healthz' }]);
  assert.equal(readTask(3, ['a','b']).token, 'b');
});
test('NETWORKS bounds the synthetic network population', () => {
  const ips = Array.from({length:9}, (_,i) => clientHeaders(i,2,'key')['x-app-client-ip']);
  assert.equal(new Set(ips).size,2); assert.equal(ips[0],ips[2]);
});
test('drain time and error latency remain visible and empty percentiles are null', () => {
  const r = report([{status:200,useful:true,milliseconds:10},{status:503,useful:false,milliseconds:900}],
    {name:'test',offered:3,launched:2,completedInWindow:1,offeringSeconds:1,elapsedSeconds:2});
  assert.equal(r.usefulRps,0.5); assert.equal(r.drained,1); assert.equal(r.dropped,1);
  assert.equal(r.latency.all.p99,900); assert.equal(r.latency.success.p99,10);
  assert.equal(report([],{name:'empty',offered:0,launched:0,completedInWindow:0,offeringSeconds:1,elapsedSeconds:1}).latency.all.p95,null);
});
test('launch selection is independent of completion and capacity is bounded', async () => {
  let now=0; const indices:number[]=[]; const pending:Array<()=>void>=[];
  const r = await runPhase({name:'test',durationS:1,targetRps:4,maxInFlight:2,tasks:(i:number)=>({method:'GET',path:`/${i}`})}, {
    now:()=>now, sleep:async(ms:number)=>{now+=ms; if(now>=1000) pending.splice(0).forEach(f=>f());},
    sample:(t:{path:string})=>{indices.push(Number(t.path.slice(1))); return new Promise(resolve=>pending.push(()=>resolve({status:200,useful:true,milliseconds:1000})));}
  });
  assert.deepEqual(indices,[0,1]); assert.equal(r.launched,2); assert.equal(r.dropped,2); assert.equal(r.drained,2);
});

test('late scheduling does not replay missed arrivals', async () => {
  let now=0; const paths:string[]=[];
  const r=await runPhase({name:'late',durationS:1,targetRps:4,tasks:(i:number)=>({path:`/${i}`})}, {
    now:()=>now,sleep:async(ms:number)=>{now+=Math.max(ms,800);},
    sample:async(t:{path:string})=>{paths.push(t.path);return {status:200,useful:true,milliseconds:0};}
  });
  assert.ok(r.launched<=2); assert.equal(r.offered,4); assert.ok(r.dropped>=2);
});
test('driver CLI import never launches requests', async () => {
  let calls=0; const original=globalThis.fetch;
  globalThis.fetch=async()=>{calls++;throw Error('unexpected traffic');};
  try {
    // @ts-ignore Native CLI module is copied beside compiled tests.
    await Promise.race([import('../load/loadtest.mjs'), new Promise(resolve=>setTimeout(resolve,20))]); assert.equal(calls,0);
  } finally {globalThis.fetch=original;}
});

test('transport rejects redirects, invalid bodies and times out aborted fetches', async () => {
  // @ts-ignore Driver module is JavaScript.
  const d=await import('../load/driver.mjs');
  assert.equal(typeof d.sampleRequest,'function');
  for(const response of [new Response('{}',{status:302}),new Response('{}',{status:200})]) {
    const r=await d.sampleRequest({method:'GET',path:'/healthz'},0,{base:'http://localhost',timeoutMs:20,fetch:async()=>response});
    assert.equal(r.useful,false);
  }
  const r=await d.sampleRequest({method:'GET',path:'/healthz'},0,{base:'http://localhost',timeoutMs:5,
    fetch:async(_url:unknown,{signal}:{signal:AbortSignal})=>new Promise((_resolve,reject)=>signal.addEventListener('abort',()=>reject(signal.reason),{once:true}))});
  assert.equal(r.status,0);assert.equal(r.outcome,'timeout');
});

test('public product reads include the required provider query', () => {
  assert.equal(new URL(readTask(1,[]).path,'http://localhost').searchParams.get('provider'),'stripe');
});

test('external targets and unsafe URL forms fail before workload', async () => {
  // @ts-ignore Native JS module.
  const d=await import('../load/driver.mjs');assert.equal(typeof d.validateTarget,'function');
  for(const base of ['https://example.com','http://api:8080','http://user:pass@localhost','ftp://localhost','http://localhost/a','http://127.0.0.1.evil.test'])
    assert.throws(()=>d.validateTarget({BASE:base,LOAD_ISOLATED:'true'}));
  assert.throws(()=>d.validateTarget({BASE:'http://localhost'}));
  assert.equal(d.validateTarget({BASE:'http://api-load:8080',LOAD_ISOLATED:'true'}).hostname,'api-load');
});
test('invalid and missing fixture identities fail closed', async () => {
  // @ts-ignore Native JS module.
  const d=await import('../load/driver.mjs');assert.equal(typeof d.preflight,'function');
  const env={BASE:'http://api-load:8080',LOAD_ISOLATED:'true'};
  await assert.rejects(d.preflight(env,{runId:'abc',tokens:[]},async()=>new Response('{}')));
  await assert.rejects(d.preflight(env,{runId:'abc',tokens:['a'.repeat(64)],proxyToken:'test'},async()=>new Response(JSON.stringify({runId:'other'}))));
});

test('fixture session tokens satisfy the real authentication boundary',async()=>{
  // @ts-ignore JS helper is shared with the seed process.
  const driver=await import('../load/driver.mjs');
  const {bearerHash}=await import('../src/auth.js');
  assert.equal(typeof driver.createFixtureToken,'function');
  assert.match(bearerHash(`Bearer ${driver.createFixtureToken()}`),/^[a-f0-9]{64}$/);
});

test('challenge workload sends the required empty JSON object',async()=>{
  // @ts-ignore Shared JS workload helper.
  const d=await import('../load/driver.mjs');assert.equal(typeof d.writeTask,'function');
  let observed:any;
  const result=await d.sampleRequest(d.writeTask(),0,{base:'http://localhost',fetch:async(_url:unknown,options:any)=>{observed=options;return Response.json({challengeID:'fixture'});}});
  assert.equal(observed.body,'{}');assert.equal(observed.headers['content-type'],'application/json');assert.equal(result.useful,true);
});

test('isolated failure scenarios are a closed domain and never accept destinations',async()=>{
  // @ts-ignore Scenario validation is shared with the runner.
  const d=await import('../load/driver.mjs');assert.equal(typeof d.loadScenario,'function');
  assert.equal(d.loadScenario(undefined),'fast');assert.equal(d.loadScenario('slow'),'slow');assert.equal(d.loadScenario('hang'),'hang');
  assert.throws(()=>d.loadScenario('https://provider.example'));assert.throws(()=>d.loadScenario(''));
});


test('CLI smoke exercises public routes even when authenticated fixtures exist',async()=>{
  // @ts-ignore Native CLI uses dependency injection for transport and manifest boundaries.
  const {runCli}=await import('../load/loadtest.mjs');
  const id='00000000-0000-4000-8000-000000000001',calls=new Set<string>();let now=0;
  const code=await runCli({BASE:'http://api-load:8080',LOAD_ISOLATED:'true',LOAD_MODE:'smoke',DURATION_S:'1',TARGET_RPS:'4'}, {
    print:()=>{},readManifest:async()=>({runId:id,tokens:['a'.repeat(43)],proxyToken:'synthetic'}),now:()=>now,sleep:async(ms:number)=>{now+=ms;},
    fetch:async(url:any)=>{const path=new URL(url).pathname;calls.add(path);
      if(path==='/fixture/identity')return Response.json({runId:id});
      if(path==='/v1/proxy/responses')return Response.json({choices:[{message:{content:id}}],metering:{nanoUSDSettled:'0'}});
      if(path==='/v1/minutes')return Response.json({availableMilliseconds:0});
      if(path==='/v1/auth/challenge')return Response.json({challengeID:'fixture'});
      if(path==='/healthz')return Response.json({ok:true});
      if(path==='/v1/pricing')return Response.json({currency:'USD',moneyUnit:'nanoUSD'});
      if(path==='/v1/minutes/products')return Response.json({products:[]});
      return Response.json({});
    }
  });
  assert.equal(code,0);for(const path of ['/v1/minutes','/v1/pricing','/v1/minutes/products','/healthz'])assert.ok(calls.has(path),path);
});

test('driver startup never restarts one-shot seed or migration dependencies',async()=>{
  // @ts-ignore Shared Compose launch arguments.
  const d=await import('../load/driver.mjs');
  assert.equal(typeof d.driverRunArguments,'function');
  assert.deepEqual(d.driverRunArguments(),['run','--rm','--no-deps','driver-load']);
});
