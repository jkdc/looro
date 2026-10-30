import {randomUUID} from 'node:crypto';import {execFile} from 'node:child_process';import {promisify} from 'node:util';import {fileURLToPath} from 'node:url';
import {resourceProfile,resourceEnvironment,validateResourceBudget} from './resource-profiles.mjs';
import {faultAction} from './faults.mjs';
import {assertOwnedContainer,sampleContainers} from './resource-sampler.mjs';
const execute=promisify(execFile);
export function parseResourceRun(args){
  const [profile,preset,value]=args;resourceProfile(profile);
  if(!['smoke','steps','soak','faults'].includes(preset)||args.length!==(preset==='soak'?3:2))throw Error('Usage: resource-run.mjs economy|standard|peak smoke|steps|faults OR profile soak RATE(1..1000)');
  const rate=preset==='soak'?Number(value):4;
  if(!Number.isSafeInteger(rate)||rate<1||rate>1000)throw Error('Invalid endurance rate');
  return {profile,preset,rate};
}
export async function withResourceProject(config,{exec=defaultExec,log=console.log,signal}={},work){
  const profile=resourceProfile(config.profile),project=`palique-resource-${randomUUID()}`;
  const files=['compose.load.yaml','compose.resources.yaml'].flatMap(f=>['-f',fileURLToPath(new URL(f,import.meta.url))]);
  const base=['compose','--env-file','/dev/null','-p',project,...files];
  const env={PATH:process.env.PATH,HOME:process.env.HOME,LOAD_RUN_ID:randomUUID(),LOAD_MODE:'smoke',STUB_MODE:'fast',PROXY_TIMEOUT_MS:config.preset==='faults'?'1000':'30000',...resourceEnvironment(profile)};
  let failed=false;
  const docker=async args=>{if(signal?.aborted)throw Error('Experiment interrupted');try{return await exec(args,{env,signal});}catch(error){if(args.includes('load/resource-workload.mjs')&&error.stdout)log(error.stdout);throw error;}};
  const compose=args=>docker([...base,...args]);
  try{if(signal?.aborted)throw Error('Experiment interrupted');await work({profile,project,env,compose,docker,log});}
  catch(error){failed=true;log(JSON.stringify({type:'resource_failure',project,code:error.code??null,detail:String(error.stderr??'').slice(-1500),reason:error.code?'docker_command_failed':String(error.message).slice(0,160),message:'Experiment failed; inspect synthetic logs, no credentials logged.'}));
    try{const raw=await exec([...base,'logs','--tail','1000','api-load','database-load'],{env});
      const events=[];if(/Unhandled 'error' event|Unhandled.*error/i.test(raw))events.push('unhandled_client_error');if(/57P01|terminating connection due to administrator command/.test(raw))events.push('postgres_connection_terminated');if(/ECONNRESET/.test(raw))events.push('connection_reset');
      log(JSON.stringify({type:'resource_diagnostics',project,events}));
    }catch{log(JSON.stringify({type:'resource_diagnostics',project,unavailable:true}));}
  }
  finally{
    try{await exec([...base,'down','--volumes','--remove-orphans'],{env});log(JSON.stringify({type:'resource_cleanup',project,result:'PASS'}));}
    catch{failed=true;log(JSON.stringify({type:'resource_cleanup',project,result:'FAIL'}));}
  }
  return failed?1:0;
}
async function defaultExec(args,options){return (await execute('docker',args,{...options,maxBuffer:8*1024**2})).stdout;}
export async function runResourceExperiment(config,dependencies={}){
  return withResourceProject(config,dependencies,async ctx=>{
    const {compose,docker,profile,project,log}=ctx;
    const cfg=JSON.parse(await compose(['config','--format','json']));
    validateResourceBudget(profile,Object.keys(cfg.services).filter(s=>profile.services[s]));
    await compose(['build','migrate-load']);
    const image=await docker(['image','inspect','palique-capacity:local','--format','{{.Id}}']);
    log(JSON.stringify({type:'resource_environment',project,profile:profile.name,image:image.trim(),cpuLimit:profile.cpuLimit,memoryLimitBytes:profile.memoryLimitBytes,excluded:['driver-load','stub-load'],optionalOff:['grafana','admin-static-ui']}));
    await compose(['up','-d','--wait','--wait-timeout','180','caddy-load','prometheus-load']);
    await waitFixtureJob(ctx,'trust-load');
    const sample=async()=>{
      const ids=(await docker(['ps','--filter',`label=com.docker.compose.project=${project}`,'--format','{{.ID}} {{.Label "com.docker.compose.service"}}'])).trim().split('\n').filter(Boolean).map(line=>{const [id,service]=line.trim().split(/\s+/);return {id,service};});
      const samples=await sampleContainers(project,ids,profile,docker);
      const resident=samples.filter(s=>s.includedInBudget);validateResourceBudget(profile,resident.map(s=>s.service));
      log(JSON.stringify({type:'resource_sample',at:new Date().toISOString(),samples}));
    };
    await sample();
    if(config.preset==='faults'){await runFaultExperiment(ctx,dependencies);await sample();const output=await compose(['run','--rm','--no-deps','driver-load','node','--input-type=module','-e',persistenceProbe]);const proof=JSON.parse(output.trim());validateResourceProof(proof,proof.expected_accounts,true);log(output);return;}
    let done=false;
    const run=compose(['run','--rm','--no-deps','-e',`RESOURCE_RUN_CONFIG=${JSON.stringify(config)}`,'driver-load','node','load/resource-workload.mjs']).then(out=>log(out)).finally(()=>{done=true;});
    // Attach a rejection handler immediately; cleanup still awaits the original failure.
    run.catch(()=>{});
    while(!done){await (dependencies.sleep??(ms=>new Promise(r=>setTimeout(r,ms))))(15000);if(!done)await sample();}
    await run;await sample();
    const proof=await compose(['run','--rm','--no-deps','driver-load','node','--input-type=module','-e',persistenceProbe]);const parsed=JSON.parse(proof.trim());validateResourceProof(parsed,parsed.expected_accounts);log(proof);
  });
}
export async function waitFixtureJob(ctx,service){
  if(!['trust-load','seed-load','migrate-load'].includes(service))throw Error('Unknown fixture job');
  await ctx.compose(['up','-d','--no-deps',service]);
  const id=(await ctx.compose(['ps','--all','-q',service])).trim();
  if(!/^[a-zA-Z0-9]+$/.test(id))throw Error('Invalid fixture job identity');
  const [inspect]=JSON.parse(await ctx.docker(['inspect',id]));assertOwnedContainer(inspect,ctx.project,[service]);
  if((await ctx.docker(['wait',id])).trim()!=='0')throw Error('Fixture job failed');
  return true;
}
async function runFaultExperiment(ctx,dependencies){
  const {compose,docker,log,project,env}=ctx;
  log(await compose(['run','--rm','--no-deps','-e',`RESOURCE_RUN_CONFIG=${JSON.stringify({preset:'smoke',rate:4})}`,'driver-load','node','load/resource-workload.mjs']));
  const client=mode=>compose(['run','--rm','--no-deps','driver-load','node','load/resource-fault-client.mjs',mode]);
  const setMode=async mode=>{
    const code=`fetch('http://stub-load:8081/fixture/mode',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify(${JSON.stringify({runId:env.LOAD_RUN_ID,mode})})}).then(r=>{if(!r.ok)process.exit(1)})`;
    await compose(['exec','-T','api-load','node','-e',code]);
  };
  for(const kind of ['slow','hang','http-error']){
    const action=faultAction(kind,{project,containers:{}});await setMode(action.mode);log(await client(kind));await setMode('fast');
  }
  for(const kind of ['restart-api','restart-db']){
    const service=kind==='restart-api'?'api-load':'database-load';
    const id=(await compose(['ps','-q',service])).trim();const [container]=JSON.parse(await docker(['inspect',id]));
    const action=faultAction(kind,{project,containers:{[service]:container}});
    const traffic=client('restart-client');traffic.catch(()=>{});
    await (dependencies.sleep??(ms=>new Promise(r=>setTimeout(r,ms))))(1000);
    await docker(action.args);log(await traffic);log(await client('recover'));
    log(JSON.stringify({type:'resource_restart_fault',kind,result:'PASS'}));
  }
}
export function validateResourceProof(p,expectedAccounts,faults=false){
  if(p.accounts!==expectedAccounts||p.money!==p.journal||p.minutes!==p.minute_journal||p.usage!==p.settled||p.pending!==0||p.bad_wallets!==0||p.bad_minutes!==0||p.duplicate_grants!==0||p.unknown!==(faults?2:0)||p.rejected!==(faults?1:0))throw Error('Synthetic persistence invariant failed');
  if(BigInt(p.money)!==BigInt(expectedAccounts)*1000000000n-BigInt(p.settled_nano))throw Error('Unexpected synthetic wallet movement');
  return true;
}
const persistenceProbe=`import {Pool} from 'pg';import {readFile} from 'node:fs/promises';const f=JSON.parse(await readFile('/fixtures/manifest.json','utf8'));const p=new Pool({connectionString:'postgresql://capacity:synthetic-load-only@database-load:5432/capacity_test'});try{const r=(await p.query(\`SELECT
(SELECT count(*)::int FROM accounts) accounts,
(SELECT coalesce(sum(balance_nano),0)::text FROM wallets) money,
(SELECT coalesce(sum(balance_delta_nano),0)::text FROM ledger) journal,
(SELECT coalesce(sum(balance_ms),0)::text FROM minute_wallets) minutes,
(SELECT coalesce(sum(balance_delta_ms),0)::text FROM minute_entries) minute_journal,
(SELECT coalesce(sum(settled_nano),0)::text FROM proxy_usage) settled_nano,
(SELECT count(*)::int FROM proxy_usage) usage,
(SELECT count(*)::int FROM proxy_attempts WHERE state='settled') settled,
(SELECT count(*)::int FROM proxy_attempts WHERE state='unknown') unknown,
(SELECT count(*)::int FROM proxy_attempts WHERE state='rejected') rejected,
(SELECT count(*)::int FROM proxy_attempts WHERE state IN ('prepared','dispatched')) pending,
(SELECT count(*)::int FROM wallets w LEFT JOIN (SELECT account_id,sum(balance_delta_nano) b FROM ledger GROUP BY account_id) l USING(account_id) WHERE w.balance_nano<>coalesce(l.b,0)) bad_wallets,
(SELECT count(*)::int FROM minute_wallets w LEFT JOIN (SELECT account_id,sum(balance_delta_ms) b FROM minute_entries GROUP BY account_id) l USING(account_id) WHERE w.balance_ms<>coalesce(l.b,0)) bad_minutes,
(SELECT count(*)::int FROM (SELECT account_id,coalesce(subscription_id::text,'free'),period_key FROM subscription_grants GROUP BY 1,2,3 HAVING count(*)>1) d) duplicate_grants
\`)).rows[0];console.log(JSON.stringify({type:'resource_integrity',expected_accounts:f.expectedAccounts,...r}));if(r.accounts!==f.expectedAccounts)process.exitCode=1}finally{await p.end()}`;

if(process.argv[1]&&fileURLToPath(import.meta.url)===process.argv[1]){
  if(process.argv.length===3&&process.argv[2]==='--help'){console.log('Usage: node load/resource-run.mjs economy|standard|peak smoke|steps|faults\n       node load/resource-run.mjs PROFILE soak RATE (1..1000)\nLocal synthetic fixtures only. No deployment or remote targets.');process.exit(0);}
  let config;try{config=parseResourceRun(process.argv.slice(2));}catch(e){console.error(e.message);process.exit(2);}
  const controller=new AbortController();for(const s of ['SIGINT','SIGTERM'])process.on(s,()=>controller.abort());
  process.exitCode=await runResourceExperiment(config,{signal:controller.signal});
}
