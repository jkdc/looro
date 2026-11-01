import { randomUUID } from 'node:crypto';
import { transaction, type Database } from './db.js';
import { appendEntry, lockWallet } from './ledger.js';
import { ServiceError } from './errors.js';

export async function prepareProxyAttempt(db:Database, value:{id:string;accountID:string;rateVersionID:string}) {
  await db.query('INSERT INTO proxy_attempts(id,account_id,rate_version) VALUES($1,$2,$3)',[value.id,value.accountID,value.rateVersionID]);
}
export async function markProxyDispatched(db:Database,id:string) {
  const result=await db.query("UPDATE proxy_attempts SET state='dispatched',updated_at=now() WHERE id=$1 AND state='prepared'",[id]);
  if(result.rowCount!==1) throw new ServiceError('proxy_attempt_conflict',409);
}
export async function finishProxyAttempt(db:Database,id:string,outcome:'rejected'|'unknown') {
  const result=await db.query(`UPDATE proxy_attempts SET state=$2,updated_at=now() WHERE id=$1 AND
    (state='dispatched' OR (state='prepared' AND $2='rejected'))`,[id,outcome]);
  if(result.rowCount!==1) throw new ServiceError('proxy_attempt_conflict',409);
}
export async function settleProxyAttempt(db:Database,value:{id:string;usage:{inputTokens:bigint;outputTokens:bigint};estimateNano:bigint;costNano:bigint;settledNano:bigint}) {
  return transaction(db,async sql=>{
    // Account -> wallet -> attempt order matches the rest of the financial model.
    const owner=(await sql.query('SELECT account_id FROM proxy_attempts WHERE id=$1',[value.id])).rows[0];
    if(!owner) throw new ServiceError('proxy_attempt_conflict',409);
    await lockWallet(sql,owner.account_id);
    const attempt=(await sql.query('SELECT * FROM proxy_attempts WHERE id=$1 FOR UPDATE',[value.id])).rows[0]!;
    if(attempt.state==='settled') {
      const prior=(await sql.query('SELECT settled_nano FROM proxy_usage WHERE attempt_id=$1',[value.id])).rows[0];
      if(!prior) throw new ServiceError('proxy_attempt_conflict',409);
      return {rateVersion:attempt.rate_version as string,nanoUSDSettled:String(prior.settled_nano)};
    }
    if(attempt.state!=='dispatched') throw new ServiceError('proxy_attempt_conflict',409);
    const reference=`proxy:${value.id}`;
    if(value.settledNano>0n) await appendEntry(sql,attempt.account_id,reference,'settle',-value.settledNano,0n,attempt.rate_version);
    await sql.query(`INSERT INTO proxy_usage(id,account_id,rate_version,input_tokens,output_tokens,estimate_nano,cost_nano,settled_nano,ledger_reference,attempt_id)
      VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10)`,[randomUUID(),attempt.account_id,attempt.rate_version,value.usage.inputTokens.toString(),value.usage.outputTokens.toString(),
      value.estimateNano.toString(),value.costNano.toString(),value.settledNano.toString(),value.settledNano>0n?reference:null,value.id]);
    await sql.query("UPDATE proxy_attempts SET state='settled',updated_at=now() WHERE id=$1",[value.id]);
    return {rateVersion:attempt.rate_version as string,nanoUSDSettled:value.settledNano.toString()};
  });
}
