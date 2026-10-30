#!/usr/bin/env node
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';
import { readFile } from 'node:fs/promises';
import { positiveInteger, loadScenario, preflight, readTask, writeTask, runPhase, sampleRequest } from './driver.mjs';

export async function runCli(env=process.env,{print=console.log,fetch:fetchFn=fetch}={}) {
  try {
    const fixture=JSON.parse(await readFile('/fixtures/manifest.json','utf8'));
    const networks=positiveInteger(env.NETWORKS,200,100000,'NETWORKS');
    const maxInFlight=positiveInteger(env.MAX_IN_FLIGHT,100,10000,'MAX_IN_FLIGHT');
    const timeoutMs=positiveInteger(env.TIMEOUT_MS,5000,300000,'TIMEOUT_MS');
    const durationS=positiveInteger(env.DURATION_S,env.LOAD_MODE==='smoke'?2:30,7200,'DURATION_S');
    const rate=positiveInteger(env.TARGET_RPS,env.LOAD_MODE==='smoke'?4:60,100000,'TARGET_RPS');
    const scenario=loadScenario(env.LOAD_SCENARIO);
    await preflight(env,fixture,fetchFn);
    // Activate degradation only AFTER verifying identity and a normal metered completion.
    const mode=await fetchFn('http://stub-load:8081/fixture/mode',{method:'POST',headers:{'content-type':'application/json'},
      body:JSON.stringify({runId:fixture.runId,mode:scenario}),redirect:'error',signal:AbortSignal.timeout(5000)});
    if(!mode.ok)throw Error('Fixture scenario rejected');
    const phases=[['warmup',Math.min(durationS,10),20,'read'],['steady-reads',durationS,rate,'read'],
      ['challenge-writes',durationS,Math.min(rate,25),'write'],['mixed',durationS,rate,'mixed'],
      ['spike',1,300,'read'],['proxy-completions',durationS,5,'proxy']];
    let failed=false;
    for(const [name,seconds,rps,type] of phases) {
      const tasks=i=>type==='proxy'?{method:'POST',path:'/v1/proxy/responses',token:fixture.tokens[i%fixture.tokens.length],body:{model:'stub',stream:false,messages:[{role:'user',content:'loadtest'}]}}
        :type==='write'||(type==='mixed'&&i%5===0)?writeTask():readTask(i,fixture.tokens);
      const result=await runPhase({name,durationS:seconds,targetRps:rps,maxInFlight,tasks},{now:()=>performance.now(),sleep:ms=>new Promise(r=>setTimeout(r,ms)),
        sample:(t,i)=>sampleRequest(t,i,{base:env.BASE,timeoutMs:type==='proxy'?35000:timeoutMs,networks,proxyToken:fixture.proxyToken,fetch:fetchFn})});
      print(JSON.stringify(result));
      if(!result.useful || result.transportFailures) failed=true;
    }
    return failed?1:0;
  } catch(error) {
    const safe=new Set(['Explicit isolated target required','Unsafe target','Invalid fixture manifest','Fixture identity mismatch','Invalid authenticated fixture','Proxy is not the fixture provider']);
    console.error(safe.has(error?.message)?error.message:'Isolated load execution failed');return 2;
  }
}
if(process.argv[1] && resolve(process.argv[1])===fileURLToPath(import.meta.url)) process.exitCode=await runCli();
