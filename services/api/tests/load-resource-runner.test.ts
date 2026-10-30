import {test} from 'node:test';import assert from 'node:assert/strict';
import {parseResourceRun,withResourceProject} from '../load/resource-run.mjs';
import {windowPlan,acceptWindow,summarizeWindows} from '../load/resource-workload.mjs';
test('resource CLI is closed and endurance requires an explicit validated rate',()=>{
  assert.equal(parseResourceRun(['economy','smoke']).profile,'economy');
  assert.equal(parseResourceRun(['standard','soak','25']).rate,25);
  for(const args of [[],['custom','steps'],['economy','unknown'],['peak','soak'],['peak','soak','0'],['peak','soak','1001'],['economy','smoke','25'],['economy','smoke','--url','https://example.com']])assert.throws(()=>parseResourceRun(args));
});
test('endurance is sixty bounded windows with no percentile averaging or lost failures',()=>{
  const p=windowPlan('soak',25);assert.equal(p.length,60);assert.equal(p.reduce((s,w)=>s+w.durationS,0),3600);
  assert.ok(p.every(w=>w.targetRps===25&&w.durationS===60));
  const good={offered:100,launched:100,useful:100,completed:100,dropped:0,transportFailures:0,latency:{success:{p95:10},all:{p99:20}},elapsedSeconds:60};
  assert.equal(acceptWindow(good),true);assert.equal(acceptWindow({...good,useful:90}),false);
  const s=summarizeWindows([good,{...good,useful:90}]);assert.equal(s.useful,190);assert.equal(s.accepted,false);assert.equal(s.failedWindows,1);assert.equal('p95' in s,false);assert.equal('samples' in s,false);
});
test('startup, integrity, interruption and cleanup errors fail without broad Docker cleanup',async()=>{
  for(const reason of ['startup','integrity','abort','cleanup']){
    const calls:any[]=[];const signal=new AbortController();if(reason==='abort')signal.abort();
    const rc=await withResourceProject(parseResourceRun(['economy','smoke']),{
      exec:async(args:string[],options:any)=>{calls.push({args,env:options.env});if(reason==='cleanup'&&args.includes('down'))throw Error('cleanup');return '';},
      log:()=>{},signal:signal.signal,
    },async ctx=>{await ctx.compose(['up','-d']);throw Error(reason);});
    assert.equal(rc,1);assert.ok(calls.some(x=>x.args.includes('down')&&x.args.includes('--volumes')));
    assert.ok(calls.every(x=>!x.args.includes('prune')));assert.ok(calls.every(x=>!('PROXY_PROVIDER_KEY' in x.env)));
    assert.ok(calls.every(x=>x.args.includes('compose')?String(x.args[x.args.indexOf('-p')+1]).startsWith('palique-resource-'):true));
  }
});
test('transient CA job must finish successfully before resident-only sampling',async()=>{
  const {waitFixtureJob}=await import('../load/resource-run.mjs');
  const calls:string[][]=[];
  const ctx={project:'palique-resource-test',compose:async(args:string[])=>{calls.push(args);return 'trust123\n';},docker:async(args:string[])=>{calls.push(args);return args[0]==='inspect'?JSON.stringify([{Config:{Labels:{'com.docker.compose.project':'palique-resource-test','com.docker.compose.service':'trust-load'}}}]):'0\n';}};
  assert.equal(await waitFixtureJob(ctx,'trust-load'),true);assert.deepEqual(calls[0],['up','-d','--no-deps','trust-load']);assert.ok(calls.some(c=>c[0]==='wait'&&c[1]==='trust123'));
  await assert.rejects(waitFixtureJob({...ctx,docker:async(args:string[])=>args[0]==='inspect'?ctx.docker(args):'1\n'},'trust-load'));
  await assert.rejects(waitFixtureJob(ctx,'foreign'));
});
test('resource CLI help documents commands and exits without starting Docker',async()=>{
  const {execFileSync}=await import('node:child_process');const {fileURLToPath}=await import('node:url');
  const output=execFileSync(process.execPath,[fileURLToPath(new URL('../load/resource-run.mjs',import.meta.url)),'--help'],{encoding:'utf8'});
  assert.match(output,/economy\|standard\|peak/);assert.match(output,/soak RATE/);
});
test('resource proof rejects hidden debits, per-account mismatches and invented uncertainty',async()=>{
  const {validateResourceProof}=await import('../load/resource-run.mjs');
  const p={accounts:1201,money:'1200999999991',journal:'1200999999991',minutes:'55500000',minute_journal:'55500000',settled_nano:'9',usage:1,settled:1,unknown:0,rejected:0,pending:0,bad_wallets:0,bad_minutes:0,duplicate_grants:0};
  assert.equal(validateResourceProof(p,1201),true);
  assert.throws(()=>validateResourceProof({...p,money:'1200999999990',journal:'1200999999990'},1201));
  assert.throws(()=>validateResourceProof({...p,bad_minutes:1},1201));assert.throws(()=>validateResourceProof({...p,unknown:1},1201));
  assert.equal(validateResourceProof({...p,unknown:2,rejected:1},1201,true),true);
  assert.throws(()=>validateResourceProof({...p,unknown:2,rejected:1,duplicate_grants:1},1201,true));
});
test('failure diagnostics retain closed error categories without leaking raw connection details',async()=>{
  const records:string[]=[];
  const rc=await withResourceProject(parseResourceRun(['standard','faults']),{
    exec:async(args:string[])=>args.includes('logs')?"Unhandled 'error' event\ncode: '57P01'\npassword=never-log-this":'',log:s=>records.push(s),
  },async()=>{throw Error('failure');});
  assert.equal(rc,1);const diagnostic=records.map(s=>JSON.parse(s)).find(r=>r.type==='resource_diagnostics');
  assert.ok(diagnostic);assert.ok(diagnostic.events.includes('unhandled_client_error'));assert.ok(diagnostic.events.includes('postgres_connection_terminated'));
  assert.ok(!records.join('').includes('never-log-this'));
});
