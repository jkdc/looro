import { randomUUID } from 'node:crypto';
import { transaction, type Database } from './db.js';
import { appendEntry, lockWallet } from './ledger.js';
import { ServiceError } from './errors.js';

/** Proxy attempt lifecycle with funded admission (change proxy-api-provider):
 * prepared == reserved — the attempt row and its wallet hold are written in
 * one transaction under the wallet lock, so two calls (or two processes)
 * cannot admit against the same available funds. Settlement debits actual
 * usage and releases the hold in the same entry; a rejected attempt releases
 * its hold; an unknown one keeps it for audited reconciliation. */

export async function prepareProxyAttempt(db: Database, value: { id: string; accountID: string; rateVersionID: string; estimateNano: bigint }) {
  await transaction(db, async sql => {
    const wallet = await lockWallet(sql, value.accountID, true);
    if (wallet.balance - wallet.reserved < value.estimateNano) throw new ServiceError('token_balance_exhausted', 402);
    await sql.query('INSERT INTO proxy_attempts(id,account_id,rate_version) VALUES($1,$2,$3)',
      [value.id, value.accountID, value.rateVersionID]);
    await sql.query('INSERT INTO reservations(id,account_id,idempotency_key,reserved_nano,rate_version) VALUES($1,$2,$3,$4,$5)',
      [value.id, value.accountID, `proxy:${value.id}`, value.estimateNano.toString(), value.rateVersionID]);
    await appendEntry(sql, value.accountID, `reservation:${value.id}`, 'reserve', 0n, value.estimateNano, value.rateVersionID);
  });
}
export async function markProxyDispatched(db: Database, id: string) {
  const result = await db.query("UPDATE proxy_attempts SET state='dispatched',updated_at=now() WHERE id=$1 AND state='prepared'", [id]);
  if (result.rowCount !== 1) throw new ServiceError('proxy_attempt_conflict', 409);
}

/** Releases an open hold inside a caller transaction; a missing or closed
 * hold is not an error (legacy attempts carry none, reconciliation may race). */
async function releaseHoldInTransaction(sql: Parameters<Parameters<typeof transaction>[1]>[0], id: string) {
  const hold = (await sql.query('SELECT * FROM reservations WHERE id=$1 FOR UPDATE', [id])).rows[0];
  if (!hold || hold.state !== 'open') return;
  await appendEntry(sql, hold.account_id as string, `proxy-release:${id}`, 'release', 0n, -BigInt(hold.reserved_nano as string), hold.rate_version as string);
  await sql.query("UPDATE reservations SET state='released' WHERE id=$1", [id]);
}

export async function finishProxyAttempt(db: Database, id: string, outcome: 'rejected' | 'unknown') {
  await transaction(db, async sql => {
    const result = await sql.query(`UPDATE proxy_attempts SET state=$2,updated_at=now() WHERE id=$1 AND
      (state='dispatched' OR (state='prepared' AND $2='rejected'))`, [id, outcome]);
    if (result.rowCount !== 1) throw new ServiceError('proxy_attempt_conflict', 409);
    // A rejection proves no provider work happened: return the hold. An
    // unknown outcome keeps it — the provider spend cannot be proven absent.
    if (outcome === 'rejected') await releaseHoldInTransaction(sql, id);
  });
}

