import { randomUUID } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import type { PoolClient } from 'pg';
import { connectDatabase, transaction, type Database } from './db.js';
import { ServiceError } from './errors.js';
import { appendEntry, lockWallet } from './ledger.js';
import { isProxyPurpose } from './proxy-policy.js';
import { releaseProxyHold } from './proxy-attempts.js';
import { setSubscription } from './proxy-quota.js';

// Local operator process only (same standing as minutes-admin): assignment of
// proxy unlock and wallet credit without a store purchase. There is
// deliberately no HTTP endpoint that creates unlock or journal state.
// Every mutation carries an actor and a reason and writes token_admin_audit.

const isUUID = (value: unknown): value is string =>
  typeof value === 'string' && /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i.test(value);
const identifier = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,199}$/;
/** Same ceiling as pack purchase quotes: 1,000,000 USD in nanoUSD. */
const MAX_GRANT_NANO = 1_000_000_000_000_000n;
function auditText(value: unknown): asserts value is string {
  if (typeof value !== 'string' || value.trim().length < 3 || value.length > 200 || /[\x00-\x1f]/.test(value))
    throw new ServiceError('operator_and_reason_required');
}
async function memberAccount(sql: PoolClient, accountID: string) {
  const wallet = await lockWallet(sql, accountID, true);
  // Guests are transitional installation identities; campaign grants target
  // signed-in member accounts only.
  if (wallet.isGuest) throw new ServiceError('campaign_grant_requires_member', 403);
  return wallet;
}
async function audit(sql: PoolClient, action: 'grant_unlock' | 'revoke_unlock' | 'grant_balance' | 'set_purpose_route' | 'grant_quota' | 'release_hold',
  accountID: string | null, actor: string, reason: string, reference: string | null, detail: unknown) {
  await sql.query(`INSERT INTO token_admin_audit(id,actor,reason,action,account_id,reference,detail) VALUES($1,$2,$3,$4,$5,$6,$7)`,
    [randomUUID(), actor, reason, action, accountID, reference, JSON.stringify(detail ?? {})]);
}

export interface TokenGrantInput { accountID: string; actor: string; reason: string }

/** Campaign unlock: same set-once shape as the purchase path — an active
 * unlock (paid or campaign) is never overwritten, but a row cleared by a
 * refund can be re-granted. `unlock_order_id` stays NULL: no store order owns
 * it, so only the paid refund machinery and revokeUnlock touch it. */
export async function grantUnlock(db: Database, input: TokenGrantInput) {
  auditText(input.actor); auditText(input.reason);
  if (!isUUID(input.accountID)) throw new ServiceError('invalid_request');
  return transaction(db, async sql => {
    await memberAccount(sql, input.accountID);
    const existing = (await sql.query('SELECT unlocked_at,unlock_source FROM entitlements WHERE account_id=$1 FOR UPDATE',
      [input.accountID])).rows[0];
    if (existing?.unlocked_at) return { accountID: input.accountID, unlocked: true, changed: false, unlockSource: existing.unlock_source };
    await sql.query(`INSERT INTO entitlements(account_id,unlocked_at,unlock_source) VALUES($1,now(),'campaign')
      ON CONFLICT (account_id) DO UPDATE SET unlocked_at=EXCLUDED.unlocked_at,unlock_source=EXCLUDED.unlock_source,unlock_order_id=NULL
      WHERE entitlements.unlocked_at IS NULL`, [input.accountID]);
    await audit(sql, 'grant_unlock', input.accountID, input.actor, input.reason, null,
      { regrantAfterRevoke: Boolean(existing) });
    return { accountID: input.accountID, unlocked: true, changed: true, unlockSource: 'campaign' };
  });
}

/** Revokes only an operator-granted unlock. A paid unlock clears exclusively
 * through its own refund/void machinery, never from the CLI. */
export async function revokeUnlock(db: Database, input: TokenGrantInput) {
  auditText(input.actor); auditText(input.reason);
  if (!isUUID(input.accountID)) throw new ServiceError('invalid_request');
  return transaction(db, async sql => {
    await memberAccount(sql, input.accountID);
    const cleared = await sql.query(`UPDATE entitlements SET unlocked_at=NULL,unlock_source=NULL
      WHERE account_id=$1 AND unlocked_at IS NOT NULL AND unlock_source='campaign'`, [input.accountID]);
    if (!cleared.rowCount) throw new ServiceError('campaign_unlock_not_found', 404);
    await audit(sql, 'revoke_unlock', input.accountID, input.actor, input.reason, null, {});
    return { accountID: input.accountID, unlocked: false, changed: true };
  });
}

export interface BalanceGrantInput extends TokenGrantInput {
  /** Operator-chosen unique grant id; replays with the same id are no-ops. */
  grantID: string;
  /** Decimal-string nanoUSD, 0 < amount ≤ 1,000,000 USD. */
  creditNanoUSD: string;
}

