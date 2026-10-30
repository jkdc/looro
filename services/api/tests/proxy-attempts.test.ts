import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { connectDatabase, transaction } from '../src/db.js';
import { migrate } from '../src/migrate.js';
import { appendEntry } from '../src/ledger.js';
const url=process.env.TEST_DATABASE_URL;
if(url && !new URL(url).pathname.endsWith('_test')) throw Error('Dedicated test database required');
test('proxy attempts persist uncertainty and settle concurrently exactly once',{skip:!url},async()=>{
  const schema=`attempt_${randomUUID().replaceAll('-','')}`, u=new URL(url!);u.searchParams.set('options',`-c search_path=${schema}`);
  const db=connectDatabase(u.toString());
  try {
    await db.query(`CREATE SCHEMA ${schema}`);await migrate(db);
    assert.ok((await db.query("SELECT to_regclass('proxy_attempts') AS name")).rows[0].name,'durable attempt table is required');
    // @ts-ignore New orchestration module is introduced by this delivery.
    const a=await import('../src/proxy-attempts.js');
    const accountID=randomUUID(),rateVersionID='2026-09-minimax-m';
    await db.query('INSERT INTO accounts(id) VALUES($1)',[accountID]);await db.query('INSERT INTO wallets(account_id) VALUES($1)',[accountID]);
    await transaction(db,sql=>appendEntry(sql,accountID,`seed:${accountID}`,'purchase',10000n,0n));
    const id=randomUUID();await a.prepareProxyAttempt(db,{id,accountID,rateVersionID});await a.markProxyDispatched(db,id);
    const args={id,usage:{inputTokens:10n,outputTokens:5n},estimateNano:10n,costNano:5n,settledNano:5n};
    await Promise.all([a.settleProxyAttempt(db,args),a.settleProxyAttempt(db,args)]);
    assert.equal((await db.query('SELECT balance_nano FROM wallets WHERE account_id=$1',[accountID])).rows[0].balance_nano,'9995');
    assert.equal((await db.query("SELECT count(*) FROM ledger WHERE reference=$1",[`proxy:${id}`])).rows[0].count,'1');
    assert.equal((await db.query('SELECT count(*) FROM proxy_usage WHERE attempt_id=$1',[id])).rows[0].count,'1');
    assert.equal((await db.query('SELECT state FROM proxy_attempts WHERE id=$1',[id])).rows[0].state,'settled');
    await assert.rejects(a.finishProxyAttempt(db,id,'unknown'));
    const uncertain=randomUUID();await a.prepareProxyAttempt(db,{id:uncertain,accountID,rateVersionID});await a.markProxyDispatched(db,uncertain);await a.finishProxyAttempt(db,uncertain,'unknown');
    await assert.rejects(a.settleProxyAttempt(db,{...args,id:uncertain}));
    const failed=randomUUID();await a.prepareProxyAttempt(db,{id:failed,accountID,rateVersionID});await a.markProxyDispatched(db,failed);
    // Force a real SQL constraint failure after entering finalization; transaction must roll back all effects.
    await assert.rejects(a.settleProxyAttempt(db,{...args,id:failed,usage:{inputTokens:-1n,outputTokens:0n}}));
    assert.equal((await db.query('SELECT state FROM proxy_attempts WHERE id=$1',[failed])).rows[0].state,'dispatched');
    assert.equal((await db.query('SELECT count(*) FROM ledger WHERE reference=$1',[`proxy:${failed}`])).rows[0].count,'0');
    assert.equal((await db.query('SELECT balance_nano FROM wallets WHERE account_id=$1',[accountID])).rows[0].balance_nano,'9995');
  } finally {await db.query(`DROP SCHEMA ${schema} CASCADE`);await db.end();}
});
