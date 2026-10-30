import assert from 'node:assert/strict';import {readFile} from 'node:fs/promises';import {performance} from 'node:perf_hooks';
import {sampleRequest,clientHeaders,runPhase} from './driver.mjs';import {workloadTask} from './workloads.mjs';import {readyPayload} from './faults.mjs';
if(process.env.LOAD_ISOLATED!=='true'||process.env.BASE!=='https://caddy-load')throw Error('Synthetic TLS fixture required');
const mode=process.argv[2],fixture=JSON.parse(await readFile('/fixtures/manifest.json','utf8'));
const options={base:process.env.BASE,networks:2000,proxyToken:fixture.proxyToken,timeoutMs:5000};
if(['slow','hang','http-error'].includes(mode)){
  const task={method:'POST',path:'/v1/proxy/responses',token:fixture.tokens[0],body:{model:'stub',stream:false,messages:[{role:'user',content:'fixture-fault'}]}};
  const [proxy,read]=await Promise.all([sampleRequest(task,0,options),sampleRequest(workloadTask('claimed',0,fixture),1,options)]);
  assert.equal(proxy.status,mode==='http-error'?502:504);assert.equal(proxy.useful,false);assert.equal(read.useful,true);
  console.log(JSON.stringify({type:'resource_provider_fault',mode,status:proxy.status,concurrentReadUseful:read.useful}));
}else if(mode==='restart-client'){
  const result=await runPhase({name:'restart-observation',durationS:5,targetRps:5,maxInFlight:20,tasks:i=>workloadTask('progress',i,fixture)},
    {now:()=>performance.now(),sleep:ms=>new Promise(r=>setTimeout(r,ms)),sample:(t,i)=>sampleRequest(t,i,options)});
  // Availability during an intentional restart is an observation, not claimed SLO success.
  console.log(JSON.stringify({type:'resource_restart_traffic',...result}));
}else if(mode==='recover'){
  let ready=false;
  for(let i=0;i<60;i++){
    try{const r=await fetch('https://caddy-load/readyz',{signal:AbortSignal.timeout(1000)});if(r.ok&&readyPayload(await r.json())){ready=true;break;}}catch{}
    await new Promise(r=>setTimeout(r,500));
  }
  assert.equal(ready,true,'API failed to recover within bounded polling');
  assert.equal((await sampleRequest(workloadTask('progress',0,fixture),0,options)).useful,true);
  assert.equal((await sampleRequest(workloadTask('claimed',0,fixture),0,options)).useful,true);
  console.log(JSON.stringify({type:'resource_recovery',result:'PASS'}));
}else throw Error('Unknown fixture probe');