/** Wallet credit through the immutable ledger ('purchase' kind, sandbox delta
 * zero): operator-assigned spendable balance at the token proxy. It is not
 * store cash, so it does not by itself satisfy the cash-provenance gates of
 * the paid voice and helper paths. The unique ledger reference makes a replay
 * of the same grantID idempotent and a conflicting reuse a 409. */
export async function grantBalance(db: Database, input: BalanceGrantInput) {
  auditText(input.actor); auditText(input.reason);
  if (!isUUID(input.accountID) || typeof input.grantID !== 'string' || !identifier.test(input.grantID) ||
    typeof input.creditNanoUSD !== 'string' || !/^\d+$/.test(input.creditNanoUSD)) throw new ServiceError('invalid_token_grant');
  const credit = BigInt(input.creditNanoUSD);
  if (credit <= 0n || credit > MAX_GRANT_NANO) throw new ServiceError('invalid_token_grant');
  const reference = `campaign-grant:${input.grantID}`;
  return transaction(db, async sql => {
    await memberAccount(sql, input.accountID);
    const rate = ((await sql.query('SELECT id FROM rate_versions ORDER BY valid_from DESC LIMIT 1')).rows[0]?.id ?? null) as string | null;
    const applied = await appendEntry(sql, input.accountID, reference, 'purchase', credit, 0n, rate);
    if (applied) await audit(sql, 'grant_balance', input.accountID, input.actor, input.reason, reference,
      { grantID: input.grantID, creditNanoUSD: credit.toString() });
    return { accountID: input.accountID, grantID: input.grantID, reference, creditNanoUSD: credit.toString(), applied };
  });
}

export interface TokenAccountSummary {
  accountID: string; createdAt: string; deleted: boolean; isGuest: boolean;
  wallet: { balanceNanoUSD: string; reservedNanoUSD: string; sandboxNanoUSD: string; cashProvenanceVerified: boolean } | null;
  entitlement: { unlocked: boolean; unlockSource: string | null; unlockOrderID: string | null; unlockedAt: string | null };
  usage30d: { calls: number; inputTokens: number; outputTokens: number; settledNanoUSD: string };
}

/** Read-only operator view: wallet, unlock state and 30-day proxy usage. */
export async function tokenAccountSummary(db: Database, accountID: string): Promise<TokenAccountSummary> {
  if (!isUUID(accountID)) throw new ServiceError('invalid_request');
  const account = (await db.query('SELECT deleted_at,is_guest,created_at FROM accounts WHERE id=$1', [accountID])).rows[0];
  if (!account) throw new ServiceError('account_not_found', 404);
  const wallet = (await db.query('SELECT balance_nano,reserved_nano,sandbox_balance_nano,cash_provenance_verified FROM wallets WHERE account_id=$1',
    [accountID])).rows[0];
  const entitlement = (await db.query('SELECT unlocked_at,unlock_source,unlock_order_id FROM entitlements WHERE account_id=$1',
    [accountID])).rows[0];
  const usage = (await db.query(`SELECT count(*)::int AS calls, COALESCE(sum(input_tokens),0)::bigint AS input_tokens,
    COALESCE(sum(output_tokens),0)::bigint AS output_tokens, COALESCE(sum(settled_nano),0)::bigint AS settled_nano
    FROM proxy_usage WHERE account_id=$1 AND created_at >= now() - interval '30 days'`, [accountID])).rows[0];
  return {
    accountID, createdAt: new Date(account.created_at).toISOString(), deleted: Boolean(account.deleted_at), isGuest: Boolean(account.is_guest),
    wallet: wallet ? { balanceNanoUSD: BigInt(wallet.balance_nano).toString(), reservedNanoUSD: BigInt(wallet.reserved_nano).toString(),
      sandboxNanoUSD: BigInt(wallet.sandbox_balance_nano).toString(), cashProvenanceVerified: Boolean(wallet.cash_provenance_verified) } : null,
    entitlement: { unlocked: Boolean(entitlement?.unlocked_at), unlockSource: entitlement?.unlock_source ?? null,
      unlockOrderID: entitlement?.unlock_order_id ?? null,
      unlockedAt: entitlement?.unlocked_at ? new Date(entitlement.unlocked_at).toISOString() : null },
    usage30d: { calls: Number(usage.calls), inputTokens: Number(usage.input_tokens), outputTokens: Number(usage.output_tokens),
      settledNanoUSD: usage.settled_nano.toString() }
  };
}

// --- Purpose routing, subscription quota and hold reconciliation (proxy-api-provider) ---

export interface RouteUpdateInput { actor: string; reason: string; purpose: string; model: string; cap: number }

/** Operator routing decision: which model (and output cap) serves a purpose.
 * Audited; no HTTP route mutates routing. */
