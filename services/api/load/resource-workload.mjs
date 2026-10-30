import {performance} from 'node:perf_hooks';import {fileURLToPath} from 'node:url';
import {runPhase,sampleRequest,preflight} from './driver.mjs';import {workloadTask} from './workloads.mjs';
export function windowPlan(preset,rate){
  if(preset==='soak'){
    if(!Number.isSafeInteger(rate)||rate<1||rate>1000)throw Error('Invalid soak rate');
    return Array.from({length:60},(_,i)=>({name:`soak-${i+1}`,kind:'mixed',durationS:60,targetRps:rate}));
  }
  if(preset==='steps')return [50,100,200,400,800].map(r=>({name:`step-${r}`,kind:'mixed',durationS:15,targetRps:r}));
  if(preset==='smoke')return ['claimed','new','guest','active','progress','exams'].map(kind=>({name:kind,kind,durationS:1,targetRps:4}));
  throw Error('Unknown workload preset');
}
export function acceptWindow(r){
  return r.offered>0 && r.launched/r.offered>=.98 && r.useful/r.offered>=.99 && r.transportFailures===0 && r.latency?.success?.p95!==null && r.latency?.success?.p95<=300 && r.latency?.all?.p99!==null && r.latency?.all?.p99<=1000;
}
export function summarizeWindows(results){
  return {windows:results.length,offered:results.reduce((s,r)=>s+r.offered,0),launched:results.reduce((s,r)=>s+r.launched,0),useful:results.reduce((s,r)=>s+r.useful,0),dropped:results.reduce((s,r)=>s+r.dropped,0),failedWindows:results.filter(r=>!acceptWindow(r)).length,accepted:results.length>0&&results.every(acceptWindow)};
}
export function mixedTask(i,fixture){
  if(i%10<7)return workloadTask('claimed',i,fixture);
  if(i%10===7)return workloadTask('progress',i,fixture);
  if(i%10===8)return {method:'GET',path:'/v1/progress',token:fixture.cohorts.claimed[i%fixture.cohorts.claimed.length]};
  return workloadTask('exams',i,fixture);
}
export async function runResourceWorkload(config,env=process.env){
  const {readFile}=await import('node:fs/promises');
  const fixture=JSON.parse(await readFile('/fixtures/manifest.json','utf8'));
  await preflight(env,fixture);
  const sample=(task,i)=>sampleRequest(task,i,{base:env.BASE,networks:2000,proxyToken:fixture.proxyToken,timeoutMs:5000});
  // Warm only the claimed cohort once, outside every timed window.
  for(let i=0;i<fixture.cohorts.claimed.length;i++)if(!(await sample(workloadTask('claimed',i,fixture),i)).useful)throw Error('Warmup failed');
  const results=[];
  for(const w of windowPlan(config.preset,config.rate)){
    const r=await runPhase({...w,maxInFlight:100,tasks:i=>w.kind==='mixed'?mixedTask(i,fixture):workloadTask(w.kind,i,fixture)},
      {now:()=>performance.now(),sleep:ms=>new Promise(r=>setTimeout(r,ms)),sample});
    results.push(r);console.log(JSON.stringify({type:'resource_window',...r,accepted:acceptWindow(r)}));
    if(config.preset==='steps'&&!acceptWindow(r))break;
  }
  const summary=summarizeWindows(results);console.log(JSON.stringify({type:'resource_summary',...summary}));
  // Exploratory steps stop at the first failed boundary and report it; a soak/smoke must meet acceptance.
  return config.preset==='steps'||summary.accepted?0:1;
}
if(process.argv[1]&&fileURLToPath(import.meta.url)===process.argv[1]){
  try{process.exitCode=await runResourceWorkload(JSON.parse(process.env.RESOURCE_RUN_CONFIG??'{}'));}
  catch{console.error('Resource workload failed; fixture credentials are not logged.');process.exitCode=1;}
}
