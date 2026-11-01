import {test} from 'node:test';import assert from 'node:assert/strict';
import {workloadTask,fixtureLayout,usefulResult} from '../load/workloads.mjs';
const fixture={tokens:['probe'],cohorts:{claimed:['c'],new:['n0','n1'],guest:['g'],active:['a']}};
test('fresh cohort is separate from preflight and cannot silently recycle already claimed accounts',()=>{
  assert.equal(fixtureLayout(false).length,200);
  const layout=fixtureLayout(true,1000);assert.equal(layout.length,1201);assert.equal(layout[0],'probe');assert.equal(layout.filter(x=>x==='new').length,1000);
  assert.equal(workloadTask('new',1,fixture).token,'n1');assert.throws(()=>workloadTask('new',2,fixture));
  assert.equal(workloadTask('claimed',123,fixture).token,'c');assert.equal(workloadTask('guest',1,fixture).token,'g');
  assert.throws(()=>workloadTask('constructor',0,fixture));assert.throws(()=>fixtureLayout(true,0));
});
test('progress 202 is useful only for the right method and matching merge result',()=>{
  const t=workloadTask('progress',0,fixture);
  assert.equal(t.method,'POST');assert.equal(t.path,'/v1/progress');
  assert.equal(usefulResult(t,202,{merged:1}),true);
  for(const [status,body] of [[200,{merged:1}],[202,{}],[202,{merged:0}],[429,{merged:1}],[302,{merged:1}]] as const)assert.equal(usefulResult(t,status,body),false);
  assert.equal(usefulResult({method:'GET',path:'/v1/progress'},200,{progress:[]}),true);
  assert.equal(usefulResult({method:'GET',path:'/v1/progress'},200,{progress:[{}]}),false);
  assert.equal(usefulResult({method:'GET',path:'/v1/progress'},200,{progress:[{kind:'lesson',itemID:'lesson-1',language:'en',attempts:1}]}),false);
  assert.equal(usefulResult({method:'GET',path:'/v1/progress'},200,{progress:[{kind:'lesson',itemID:'lesson-1',language:'en',firstAt:1,lastAt:2,attempts:1,bestScore:80}]}),true);
});
test('exam content checks real payload fields and does not accept empty success bodies',async()=>{
  const t=workloadTask('exams',0,fixture);
  const {composeWeekly}=await import('../src/level-tests.js');
  const payload=composeWeekly('en','A1',new Date('2026-10-30T12:00:00Z'))!;
  assert.equal(usefulResult(t,200,payload),true);assert.equal(usefulResult(t,200,{}),false);assert.equal(usefulResult(t,404,payload),false);
  assert.equal(usefulResult(t,200,{languageID:'en',level:'A1',weekId:2965,items:[{id:'i1',section:'reading'}]}),false);
  assert.equal(usefulResult(t,200,{...payload,items:payload.items.map((item,index)=>index===0?{...item,prompt:''}:item)}),false);
});
test('bounded mobile flow covers the source-derived foreground session routes',()=>{
  const tasks=Array.from({length:6},(_,index)=>workloadTask('mobile',index,fixture));
  assert.deepEqual(tasks.map(({method,path})=>[method,path]),[
    ['GET','/v1/auth/providers'],['GET','/v1/account'],['GET','/v1/minutes'],
    ['POST','/v1/progress'],['GET','/v1/progress'],['GET','/v1/level-tests/hybrid?language=en&level=A1'],
  ]);
  assert.equal(tasks[1]!.token,'c');assert.equal(tasks[2]!.token,'c');assert.equal(tasks[3]!.token,'c');assert.equal(tasks[4]!.token,'c');
  assert.deepEqual(tasks[3]!.body,{entries:[{kind:'lesson',itemID:'en-foundations-u1-to-be',language:'en',at:1793318400000,attempts:1,bestScore:80}]});
});
test('mobile foreground route checks reject malformed provider and account responses',()=>{
  assert.equal(usefulResult({method:'GET',path:'/v1/auth/providers'},200,{google:true,googleAndroid:false,apple:false}),true);
  assert.equal(usefulResult({method:'GET',path:'/v1/auth/providers'},200,{google:'yes',googleAndroid:false,apple:false}),false);
  assert.equal(usefulResult({method:'GET',path:'/v1/account'},200,{accountID:'00000000-0000-4000-8000-000000000000',email:null,providers:[],createdAt:'2026-10-30T00:00:00.000Z'}),true);
  assert.equal(usefulResult({method:'GET',path:'/v1/account'},200,{accountID:'bad',providers:[]}),false);
});

test('Android session route profile covers one assumed call per foreground action',()=>{
  const tasks=Array.from({length:7},(_,index)=>workloadTask('android-session',index,fixture));
  assert.deepEqual(tasks.map(({method,path})=>[method,path]),[
    ['GET','/v1/auth/providers'],['GET','/v1/account'],['GET','/v1/minutes'],
    ['GET','/v1/level-test-attempts?limit=10'],['POST','/v1/progress'],
    ['GET','/v1/level-tests/hybrid?language=en&level=A1&variant=1'],['POST','/v1/usage/events'],
  ]);
  assert.equal(tasks[1]!.token,'c');assert.equal(tasks[2]!.token,'c');
  assert.equal(tasks[3]!.token,'c');assert.equal(tasks[4]!.token,'c');assert.equal(tasks[6]!.token,'c');
  assert.equal(tasks[4]!.body?.entries?.length,1);
  const usageBody=JSON.parse(JSON.stringify(tasks[6]!.body)) as {platform:string;events:unknown[]};
  assert.equal(usageBody.platform,'android');
  assert.equal(usageBody.events.length,1);
});

test('Android session history and usage responses require their real route contracts',()=>{
  const history={method:'GET',path:'/v1/level-test-attempts?limit=10'};
  assert.equal(usefulResult(history,200,{attempts:[],nextCursor:null}),true);
  assert.equal(usefulResult(history,200,{attempts:{},nextCursor:null}),false);
  assert.equal(usefulResult(history,200,{attempts:[],nextCursor:7}),false);
  assert.equal(usefulResult(history,401,{attempts:[],nextCursor:null}),false);

  const usage=workloadTask('android-session',6,fixture);
  assert.equal(usefulResult(usage,202,{accepted:1}),true);
  assert.equal(usefulResult(usage,202,{accepted:0}),false);
  assert.equal(usefulResult(usage,200,{accepted:1}),false);
  assert.equal(usefulResult(usage,202,{accepted:'1'}),false);
});
