import type { Database } from './db.js';
import { transaction } from './db.js';
import { appendEntry } from './ledger.js';
import { ServiceError } from './errors.js';
import { activeRateVersion } from './token-proxy.js';

/** Daily subscription quota (change proxy-api-provider): the allowance is a
 * grant limit, not stored money. The daily job converts each active
 * subscription's token allowance at the active rate version into one
 * idempotent ledger 'purchase' entry, so admission, metering and the
 * immutable ledger stay the single money path. The free daily allowance
 * (PROXY_FREE_DAILY_*_TOKENS, default 0 = off) covers every non-guest
 * account without an active subscription. */

export interface QuotaAllowance { dailyInputTokens: number; dailyOutputTokens: number }

const bounded = (value: string | undefined, fallback: number, max: number, name: string): number => {
  if (value === undefined || value === '') return fallback;
  const n = Number(value);
  if (!Number.isSafeInteger(n) || n < 0 || n > max) throw new Error(`${name} must be an integer within [0, ${max}].`);
  return n;
};

export const proxyQuotaConfig = (env: NodeJS.ProcessEnv): QuotaAllowance => ({
  dailyInputTokens: bounded(env.PROXY_FREE_DAILY_INPUT_TOKENS, 0, 10_000_000, 'PROXY_FREE_DAILY_INPUT_TOKENS'),
  dailyOutputTokens: bounded(env.PROXY_FREE_DAILY_OUTPUT_TOKENS, 0, 10_000_000, 'PROXY_FREE_DAILY_OUTPUT_TOKENS'),
});

const grantDay = () => new Date().toISOString().slice(0, 10);

/** The nanoUSD value of a token allowance at a rate version. **Single
 * valuation for every path** (sweep, assignment, upgrade): sum the token
 * costs first, then divide — per-direction truncation would make the daily
 * sweep and the assignment disagree on the same allowance. */
export const tokenCreditNano = (input: bigint, output: bigint, rate: { nanoPerMillionInput: bigint; nanoPerMillionOutput: bigint }): bigint =>
  (input * rate.nanoPerMillionInput + output * rate.nanoPerMillionOutput) / 1_000_000n;

/** Funds today's allowance for one account inside a caller transaction.
 * The pending amount is computed from the **total already credited today**
 * across every subscription identity (grant + top-ups) while the caller
 * holds the wallet lock — so successive plan changes in one day each top up
 * only their remaining difference and never reuse another amount's
 * idempotent identity (audit rev.4): the first grant lands under the
 * standard `subscription-grant:<date>:<accountID>` reference, and every
 * increment uses `subscription-topup:<date>:<accountID>:<credit>` where the
 * target amount is part of the identity, making a retry of the same top-up a
 * no-op and a different plan a fresh one. Already funded at or above the
 * allowance → 0n (credit is never clawed back). Returns the granted amount. */
export async function grantSubscriptionDayInTransaction(
  sql: Parameters<Parameters<typeof transaction>[1]>[0],
  accountID: string, credit: bigint, rate: { id: string; nanoPerMillionInput: bigint; nanoPerMillionOutput: bigint },
  day: string,
): Promise<bigint> {
  if (credit <= 0n) return 0n;
  const total = BigInt((await sql.query(
    `SELECT COALESCE(SUM(balance_delta_nano),0)::bigint AS total FROM ledger
      WHERE account_id=$1 AND kind='purchase'
        AND (reference LIKE $2 OR reference LIKE $3)`,
    [accountID, `subscription-grant:${day}:${accountID}`, `subscription-topup:${day}:${accountID}:%`])).rows[0].total);
  const pending = credit - total;
  if (pending <= 0n) return 0n;
  if (total === 0n) {
    return (await appendEntry(sql, accountID, `subscription-grant:${day}:${accountID}`, 'purchase', pending, 0n, rate.id))
      ? pending : 0n;
  }
  return (await appendEntry(sql, accountID, `subscription-topup:${day}:${accountID}:${credit}`, 'purchase', pending, 0n, rate.id))
    ? pending : 0n;
}

/** Grants the day's allowance. Idempotent by the unique ledger reference
 * (`subscription-grant:<date>:<accountID>`): reruns and restarts add nothing.
 * Active subscriptions are funded **regardless of the free-funnel knobs**
 * (B1): the allowance gate only covers the free member sweep. Guests and
 * deleted accounts are excluded defensively in both queries. */
export async function runDailyQuotaGrants(db: Database, allowance: QuotaAllowance): Promise<{ granted: number }> {
  const rate = await activeRateVersion(db);
  const day = grantDay();
  let granted = 0;
  const grantOnce = async (accountID: string, credit: bigint) => {
    const applied = await transaction(db, sql =>
      grantSubscriptionDayInTransaction(sql, accountID, credit, rate, day));
    if (applied > 0n) granted++;
  };
  // Active subscriptions use their own plan size.
  const subscribed = (await db.query(`SELECT s.account_id,s.daily_input_tokens,s.daily_output_tokens FROM token_subscriptions s
    JOIN accounts a ON a.id=s.account_id
    WHERE (s.active_until IS NULL OR s.active_until > now()) AND NOT a.is_guest AND a.deleted_at IS NULL`)).rows;
  for (const row of subscribed)
    await grantOnce(row.account_id as string, tokenCreditNano(BigInt(row.daily_input_tokens), BigInt(row.daily_output_tokens), rate));
  // Free daily allowance for every non-guest member without an active
  // subscription — only when the funnel is enabled. Guests never hold
  // proxy credit.
  if (allowance.dailyInputTokens > 0 || allowance.dailyOutputTokens > 0) {
    const credit = tokenCreditNano(BigInt(allowance.dailyInputTokens), BigInt(allowance.dailyOutputTokens), rate);
    if (credit > 0n) {
      const members = (await db.query(`SELECT a.id AS account_id FROM accounts a
        WHERE NOT a.is_guest AND a.deleted_at IS NULL AND NOT EXISTS (
          SELECT 1 FROM token_subscriptions s WHERE s.account_id=a.id AND (s.active_until IS NULL OR s.active_until > now()))
        ORDER BY a.id`)).rows;
      for (const row of members) await grantOnce(row.account_id as string, credit);
    }
  }
  return { granted };
}

/** Operator pilot assignment (CLI only): upserts the plan window. */
export async function setSubscription(db: Database, input: { accountID: string; plan: string; days: number | null; dailyInputTokens: bigint; dailyOutputTokens: bigint }) {
  if (!['free', 'pilot', 'standard'].includes(input.plan)) throw new ServiceError('invalid_request');
  if (input.dailyInputTokens <= 0n || input.dailyOutputTokens <= 0n) throw new ServiceError('invalid_request');
  await db.query(`INSERT INTO token_subscriptions(account_id,plan,daily_input_tokens,daily_output_tokens,active_until)
    VALUES($1,$2,$3,$4,CASE WHEN $5::int IS NULL THEN NULL ELSE now() + ($5::int * interval '1 day') END)
    ON CONFLICT (account_id) DO UPDATE SET plan=EXCLUDED.plan,daily_input_tokens=EXCLUDED.daily_input_tokens,
      daily_output_tokens=EXCLUDED.daily_output_tokens,active_until=EXCLUDED.active_until,updated_at=now()`,
    [input.accountID, input.plan, input.dailyInputTokens.toString(), input.dailyOutputTokens.toString(), input.days]);
  return { accountID: input.accountID, plan: input.plan, days: input.days,
    dailyInputTokens: input.dailyInputTokens.toString(), dailyOutputTokens: input.dailyOutputTokens.toString() };
}
