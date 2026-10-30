import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { connectDatabase, transaction } from '../src/db.js';
import { migrate } from '../src/migrate.js';
import { appendEntry } from '../src/ledger.js';
import { TokenProxy, tokenProxyConfig } from '../src/token-proxy.js';
const env={PROXY_PROVIDER_URL:'http://stub/v1',PROXY_PROVIDER_KEY:'test'};
test('proxy capacity configuration defaults and bounds reject empty and invalid numbers',()=>{
  const c=tokenProxyConfig(env) as any;
  assert.equal(c.maxInFlight,10);assert.equal(c.timeoutMs,30000);assert.equal(c.maxResponseBytes,1048576);
  for(const [key,invalid] of Object.entries({PROXY_MAX_IN_FLIGHT:['','0','1001','1.1'],PROXY_TIMEOUT_MS:['','0','300001'],PROXY_MAX_RESPONSE_BYTES:['1023','16777217'],PROXY_MAX_TOKENS:['','NaN','0']}))
    for(const value of invalid) assert.throws(()=>tokenProxyConfig({...env,[key]:value}),key);
});
const url=process.env.TEST_DATABASE_URL;
if(url&&!new URL(url).pathname.endsWith('_test'))throw Error('Dedicated database required');
const payload=(extra='')=>({choices:[{message:{content:extra}}],usage:{prompt_tokens:10,completion_tokens:5}});
const config={providerURL:'http://stub',providerKey:'test',maxTokens:128,maxConcurrentPerAccount:2};
test('proxy bounds work across accounts and records failed external outcomes',{skip:!url},async t=>{
  const schema=`capacity_${randomUUID().replaceAll('-','')}`,u=new URL(url!);u.searchParams.set('options',`-c search_path=${schema}`);
  const db=connectDatabase(u.toString());
  try {
    await db.query(`CREATE SCHEMA ${schema}`);await migrate(db);
    const accounts=[randomUUID(),randomUUID()];
    for(const a of accounts){await db.query('INSERT INTO accounts(id) VALUES($1)',[a]);await db.query('INSERT INTO wallets(account_id) VALUES($1)',[a]);await transaction(db,sql=>appendEntry(sql,a,`seed:${a}`,'purchase',1000000n,0n));}
    await t.test('global admission rejects another account before provider dispatch',async()=>{
      let release!:()=>void,entered!:()=>void;const gate=new Promise<void>(r=>entered=r);let calls=0;
      const transport=async()=>{calls++;if(calls===1){entered();await new Promise<void>(r=>release=r);}return Response.json(payload());};
      const proxy=new TokenProxy(db,{...config,maxInFlight:1} as any,transport as typeof fetch);
      const first=proxy.relay(accounts[0]!,{});await gate;
      try {await assert.rejects(proxy.relay(accounts[1]!,{}),(e:any)=>e.code==='proxy_busy'&&e.status===503);}
      finally {release();await first;}
      assert.equal(calls,1);
    });
    await t.test('provider timeout aborts transport and keeps unknown instead of a fabricated debit',async()=>{
      const before=(await db.query('SELECT balance_nano FROM wallets WHERE account_id=$1',[accounts[0]])).rows[0].balance_nano;
      let aborted=false;
      const transport=async(_url:unknown,options:any)=>new Promise<Response>((resolve,reject)=>{
        const timer=setTimeout(()=>resolve(Response.json(payload())),200);
        options.signal?.addEventListener('abort',()=>{aborted=true;clearTimeout(timer);reject(options.signal.reason);},{once:true});
      });
      const proxy=new TokenProxy(db,{...config,timeoutMs:100} as any,transport as typeof fetch);
      await assert.rejects(proxy.relay(accounts[0]!,{}),(e:any)=>e.code==='provider_timeout'&&e.status===504);
      assert.equal(aborted,true);
      assert.equal((await db.query("SELECT count(*) FROM proxy_attempts WHERE account_id=$1 AND state='unknown'",[accounts[0]])).rows[0].count,'1');
      assert.equal((await db.query('SELECT balance_nano FROM wallets WHERE account_id=$1',[accounts[0]])).rows[0].balance_nano,before);
    });
    await t.test('response size is bounded without Content-Length and slots release on failure',async()=>{
      let large=true;const proxy=new TokenProxy(db,{...config,maxInFlight:1,maxResponseBytes:1024} as any,(async()=>Response.json(payload(large?'x'.repeat(2048):'ok'))) as typeof fetch);
      await assert.rejects(proxy.relay(accounts[1]!,{}),(e:any)=>e.code==='provider_error');
      large=false;assert.equal((await proxy.relay(accounts[1]!,{})).status,200);
    });
    await t.test('shutdown aborts inflight transport and refuses new admissions',async()=>{
      let enter!:()=>void;const gate=new Promise<void>(r=>enter=r);
      const proxy=new TokenProxy(db,config,(async(_url:unknown,o:any)=>{enter();return new Promise((_resolve,reject)=>o.signal?.addEventListener('abort',()=>reject(o.signal.reason),{once:true}));}) as typeof fetch);
      assert.equal(typeof (proxy as any).stop,'function');
      const pending=proxy.relay(accounts[0]!,{});const rejected=assert.rejects(pending);await gate;
      await (proxy as any).stop();await rejected;
      await assert.rejects(proxy.relay(accounts[0]!,{}),(e:any)=>e.code==='proxy_unavailable');
    });
  } finally {await db.query(`DROP SCHEMA ${schema} CASCADE`);await db.end();}
});
