// Synthetic integration assertions, executed only in the isolated driver container.
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';import {createHash} from 'node:crypto';import {createRequire} from 'node:module';
import {preflight,sampleRequest,clientHeaders} from '/app/load/driver.mjs';
import {workloadTask} from '/app/load/workloads.mjs';
if(process.env.LOAD_ISOLATED!=='true'||process.env.BASE!=='https://caddy-load')throw Error('Resource fixture only');
const {Pool}=createRequire('/app/package.json')('pg');
const db=new Pool({connectionString:'postgresql://capacity:synthetic-load-only@database-load:5432/capacity_test'});
const fixture=JSON.parse(await readFile('/fixtures/manifest.json','utf8'));
const hashes=fixture.cohorts.new.map(t=>createHash('sha256').update(t).digest('hex'));
const countNew=async()=>Number((await db.query('SELECT count(*) FROM subscription_grants g JOIN auth_sessions s ON s.account_id=g.account_id WHERE s.token_hash=ANY($1::text[])',[hashes])).rows[0].count);
try{
 assert.equal(fixture.expectedAccounts,1201);assert.equal(await countNew(),0);
 await preflight(process.env,fixture);assert.equal(await countNew(),0);
 for(let i=0;i<20;i++)assert.equal((await sampleRequest(workloadTask('new',i,fixture),i,{base:process.env.BASE,proxyToken:fixture.proxyToken})).useful,true);
 assert.equal(await countNew(),20);
 for(const kind of ['guest','active','progress','exams'])assert.equal((await sampleRequest(workloadTask(kind,0,fixture),0,{base:process.env.BASE,proxyToken:fixture.proxyToken})).useful,true,kind);
 const r=await fetch('https://caddy-load/v1/progress',{headers:{authorization:`Bearer ${fixture.cohorts.claimed[0]}`,...clientHeaders(0,200,fixture.proxyToken)}});
 assert.equal(r.status,200);const p=await r.json();assert.equal(p.progress.length,1);assert.equal(p.progress[0].bestScore,80);
 const integrity=(await db.query(`SELECT (SELECT sum(balance_ms)::text FROM minute_wallets) balance,(SELECT sum(balance_delta_ms)::text FROM minute_entries) journal,(SELECT sum(balance_nano)::text FROM wallets) money,(SELECT sum(balance_delta_nano)::text FROM ledger) ledger`)).rows[0];
 assert.equal(integrity.balance,integrity.journal);assert.equal(integrity.money,integrity.ledger);
 console.log(JSON.stringify({type:'resource_contract_probe',result:'PASS',newGrants:20,preflightCohortIsolated:true,progressReadback:true,realExam:true,integrity}));
}finally{await db.end();}
