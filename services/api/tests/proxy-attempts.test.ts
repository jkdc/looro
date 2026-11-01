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
    // Preparation reserves the estimate under the wallet lock: the hold shows
    // in reserved_nano and in the immutable ledger, never in the balance.
    const id=randomUUID();await a.prepareProxyAttempt(db,{id,accountID,rateVersionID,estimateNano:10n});await a.markProxyDispatched(db,id);
    const reserved=(await db.query('SELECT reserved_nano,balance_nano FROM wallets WHERE account_id=$1',[accountID])).rows[0];
    assert.equal(reserved.reserved_nano,'10');
    assert.equal(reserved.balance_nano,'10000');
    assert.equal((await db.query("SELECT count(*) FROM ledger WHERE kind='reserve' AND reference=$1",[`reservation:${id}`])).rows[0].count,'1');
    const args={id,usage:{inputTokens:10n,outputTokens:5n},estimateNano:10n,costNano:5n};
    await Promise.all([a.settleProxyAttempt(db,args),a.settleProxyAttempt(db,args)]);
    // One combined settle entry: debits actual usage and releases the hold.
    assert.equal((await db.query('SELECT balance_nano,reserved_nano FROM wallets WHERE account_id=$1',[accountID])).rows[0].balance_nano,'9995');
    assert.equal((await db.query('SELECT reserved_nano FROM wallets WHERE account_id=$1',[accountID])).rows[0].reserved_nano,'0');
    assert.equal((await db.query("SELECT count(*) FROM ledger WHERE reference=$1",[`proxy:${id}`])).rows[0].count,'1');
    assert.equal((await db.query('SELECT count(*) FROM proxy_usage WHERE attempt_id=$1',[id])).rows[0].count,'1');
    assert.equal((await db.query('SELECT state FROM proxy_attempts WHERE id=$1',[id])).rows[0].state,'settled');
    assert.equal((await db.query("SELECT state FROM reservations WHERE id=$1",[id])).rows[0].state,'settled');
    await assert.rejects(a.finishProxyAttempt(db,id,'unknown'));
    // An unknown outcome keeps its hold: the provider spend cannot be proven
    // absent, so reconciliation decides — never an automatic release.
    const uncertain=randomUUID();await a.prepareProxyAttempt(db,{id:uncertain,accountID,rateVersionID,estimateNano:10n});await a.markProxyDispatched(db,uncertain);await a.finishProxyAttempt(db,uncertain,'unknown');
    assert.equal((await db.query("SELECT state FROM reservations WHERE id=$1",[uncertain])).rows[0].state,'open');
    await assert.rejects(a.settleProxyAttempt(db,{...args,id:uncertain}));
    // Reconciliation releases the held funds exactly once.
    const released=await a.releaseProxyHold(db,uncertain);
    assert.equal(released.releasedNano,'10');
    assert.equal((await db.query("SELECT state FROM reservations WHERE id=$1",[uncertain])).rows[0].state,'released');
    assert.equal((await db.query('SELECT reserved_nano FROM wallets WHERE account_id=$1',[accountID])).rows[0].reserved_nano,'0');
    await assert.rejects(a.releaseProxyHold(db,uncertain));
    const failed=randomUUID();await a.prepareProxyAttempt(db,{id:failed,accountID,rateVersionID,estimateNano:10n});await a.markProxyDispatched(db,failed);
    // Force a real SQL constraint failure after entering finalization; transaction must roll back all effects.
    await assert.rejects(a.settleProxyAttempt(db,{...args,id:failed,usage:{inputTokens:-1n,outputTokens:0n}}));
    assert.equal((await db.query('SELECT state FROM proxy_attempts WHERE id=$1',[failed])).rows[0].state,'dispatched');
    assert.equal((await db.query("SELECT count(*) FROM ledger WHERE reference=$1",[`proxy:${failed}`])).rows[0].count,'0');
    assert.equal((await db.query('SELECT balance_nano FROM wallets WHERE account_id=$1',[accountID])).rows[0].balance_nano,'9995');
    // A rejected attempt (proven not dispatched work) releases its hold. The
    // failed attempt above keeps its own hold: it never reached a terminal
    // state, so it stays dispatched+reserved for reconciliation.
    const rejected=randomUUID();await a.prepareProxyAttempt(db,{id:rejected,accountID,rateVersionID,estimateNano:10n});await a.markProxyDispatched(db,rejected);await a.finishProxyAttempt(db,rejected,'rejected');
    assert.equal((await db.query("SELECT state FROM reservations WHERE id=$1",[rejected])).rows[0].state,'released');
    assert.equal((await db.query('SELECT reserved_nano FROM wallets WHERE account_id=$1',[accountID])).rows[0].reserved_nano,'10');
  } finally {await db.query(`DROP SCHEMA ${schema} CASCADE`);await db.end();}
});
test('funded admission refuses a second concurrent reservation the balance cannot cover',{skip:!url},async()=>{
  const schema=`attempt_${randomUUID().replaceAll('-','')}`, u=new URL(url!);u.searchParams.set('options',`-c search_path=${schema}`);
  const db=connectDatabase(u.toString());
  try {
    await db.query(`CREATE SCHEMA ${schema}`);await migrate(db);
    // @ts-ignore New orchestration module is introduced by this delivery.
    const a=await import('../src/proxy-attempts.js');
    const accountID=randomUUID(),rateVersionID='2026-09-minimax-m';
    await db.query('INSERT INTO accounts(id) VALUES($1)',[accountID]);await db.query('INSERT INTO wallets(account_id) VALUES($1)',[accountID]);
    await transaction(db,sql=>appendEntry(sql,accountID,`seed:${accountID}`,'purchase',1000n,0n));
    // Two reservations of 600n against a 1000n balance: the unlocked read
    // would admit both; the wallet-locked admission admits exactly one.
    const results=await Promise.allSettled([
      a.prepareProxyAttempt(db,{id:randomUUID(),accountID,rateVersionID,estimateNano:600n}),
      a.prepareProxyAttempt(db,{id:randomUUID(),accountID,rateVersionID,estimateNano:600n}),
    ]);
    const admitted=results.filter(r=>r.status==='fulfilled').length, refused=results.filter(r=>r.status==='rejected').length;
    assert.equal(admitted,1);
    assert.equal(refused,1);
    assert.equal((await db.query('SELECT reserved_nano FROM wallets WHERE account_id=$1',[accountID])).rows[0].reserved_nano,'600');
    assert.equal((await db.query("SELECT count(*) FROM ledger WHERE kind='reserve'")).rows[0].count,'1');
  } finally {await db.query(`DROP SCHEMA ${schema} CASCADE`);await db.end();}
});
test('a settlement cannot consume another call\'s reservation',{skip:!url},async()=>{
  const schema=`attempt_${randomUUID().replaceAll('-','')}`, u=new URL(url!);u.searchParams.set('options',`-c search_path=${schema}`);
  const db=connectDatabase(u.toString());
  try {
    await db.query(`CREATE SCHEMA ${schema}`);await migrate(db);
    // @ts-ignore New orchestration module is introduced by this delivery.
    const a=await import('../src/proxy-attempts.js');
    const accountID=randomUUID(),rateVersionID='2026-09-minimax-m';
    await db.query('INSERT INTO accounts(id) VALUES($1)',[accountID]);await db.query('INSERT INTO wallets(account_id) VALUES($1)',[accountID]);
    await transaction(db,sql=>appendEntry(sql,accountID,`seed:${accountID}`,'purchase',100n,0n));
    // A holds 40, B holds 60. A's real cost (70) exceeds its own hold: the
    // settle must stop at what the wallet holds minus B's backing (40), so B's
    // already-dispatched work stays funded. Confirmed cost over the settle cap
    // is visible as cost_nano > settled_nano in proxy_usage (B3).
    const holdA=randomUUID(),holdB=randomUUID();
    await a.prepareProxyAttempt(db,{id:holdA,accountID,rateVersionID,estimateNano:40n});
    await a.prepareProxyAttempt(db,{id:holdB,accountID,rateVersionID,estimateNano:60n});
    await a.markProxyDispatched(db,holdA);await a.markProxyDispatched(db,holdB);
    const metering=await a.settleProxyAttempt(db,{id:holdA,usage:{inputTokens:1n,outputTokens:1n},estimateNano:40n,costNano:70n});
    assert.equal(metering.nanoUSDSettled,'40');
    const wallet=(await db.query('SELECT balance_nano,reserved_nano FROM wallets WHERE account_id=$1',[accountID])).rows[0];
    assert.equal(wallet.balance_nano,'60');   // 100 - 40 settled
    assert.equal(wallet.reserved_nano,'60');  // B's backing untouched
    const usage=(await db.query('SELECT cost_nano,settled_nano FROM proxy_usage WHERE attempt_id=$1',[holdA])).rows[0];
    assert.equal(usage.cost_nano,'70');       // confirmed cost stays visible
    assert.equal(usage.settled_nano,'40');    // what the wallet could pay without B's backing
    // B still settles afterwards and never goes below zero.
    await a.settleProxyAttempt(db,{id:holdB,usage:{inputTokens:1n,outputTokens:1n},estimateNano:60n,costNano:60n});
    const after=(await db.query('SELECT balance_nano,reserved_nano FROM wallets WHERE account_id=$1',[accountID])).rows[0];
    assert.equal(after.balance_nano,'0');
    assert.equal(after.reserved_nano,'0');
  } finally {await db.query(`DROP SCHEMA ${schema} CASCADE`);await db.end();}
});
test('reconciliation only releases unknown attempts',{skip:!url},async()=>{
  const schema=`attempt_${randomUUID().replaceAll('-','')}`, u=new URL(url!);u.searchParams.set('options',`-c search_path=${schema}`);
  const db=connectDatabase(u.toString());
  try {
    await db.query(`CREATE SCHEMA ${schema}`);await migrate(db);
    // @ts-ignore New orchestration module is introduced by this delivery.
    const a=await import('../src/proxy-attempts.js');
    const accountID=randomUUID(),rateVersionID='2026-09-minimax-m';
    await db.query('INSERT INTO accounts(id) VALUES($1)',[accountID]);await db.query('INSERT INTO wallets(account_id) VALUES($1)',[accountID]);
    await transaction(db,sql=>appendEntry(sql,accountID,`seed:${accountID}`,'purchase',10000n,0n));
    // A prepared (not dispatched) and a dispatched in-flight attempt hold
    // funds for work that may still be running: releasing them is not
    // reconciliation (B5).
    let held='0';
    for (const state of ['prepared','dispatched'] as const) {
      const id=randomUUID();
      await a.prepareProxyAttempt(db,{id,accountID,rateVersionID,estimateNano:100n});
      if (state==='dispatched') await a.markProxyDispatched(db,id);
      await assert.rejects(a.releaseProxyHold(db,id),/proxy_hold_not_open/);
      assert.equal((await db.query("SELECT state FROM reservations WHERE id=$1",[id])).rows[0].state,'open');
      held='200'; // both holds survive the refusals
    }
    assert.equal((await db.query('SELECT reserved_nano FROM wallets WHERE account_id=$1',[accountID])).rows[0].reserved_nano,held);
    // An unknown attempt releases exactly once.
    const unknown=randomUUID();
    await a.prepareProxyAttempt(db,{id:unknown,accountID,rateVersionID,estimateNano:100n});
    await a.markProxyDispatched(db,unknown);await a.finishProxyAttempt(db,unknown,'unknown');
    assert.equal((await a.releaseProxyHold(db,unknown)).releasedNano,'100');
  } finally {await db.query(`DROP SCHEMA ${schema} CASCADE`);await db.end();}
});
