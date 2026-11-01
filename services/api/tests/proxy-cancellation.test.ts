import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID, randomBytes, createHash } from 'node:crypto';
import { request as httpRequest } from 'node:http';
import { connectDatabase, transaction } from '../src/db.js';
import { appendEntry } from '../src/ledger.js';
import { migrate } from '../src/migrate.js';
import { TokenProxy } from '../src/token-proxy.js';
import { createApp } from '../src/app.js';
const url=process.env.TEST_DATABASE_URL;
if(url&&!new URL(url).pathname.endsWith('_test'))throw Error('Dedicated database required');
test('real HTTP disconnect cancels unknown usage but never cancels known settlement',{skip:!url},async t=>{
 const schema=`cancel_${randomUUID().replaceAll('-','')}`,u=new URL(url!);u.searchParams.set('options',`-c search_path=${schema}`);
 const db=connectDatabase(u.toString());
 try {
  await db.query(`CREATE SCHEMA ${schema}`);await migrate(db);
  const account=randomUUID(),token=randomBytes(32).toString('base64url');
  await db.query('INSERT INTO accounts(id) VALUES($1)',[account]);await db.query('INSERT INTO wallets(account_id) VALUES($1)',[account]);
  await db.query('INSERT INTO entitlements(account_id,unlocked_at) VALUES($1,now())',[account]);
  await db.query("INSERT INTO auth_sessions(id,account_id,token_hash,expires_at) VALUES($1,$2,$3,now()+interval '1 hour')",[randomUUID(),account,createHash('sha256').update(token).digest('hex')]);
  await transaction(db,sql=>appendEntry(sql,account,`seed:${account}`,'purchase',100000n,0n));
  const config={providerURL:'http://stub',providerKey:'test',maxTokens:128,maxConcurrentPerAccount:2};
  await t.test('full incoming request completion does not abort normal replies and busy carries Retry-After',async()=>{
   let release!:()=>void,entered!:()=>void;const gate=new Promise<void>(r=>entered=r);let calls=0;
   const proxy=new TokenProxy(db,{...config,maxInFlight:1},(async()=>{calls++;if(calls===1){entered();await new Promise<void>(r=>release=r);}return Response.json({choices:[],usage:{prompt_tokens:10,completion_tokens:5}});}) as typeof fetch);
   const app=createApp({db,auth:{},tokenProxy:proxy});await app.listen({port:0,host:'127.0.0.1'});
   const base=`http://127.0.0.1:${(app.server.address() as any).port}`;
   try {
    const first=fetch(base+'/v1/proxy/responses',{method:'POST',headers:{authorization:`Bearer ${token}`,'content-type':'application/json'},body:'{}'});await gate;
    try {const busy=await fetch(base+'/v1/proxy/responses',{method:'POST',headers:{authorization:`Bearer ${token}`,'content-type':'application/json'},body:'{}'});assert.equal(busy.status,503);assert.equal(busy.headers.get('retry-after'),'1');}
    finally {release();}
    assert.equal((await first).status,200);
   } finally {await proxy.stop();await app.close();}
  });
  await t.test('disconnect during authentication never dispatches billable provider work',async()=>{
   let release!:()=>void,entered!:()=>void,closed!:()=>void,finished!:()=>void;
   const hold=new Promise<void>(r=>release=r),gate=new Promise<void>(r=>entered=r),closeGate=new Promise<void>(r=>closed=r),done=new Promise<void>(r=>finished=r);
   const delayed=new Proxy(db,{get(target,key){
    if(key==='query')return async(...args:any[])=>{if(typeof args[0]==='string'&&args[0].includes('SELECT s.account_id FROM auth_sessions')){entered();await hold;}return (target.query as any)(...args);};
    const value=(target as any)[key];return typeof value==='function'?value.bind(target):value;
   }});
   let calls=0;
   const proxy=new TokenProxy(db,config,(async()=>{calls++;return Response.json({choices:[],usage:{prompt_tokens:10,completion_tokens:5}});}) as typeof fetch);
   const relay=proxy.relay.bind(proxy);proxy.relay=async(...args)=>{try{return await relay(...args);}finally{finished();}};
   const app=createApp({db:delayed,auth:{},tokenProxy:proxy});app.addHook('onRequest',async(_req,reply)=>{reply.raw.once('close',closed);});
   await app.listen({port:0,host:'127.0.0.1'});
   const balance=(await db.query('SELECT balance_nano FROM wallets WHERE account_id=$1',[account])).rows[0].balance_nano;
   try {
    const req=httpRequest({hostname:'127.0.0.1',port:(app.server.address() as any).port,path:'/v1/proxy/responses',method:'POST',headers:{authorization:`Bearer ${token}`,'content-type':'application/json'}});req.on('error',()=>{});req.end('{}');
    await gate;req.destroy();await closeGate;release();await done;
    assert.equal(calls,0);
    assert.equal((await db.query('SELECT balance_nano FROM wallets WHERE account_id=$1',[account])).rows[0].balance_nano,balance);
   } finally {release();await proxy.stop();await app.close();}
  });
  await t.test('closing an actual socket during transport persists unknown and releases capacity',async()=>{
   let entered!:()=>void,aborted!:()=>void;const gate=new Promise<void>(r=>entered=r),abortGate=new Promise<void>(r=>aborted=r);
   const proxy=new TokenProxy(db,config,(async(_url:any,o:any)=>{entered();return new Promise((_resolve,reject)=>{o.signal.addEventListener('abort',()=>{aborted();reject(o.signal.reason);},{once:true});});}) as typeof fetch);
   const app=createApp({db,auth:{},tokenProxy:proxy});await app.listen({port:0,host:'127.0.0.1'});
   const before=(await db.query('SELECT count(*) FROM proxy_attempts WHERE state=\'unknown\'')).rows[0].count;
   try {
    const req=httpRequest({hostname:'127.0.0.1',port:(app.server.address() as any).port,path:'/v1/proxy/responses',method:'POST',headers:{authorization:`Bearer ${token}`,'content-type':'application/json'} });req.on('error',()=>{});req.end('{}');
    await gate;req.destroy();await abortGate;await proxy.stop();
    assert.equal(Number((await db.query("SELECT count(*) FROM proxy_attempts WHERE state='unknown'")).rows[0].count),Number(before)+1);
   } finally {await proxy.stop();await app.close();}
  });
  await t.test('usage known before cancellation still settles while wallet lock delays SQL',async()=>{
   // Funded admission reserves (and locks the wallet) BEFORE dispatch, so the
   // lock is now free during the provider call: park the provider, take the
   // wallet lock, and only then let the settle transaction race for it —
   // observing the real lock wait, never a timing assumption.
   const controller=new AbortController();let entered!:()=>void;const gate=new Promise<void>(r=>entered=r);
   let releaseProvider!:()=>void;const providerHold=new Promise<void>(r=>releaseProvider=r);
   const proxy=new TokenProxy(db,config,(async()=>{entered();await providerHold;return Response.json({choices:[],usage:{prompt_tokens:1000000,completion_tokens:0}});}) as typeof fetch);
   const before=(await db.query('SELECT balance_nano FROM wallets WHERE account_id=$1',[account])).rows[0].balance_nano;
   const result=proxy.relay(account,{}, {signal:controller.signal});
   void result.catch(()=>{});
   let lock: { query:(sql:string,values?:unknown[])=>Promise<unknown>; release:()=>void }|undefined;
   try {
    await gate;
    lock=await db.connect();await lock.query('BEGIN');await lock.query('SELECT id FROM accounts WHERE id=$1 FOR NO KEY UPDATE',[account]);
    releaseProvider();
    // The settle transaction is now blocked on the wallet lock: cancellation
    // during SQL must not prevent the known-usage settlement.
    let waiting=false;
    for(let i=0;i<200&&!waiting;i++) {
      waiting=!!(await db.query("SELECT 1 FROM pg_stat_activity WHERE wait_event_type='Lock' AND query LIKE 'SELECT%accounts%FOR UPDATE' AND pid<>pg_backend_pid() LIMIT 1")).rowCount;
      if(!waiting)await new Promise(r=>setTimeout(r,5));
    }
    assert.equal(waiting,true);controller.abort();
   } finally {releaseProvider();if(lock){await lock.query('ROLLBACK');lock.release();}}
   assert.equal((await result).status,200);await proxy.stop();
   assert.ok(BigInt((await db.query('SELECT balance_nano FROM wallets WHERE account_id=$1',[account])).rows[0].balance_nano)<BigInt(before));
  });
 }finally {await db.query(`DROP SCHEMA ${schema} CASCADE`);await db.end();}
});
