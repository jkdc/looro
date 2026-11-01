import {test} from 'node:test';import assert from 'node:assert/strict';
import {connectDatabase,transaction,type Database} from '../src/db.js';
test('an idle PostgreSQL disconnect does not crash the process or log connection secrets',async()=>{
  const db=connectDatabase('postgresql://fixture:never-log-password@localhost/unused_test');
  const output:string[]=[];const original=console.error;console.error=(value:unknown)=>output.push(String(value));
  try{
    assert.doesNotThrow(()=>db.emit('error',Object.assign(new Error('never-log-password private connection details'),{code:'57P01'})));
    assert.equal(output.length,1);assert.equal(JSON.parse(output[0]!).event,'database_idle_client_error');
    assert.ok(!output.join('').includes('never-log-password'));
  }finally{console.error=original;await db.end();}
});
test('query failure still propagates through the transaction and rolls back/releases',async()=>{
  const calls:string[]=[];const failure=Object.assign(new Error('query failed'),{code:'57P01'});
  const db={connect:async()=>({query:async(sql:string)=>{calls.push(sql);if(sql==='SELECT broken')throw failure;return {rows:[]};},release:()=>calls.push('release')})} as unknown as Database;
  await assert.rejects(transaction(db,async sql=>sql.query('SELECT broken')),error=>error===failure);
  assert.deepEqual(calls,['BEGIN','SELECT broken','ROLLBACK','release']);
});