export async function setPurposeRoute(db: Database, input: RouteUpdateInput) {
  auditText(input.actor); auditText(input.reason);
  if (!isProxyPurpose(input.purpose) || typeof input.model !== 'string' || input.model.trim().length === 0 ||
      input.model.length > 128 || !Number.isSafeInteger(input.cap) || input.cap < 64 || input.cap > 65536)
    throw new ServiceError('invalid_request');
  return transaction(db, async sql => {
    const updated = await sql.query(`UPDATE proxy_purpose_routes SET model=$2,output_cap=$3,updated_at=now()
      WHERE purpose=$1`, [input.purpose, input.model.trim(), input.cap]);
    if (!updated.rowCount) throw new ServiceError('purpose_route_not_found', 404);
    await audit(sql, 'set_purpose_route', null, input.actor, input.reason, null,
      { purpose: input.purpose, model: input.model.trim(), outputCap: input.cap });
    return { purpose: input.purpose, model: input.model.trim(), outputCap: input.cap };
  });
}

export async function listPurposeRoutes(db: Database) {
  const rows = (await db.query(`SELECT purpose,model,display_name,output_cap,enabled FROM proxy_purpose_routes ORDER BY purpose`)).rows;
  return { routes: rows };
}

export interface QuotaGrantInput extends TokenGrantInput {
  plan: string; days: number | null; dailyInputTokens: string; dailyOutputTokens: string;
}

/** Pilot subscription assignment: plan window + daily token allowance. The
 * daily job converts the allowance to ledger credit; this only sets the
 * plan. */
export async function grantQuota(db: Database, input: QuotaGrantInput) {
  auditText(input.actor); auditText(input.reason);
  if (!isUUID(input.accountID) || !/^\d+$/.test(input.dailyInputTokens) || !/^\d+$/.test(input.dailyOutputTokens) ||
    (input.days !== null && (!Number.isSafeInteger(input.days) || input.days < 1 || input.days > 3650)))
    throw new ServiceError('invalid_request');
  const applied = await setSubscription(db, { accountID: input.accountID, plan: input.plan, days: input.days,
    dailyInputTokens: BigInt(input.dailyInputTokens), dailyOutputTokens: BigInt(input.dailyOutputTokens) });
  await transaction(db, async sql => {
    await memberAccount(sql, input.accountID);
    await audit(sql, 'grant_quota', input.accountID, input.actor, input.reason, null,
      { plan: input.plan, days: input.days, dailyInputTokens: input.dailyInputTokens, dailyOutputTokens: input.dailyOutputTokens });
  });
  return applied;
}

/** Reconciliation: releases the hold of an attempt whose outcome stayed
 * unknown. Audited; never settles a guess. */
export async function reconcileHold(db: Database, input: { attemptID: string; actor: string; reason: string }) {
  auditText(input.actor); auditText(input.reason);
  if (!isUUID(input.attemptID)) throw new ServiceError('invalid_request');
  const result = await releaseProxyHold(db, input.attemptID);
  await transaction(db, async sql => {
    await audit(sql, 'release_hold', null, input.actor, input.reason, `proxy:${input.attemptID}`, { attemptID: input.attemptID });
  });
  return result;
}

// Local operator process only. No administrative HTTP routes or client admin key.
if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  const command = process.argv[2], url = process.env.DATABASE_URL;
  const usage = 'Use tokens-admin show <accountID> | grant-unlock | revoke-unlock | grant-balance | grant-quota | release-hold | routes | set-route. Mutations read JSON from stdin: {"accountID","actor","reason"} and, for grant-balance, {"grantID","creditNanoUSD"}; set-route: {"actor","reason","purpose","model","cap"}; grant-quota: {"accountID","actor","reason","plan","days","dailyInputTokens","dailyOutputTokens"}; release-hold: {"attemptID","actor","reason"}.';
  const known = command === 'show' ? process.argv.length === 4 : ['grant-unlock', 'revoke-unlock', 'grant-balance', 'grant-quota', 'release-hold', 'set-route'].includes(command ?? '') && process.argv.length === 3
    || command === 'routes' && process.argv.length === 3;
  if (!url || !known) { console.error(usage); process.exitCode = 1; } else {
    const db = connectDatabase(url);
    try {
      let input: any;
      if (command !== 'show' && command !== 'routes') {
        let data = '';
        for await (const chunk of process.stdin) { data += chunk.toString(); if (data.length > 1_000_000) throw new ServiceError('invalid_request'); }
        input = JSON.parse(data);
      }
      let result: unknown;
      if (command === 'show') result = await tokenAccountSummary(db, process.argv[3]!);
      else if (command === 'grant-unlock') result = await grantUnlock(db, input);
      else if (command === 'revoke-unlock') result = await revokeUnlock(db, input);
      else if (command === 'grant-balance') result = await grantBalance(db, input);
      else if (command === 'routes') result = await listPurposeRoutes(db);
      else if (command === 'set-route') result = await setPurposeRoute(db, input);
      else if (command === 'grant-quota') result = await grantQuota(db, input);
      else result = await reconcileHold(db, input);
      console.info(JSON.stringify(result));
    } catch (error) {
      console.error(error instanceof ServiceError ? error.code : 'token_operation_failed'); process.exitCode = 1;
    } finally { await db.end(); }
  }
}
