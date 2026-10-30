#!/usr/bin/env node
import { loadScenario } from './driver.mjs';
import { randomUUID } from 'node:crypto';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
const mode=process.argv[2];
let scenario;try {scenario=loadScenario(process.argv[3]);} catch {console.error('Scenario must be fast, slow or hang');process.exit(2);}
if(!['smoke','baseline'].includes(mode)) {console.error('Usage: node load/run.mjs smoke|baseline');process.exit(2);}
const runId=randomUUID(), project=`palique-capacity-${runId}`;
const file=fileURLToPath(new URL('./compose.load.yaml',import.meta.url));
// Do not forward shell secrets or persistent-stack configuration into Compose.
const env={PATH:process.env.PATH,HOME:process.env.HOME,LOAD_RUN_ID:runId,LOAD_MODE:mode,STUB_MODE:'fast',LOAD_SCENARIO:scenario,PROXY_TIMEOUT_MS:scenario==='fast'?'30000':'1000'};
const args=['compose','--env-file','/dev/null','-p',project,'-f',file];
let child, interrupted=false;
const run=tokens=>new Promise(resolve=>{child=spawn('docker',[...args,...tokens],{env,stdio:'inherit'});child.on('error',()=>resolve(1));child.on('exit',code=>resolve(code??1));});
for(const signal of ['SIGINT','SIGTERM']) process.on(signal,()=>{interrupted=true;child?.kill(signal);});
let result=1;
try {
  if(await run(['build'])===0 && !interrupted && await run(['up','-d','--wait','api-load'])===0 && !interrupted) {
    result=await run(['run','--rm','driver-load']);
    // OTLP JSON contains closed labels only; no fixture tokens or request bodies.
    if(mode==='baseline') await run(['exec','-T','api-load','node','-e',"fetch('http://stub-load:8081/fixture/telemetry').then(r=>r.text()).then(console.log)"]);
  }
} finally {const cleanup=await run(['down','--volumes','--remove-orphans']);if(cleanup!==0)result=cleanup;}
process.exitCode=interrupted?130:result;
