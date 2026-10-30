import {test} from 'node:test';import assert from 'node:assert/strict';
import {faultAction} from '../load/faults.mjs';
test('fault actions reference only validated owned fixture identities and closed fault kinds',()=>{
  const owned={project:'palique-resource-test',containers:{'api-load':{Id:'api123',Config:{Labels:{'com.docker.compose.project':'palique-resource-test','com.docker.compose.service':'api-load'}}},'database-load':{Id:'db123',Config:{Labels:{'com.docker.compose.project':'palique-resource-test','com.docker.compose.service':'database-load'}}}}};
  assert.deepEqual(faultAction('restart-api',owned),{kind:'restart',args:['restart','--time','5','api123']});
  assert.equal(faultAction('http-error',owned).mode,'http-error');
  assert.throws(()=>faultAction('kill-any',owned));
  const bad=structuredClone(owned);bad.containers['api-load'].Config.Labels['com.docker.compose.project']='production';assert.throws(()=>faultAction('restart-api',bad));
});
test('fixture provider supports an explicit HTTP429 failure without metered usage',async()=>{
  const {providerReply}=await import('../load/faults.mjs');
  const r=providerReply('http-error','synthetic-run');assert.equal(r.status,429);assert.equal(r.hang,false);assert.equal(r.delayMs,0);assert.equal('usage' in r.body,false);
  assert.equal(providerReply('slow','x').delayMs,2000);assert.equal(providerReply('hang','x').hang,true);
  assert.equal(providerReply('fast','x').body.choices[0].message.content,'x');assert.throws(()=>providerReply('unknown','x'));
});
test('provider fault actions also reject non-fixture project identities',()=>{
  assert.throws(()=>faultAction('hang',{project:'production',containers:{}}));
});
test('recovery recognizes the actual readyz database contract, not the healthz shape',async()=>{
  const {readyPayload}=await import('../load/faults.mjs');
  assert.equal(readyPayload({database:true,hostedVoice:false}),true);assert.equal(readyPayload({ok:true}),false);assert.equal(readyPayload({database:false}),false);
});
