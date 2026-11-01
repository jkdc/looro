import type { Database } from './db.js';
import { purposeRouteTable } from './proxy-policy.js';
import type { QuotaAllowance } from './proxy-quota.js';

/** Display-only policy for the first-party api provider: quota state and the
 * configured model label per purpose. It never grants, authorizes or alters
 * a routing or billing decision — the proxy remains the single decision
 * point. */
export interface ConversationPolicy {
  quota: { limitTokens: number; usedTokensToday: number; resetsAtUtc: string; exhausted: boolean };
  models: Record<string, string>;
}

export async function conversationPolicy(db: Database, accountID: string,
  freeAllowance: QuotaAllowance | null): Promise<ConversationPolicy> {
  const subscription = (await db.query(`SELECT daily_input_tokens,daily_output_tokens FROM token_subscriptions
    WHERE account_id=$1 AND (active_until IS NULL OR active_until > now())`, [accountID])).rows[0];
  const limitTokens = subscription
    ? Number(subscription.daily_input_tokens) + Number(subscription.daily_output_tokens)
    : freeAllowance ? freeAllowance.dailyInputTokens + freeAllowance.dailyOutputTokens : 0;
  const used = (await db.query(`SELECT COALESCE(sum(input_tokens+output_tokens),0)::bigint AS used FROM proxy_usage
    WHERE account_id=$1 AND created_at >= (date_trunc('day', now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC')`,
    [accountID])).rows[0];
  const now = new Date();
  const resetsAtUtc = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate() + 1)).toISOString();
  const models = await purposeRouteTable(db);
  const usedTokensToday = Number(used.used);
  return { quota: { limitTokens, usedTokensToday, resetsAtUtc, exhausted: limitTokens > 0 && usedTokensToday >= limitTokens }, models };
}
