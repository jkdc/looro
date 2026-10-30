import { createFixtureToken } from './driver.mjs';
import { fixtureLayout } from './workloads.mjs';
import { randomUUID, createHash } from 'node:crypto';
import { writeFile, chown, chmod } from 'node:fs/promises';
import { connectDatabase, transaction } from '../dist/src/db.js';
import { appendEntry } from '../dist/src/ledger.js';
const db=connectDatabase(process.env.DATABASE_URL);
try {
  const resource=process.env.LOAD_RESOURCE_FIXTURE==='true';
  const layout=fixtureLayout(resource,Number(process.env.LOAD_NEW_ACCOUNTS??1000)),tokens=[],cohorts={};
  for(const role of layout) {
    const id=randomUUID(), token=createFixtureToken();tokens.push(token);
    if(resource)(cohorts[role]??=[]).push(token);
    await transaction(db,async sql=>{
      await sql.query('INSERT INTO accounts(id,is_guest) VALUES($1,$2)',[id,role==='guest']);
      await sql.query('INSERT INTO wallets(account_id) VALUES($1)',[id]);
      await sql.query('INSERT INTO minute_wallets(account_id) VALUES($1)',[id]);
      await sql.query('INSERT INTO entitlements(account_id,unlocked_at) VALUES($1,now())',[id]);
      await sql.query("INSERT INTO auth_sessions(id,account_id,token_hash,expires_at) VALUES($1,$2,$3,now()+$4::interval)",[randomUUID(),id,createHash('sha256').update(token).digest('hex'),resource?'8 hours':'4 hours']);
      await appendEntry(sql,id,`seed:${id}`,'purchase',1_000_000_000n,0n);
      if(role==='active')await sql.query(`INSERT INTO subscriptions(id,account_id,tier,status,store,store_reference,period_start,period_end)
        VALUES($1,$2,'standard','active','stripe',$3,now(),now()+interval '30 days')`,[randomUUID(),id,`synthetic-${id}`]);
    });
  }
  await writeFile('/fixtures/manifest.json',JSON.stringify({runId:process.env.LOAD_RUN_ID,tokens,...(resource?{cohorts,expectedAccounts:tokens.length}:{}),proxyToken:process.env.ACCOUNTS_PROXY_TOKEN}),{mode:0o600});
  await chown('/fixtures/manifest.json',1000,1000);await chmod('/fixtures/manifest.json',0o600);
} finally {await db.end();}
