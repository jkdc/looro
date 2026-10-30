import { createFixtureToken } from './driver.mjs';
import { randomUUID, createHash } from 'node:crypto';
import { writeFile, chown, chmod } from 'node:fs/promises';
import { connectDatabase, transaction } from '../dist/src/db.js';
import { appendEntry } from '../dist/src/ledger.js';
const db=connectDatabase(process.env.DATABASE_URL);
try {
  const tokens=[];
  for(let i=0;i<200;i++) {
    const id=randomUUID(), token=createFixtureToken();tokens.push(token);
    await transaction(db,async sql=>{
      await sql.query('INSERT INTO accounts(id) VALUES($1)',[id]);
      await sql.query('INSERT INTO wallets(account_id) VALUES($1)',[id]);
      await sql.query('INSERT INTO minute_wallets(account_id) VALUES($1)',[id]);
      await sql.query('INSERT INTO entitlements(account_id,unlocked_at) VALUES($1,now())',[id]);
      await sql.query("INSERT INTO auth_sessions(id,account_id,token_hash,expires_at) VALUES($1,$2,$3,now()+interval '4 hours')",[randomUUID(),id,createHash('sha256').update(token).digest('hex')]);
      await appendEntry(sql,id,`seed:${id}`,'purchase',1_000_000_000n,0n);
    });
  }
  await writeFile('/fixtures/manifest.json',JSON.stringify({runId:process.env.LOAD_RUN_ID,tokens,proxyToken:process.env.ACCOUNTS_PROXY_TOKEN}),{mode:0o600});
  await chown('/fixtures/manifest.json',1000,1000);await chmod('/fixtures/manifest.json',0o600);
} finally {await db.end();}