export async function settleProxyAttempt(db: Database, value: { id: string; usage: { inputTokens: bigint; outputTokens: bigint }; estimateNano: bigint; costNano: bigint }) {
  return transaction(db, async sql => {
    // Account -> wallet -> attempt order matches the rest of the financial model.
    const owner = (await sql.query('SELECT account_id FROM proxy_attempts WHERE id=$1', [value.id])).rows[0];
    if (!owner) throw new ServiceError('proxy_attempt_conflict', 409);
    await lockWallet(sql, owner.account_id as string);
    const attempt = (await sql.query('SELECT * FROM proxy_attempts WHERE id=$1 FOR UPDATE', [value.id])).rows[0]!;
    if (attempt.state === 'settled') {
      const prior = (await sql.query('SELECT settled_nano FROM proxy_usage WHERE attempt_id=$1', [value.id])).rows[0];
      if (!prior) throw new ServiceError('proxy_attempt_conflict', 409);
      return { rateVersion: attempt.rate_version as string, nanoUSDSettled: String(prior.settled_nano) };
    }
    if (attempt.state !== 'dispatched') throw new ServiceError('proxy_attempt_conflict', 409);
    const hold = (await sql.query('SELECT * FROM reservations WHERE id=$1 FOR UPDATE', [value.id])).rows[0];
    // Settle at what the wallet can pay **without consuming the reservation
    // of another in-flight call**: balance minus the other holds. An overrun
    // above this call's own estimate is covered by its own hold and the free
    // balance, never by another call's backing; the confirmed-but-uncollected
    // difference stays visible as cost_nano > settled_nano in proxy_usage.
    const wallet = await lockWallet(sql, owner.account_id as string);
    const ownHold = hold && hold.state === 'open' ? BigInt(hold.reserved_nano as string) : 0n;
    const settleCap = wallet.balance - (wallet.reserved - ownHold);
    const settled = value.costNano > settleCap ? (settleCap > 0n ? settleCap : 0n) : value.costNano;
    const reservedDelta = hold && hold.state === 'open' ? -ownHold : 0n;
    const reference = `proxy:${value.id}`;
    // The combined settle entry debits actual usage and releases the hold in
    // one immutable row; a zero-cost settled call still releases the hold.
    if (settled > 0n || reservedDelta !== 0n)
      await appendEntry(sql, attempt.account_id as string, reference, 'settle', -settled, reservedDelta, attempt.rate_version as string | null);
    if (hold && hold.state === 'open')
      await sql.query("UPDATE reservations SET state='settled',actual_nano=$2 WHERE id=$1", [value.id, settled.toString()]);
    await sql.query(`INSERT INTO proxy_usage(id,account_id,rate_version,input_tokens,output_tokens,estimate_nano,cost_nano,settled_nano,ledger_reference,attempt_id)
      VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10)`, [randomUUID(), attempt.account_id, attempt.rate_version, value.usage.inputTokens.toString(), value.usage.outputTokens.toString(),
      value.estimateNano.toString(), value.costNano.toString(), settled.toString(), settled > 0n ? reference : null, value.id]);
    await sql.query("UPDATE proxy_attempts SET state='settled',updated_at=now() WHERE id=$1", [value.id]);
    return { rateVersion: attempt.rate_version as string, nanoUSDSettled: settled.toString() };
  });
}

/** Reconciliation core (single transaction step): validates that the attempt
 * is `unknown` — the only reconcilable state — and releases its open hold.
 * Called by the operator CLI, which owns the wallet lock order and the
 * audit write inside the same transaction. */
export async function reconcileUnknownHoldInTransaction(sql: Parameters<Parameters<typeof transaction>[1]>[0], attemptID: string) {
  const attempt = (await sql.query('SELECT account_id,state FROM proxy_attempts WHERE id=$1', [attemptID])).rows[0];
  if (!attempt) throw new ServiceError('proxy_attempt_not_found', 404);
  if (attempt.state !== 'unknown') throw new ServiceError('proxy_hold_not_open', 409);
  await lockWallet(sql, attempt.account_id as string);
  const hold = (await sql.query('SELECT * FROM reservations WHERE id=$1 FOR UPDATE', [attemptID])).rows[0];
  if (!hold || hold.state !== 'open') throw new ServiceError('proxy_hold_not_open', 409);
  await appendEntry(sql, hold.account_id as string, `proxy-release:${attemptID}`, 'release', 0n, -BigInt(hold.reserved_nano as string), hold.rate_version as string);
  await sql.query("UPDATE reservations SET state='released' WHERE id=$1", [attemptID]);
  return { attemptID, releasedNano: String(hold.reserved_nano) };
}

/** Reconciliation primitive (operator CLI only): releases the hold of an
 * attempt whose outcome stayed unknown. Never settles a guess and never
 * releases an attempt that is still prepared or in flight. */
export async function releaseProxyHold(db: Database, attemptID: string) {
  return transaction(db, sql => reconcileUnknownHoldInTransaction(sql, attemptID));
}
