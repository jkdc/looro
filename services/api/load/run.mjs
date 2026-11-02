#!/usr/bin/env node
import { loadScenario, driverRunArguments } from './driver.mjs';
import { randomUUID } from 'node:crypto';
import { spawn, execFileSync } from 'node:child_process';
import { availableParallelism,totalmem,arch,platform } from 'node:os';
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
console.log(JSON.stringify({type:'capacity_environment',revision:execFileSync('git',['rev-parse','HEAD'],{encoding:'utf8'}).trim(),node:process.version,platform:platform(),arch:arch(),visibleCPUs:availableParallelism(),hostMemoryBytes:totalmem(),mode,scenario,accounts:200,provider:'local-stub',network:'internal',resourceLimits:'not configured; local signal only'}));
let result=1;
try {
  if(await run(['build'])===0 && !interrupted && await run(['up','-d','--wait','api-load'])===0 && !interrupted) {
    result=await run(driverRunArguments());
    const persistence=await run(['exec','-T','api-load','node','--input-type=module','-e',`
      import {Pool} from 'pg';const p=new Pool({connectionString:process.env.DATABASE_URL});
      try {const attempts=await p.query('SELECT state,count(*)::int AS count FROM proxy_attempts GROUP BY state');
        const integrity=await p.query("SELECT (SELECT coalesce(sum(balance_nano),0)::text FROM wallets) AS balances, (SELECT count(*)::int FROM accounts) AS accounts, (SELECT coalesce(sum(balance_delta_nano),0)::text FROM ledger) AS journal, (SELECT count(*)::int FROM proxy_usage WHERE attempt_id IS NOT NULL) AS usage, (SELECT count(*)::int FROM proxy_attempts WHERE state='settled') AS settled");
        console.log(JSON.stringify({type:'capacity_persistence',attempts:attempts.rows,integrity:integrity.rows[0]}));
        const r=integrity.rows[0];if(r.accounts!==200||r.balances!==r.journal||r.usage!==r.settled)process.exitCode=1;
      }finally{await p.end();}
    `]);
    if(persistence!==0)result=persistence;
    // OTLP JSON contains closed labels only; no fixture tokens or request bodies.
    // The api shares the stub's network namespace, so the stub is on loopback.
    if(mode==='baseline') await run(['exec','-T','api-load','node','-e',"fetch('http://127.0.0.1:8081/fixture/telemetry').then(r=>r.text()).then(console.log)"]);
  }
} finally {const cleanup=await run(['down','--volumes','--remove-orphans']);if(cleanup!==0)result=cleanup;}
process.exitCode=interrupted?130:result;
