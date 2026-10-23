import { randomUUID } from 'node:crypto';
import type { PoolClient } from 'pg';
import { transaction, type Database } from './db.js';
import { ServiceError } from './errors.js';
import { appendMinuteEntry, lockMinuteWallet, MS_PER_MINUTE } from './minutes.js';
import { recordAdminAction } from './admin-audit.js';

export const SUBSCRIPTION_TIERS = ['standard', 'pro'] as const;
export type SubscriptionTier = typeof SUBSCRIPTION_TIERS[number];
export const SUBSCRIPTION_STATUSES = ['trialing', 'active', 'past_due', 'canceled'] as const;
export type SubscriptionStatus = typeof SUBSCRIPTION_STATUSES[number];

export interface SubscriptionConfig { version: number; freeDripEnabled: boolean; freeDripMinutes: number;
  freeDripRolloverCapMinutes: number; trialDays: number; lifetimeDripBudgetMinutes: number }
export interface PlanTier { tier: SubscriptionTier; monthlyMinutes: number; rolloverCapMinutes: number;
  features: Record<string, boolean>; stripePriceID: string | null; playProductID: string | null; sortOrder: number }

const boundedInt = (value: unknown, min: number, max: number): number => {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < min || value > max)
    throw new ServiceError('invalid_subscription_config');
  return value;
};
const minutesRow = (row: any): PlanTier => ({ tier: row.tier, monthlyMinutes: Number(row.monthly_minutes),
  rolloverCapMinutes: Number(row.rollover_cap_minutes), features: row.features ?? {},
  stripePriceID: row.stripe_price_id ?? null, playProductID: row.play_product_id ?? null, sortOrder: Number(row.sort_order) });
const configRow = (row: any): SubscriptionConfig => ({ version: Number(row.version), freeDripEnabled: Boolean(row.free_drip_enabled),
  freeDripMinutes: Number(row.free_drip_minutes), freeDripRolloverCapMinutes: Number(row.free_drip_rollover_cap_minutes),
  trialDays: Number(row.trial_days), lifetimeDripBudgetMinutes: Number(row.lifetime_drip_budget_minutes) });

export async function subscriptionConfig(db: Database): Promise<SubscriptionConfig> {
  const row = (await db.query('SELECT * FROM subscription_config WHERE singleton')).rows[0];
  if (!row) throw new ServiceError('subscription_config_missing', 503);
  return configRow(row);
}

export async function subscriptionTiers(db: Database): Promise<PlanTier[]> {
  return (await db.query('SELECT * FROM subscription_plans ORDER BY sort_order, tier')).rows.map(minutesRow);
}

/** Public catalog for the client paywall. Stripe price IDs stay server-side:
 *  the client never builds a Stripe session. */
export async function subscriptionCatalog(db: Database): Promise<{ config: SubscriptionConfig; tiers: Omit<PlanTier, 'stripePriceID'>[] }> {
  const [config, tiers] = [await subscriptionConfig(db), await subscriptionTiers(db)];
  return { config, tiers: tiers.map(({ stripePriceID, ...rest }) => rest) };
}

export interface SubscriptionConfigUpdate extends Partial<Omit<SubscriptionConfig, 'version'>> {
  version: number; reason: string;
  tiers?: { tier: SubscriptionTier; monthlyMinutes: number; rolloverCapMinutes: number;
    features?: Record<string, boolean>; stripePriceID?: string | null; playProductID?: string | null }[];
}

/** Operator edit: version-conflict guarded, reason-required, audited. */
export async function updateSubscriptionConfig(db: Database, adminID: string, input: SubscriptionConfigUpdate):
  Promise<{ config: SubscriptionConfig; tiers: PlanTier[] }> {
  if (typeof input.reason !== 'string' || input.reason.trim().length < 3) throw new ServiceError('admin_reason_required');
  return transaction(db, async sql => {
    const before = (await sql.query('SELECT * FROM subscription_config WHERE singleton FOR UPDATE')).rows[0];
    if (!before) throw new ServiceError('subscription_config_missing', 503);
    if (Number(before.version) !== input.version) throw new ServiceError('subscription_version_conflict', 409);
    const next = {
      freeDripEnabled: input.freeDripEnabled ?? Boolean(before.free_drip_enabled),
      freeDripMinutes: boundedInt(input.freeDripMinutes ?? Number(before.free_drip_minutes), 0, 60),
      freeDripRolloverCapMinutes: boundedInt(input.freeDripRolloverCapMinutes ?? Number(before.free_drip_rollover_cap_minutes), 0, 1440),
      trialDays: boundedInt(input.trialDays ?? Number(before.trial_days), 0, 30),
      lifetimeDripBudgetMinutes: boundedInt(input.lifetimeDripBudgetMinutes ?? Number(before.lifetime_drip_budget_minutes), 0, 100_000_000)
    };
    if (next.freeDripMinutes > next.freeDripRolloverCapMinutes) throw new ServiceError('invalid_subscription_config');
    await sql.query(`UPDATE subscription_config SET version=version+1, free_drip_enabled=$1, free_drip_minutes=$2,
      free_drip_rollover_cap_minutes=$3, trial_days=$4, lifetime_drip_budget_minutes=$5, updated_at=now() WHERE singleton`,
      [next.freeDripEnabled, next.freeDripMinutes, next.freeDripRolloverCapMinutes, next.trialDays,
        next.lifetimeDripBudgetMinutes]);
    for (const tier of input.tiers ?? []) {
      if (!SUBSCRIPTION_TIERS.includes(tier.tier)) throw new ServiceError('invalid_subscription_config');
      await sql.query(`UPDATE subscription_plans SET monthly_minutes=$2, rollover_cap_minutes=$3, features=$4::jsonb,
        stripe_price_id=$5, play_product_id=$6 WHERE tier=$1`, [tier.tier,
        boundedInt(tier.monthlyMinutes, 15, 100_000), boundedInt(tier.rolloverCapMinutes, 15, 144_000),
        JSON.stringify(tier.features ?? {}), tier.stripePriceID ?? null, tier.playProductID ?? null]);
    }
    const afterConfig = (await sql.query('SELECT * FROM subscription_config WHERE singleton')).rows[0];
    const tiers = (await sql.query('SELECT * FROM subscription_plans ORDER BY sort_order, tier')).rows.map(minutesRow);
    await recordAdminAction(sql, adminID, 'plans.update', 'subscription_config', input.reason,
      { previous: configRow(before), next: configRow(afterConfig),
        tiers: input.tiers?.map(({ tier, monthlyMinutes, rolloverCapMinutes }) => ({ tier, monthlyMinutes, rolloverCapMinutes })) ?? undefined });
    return { config: configRow(afterConfig), tiers };
  });
}

export interface SubscriptionRow { id: string; accountID: string; tier: SubscriptionTier; status: SubscriptionStatus;
  store: 'stripe' | 'play'; storeReference: string; periodStart: Date; periodEnd: Date; cancelAtPeriodEnd: boolean }

const subRow = (row: any): SubscriptionRow => ({ id: row.id, accountID: row.account_id, tier: row.tier, status: row.status,
  store: row.store, storeReference: row.store_reference, periodStart: new Date(row.period_start),
  periodEnd: new Date(row.period_end), cancelAtPeriodEnd: Boolean(row.cancel_at_period_end) });

/** The subscription that currently pays for the account, if any. */
export async function activeSubscription(db: Database, account: string): Promise<SubscriptionRow | null> {
  const row = (await db.query(`SELECT * FROM subscriptions WHERE account_id=$1 AND status IN ('trialing','active')
    ORDER BY (status='active') DESC, period_end DESC LIMIT 1`, [account])).rows[0];
  return row ? subRow(row) : null;
}

export async function upsertSubscriptionFromStore(db: Database, fields: { accountID: string; tier: SubscriptionTier;
  status: SubscriptionStatus; store: 'stripe' | 'play'; storeReference: string; periodStart: Date; periodEnd: Date;
  cancelAtPeriodEnd?: boolean }): Promise<SubscriptionRow> {
  if (!SUBSCRIPTION_TIERS.includes(fields.tier) || !SUBSCRIPTION_STATUSES.includes(fields.status) ||
    !(fields.periodEnd > fields.periodStart) || !/^[\x21-\x7e]{8,512}$/.test(fields.storeReference))
    throw new ServiceError('invalid_subscription');
  return transaction(db, async sql => {
    const row = (await sql.query(`INSERT INTO subscriptions(id,account_id,tier,status,store,store_reference,period_start,period_end,cancel_at_period_end)
      VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9)
      ON CONFLICT (store, store_reference) DO UPDATE SET tier=EXCLUDED.tier, status=EXCLUDED.status,
        period_start=EXCLUDED.period_start, period_end=EXCLUDED.period_end, cancel_at_period_end=EXCLUDED.cancel_at_period_end
      RETURNING *`, [randomUUID(), fields.accountID, fields.tier, fields.status, fields.store, fields.storeReference,
      fields.periodStart, fields.periodEnd, fields.cancelAtPeriodEnd ?? false])).rows[0];
    return subRow(row);
  });
}

const periodKey = (at: Date): string => at.toISOString().slice(0, 10);

/** Grant the tier's monthly minutes for a period, idempotent, rollover-capped. */
export async function grantSubscriptionPeriod(db: Database, subscription: SubscriptionRow): Promise<number> {
  return transaction(db, async sql => {
    const tier = ((await sql.query('SELECT * FROM subscription_plans WHERE tier=$1', [subscription.tier])).rows.map(minutesRow))[0];
    if (!tier) throw new ServiceError('invalid_subscription');
    return grantWithin(sql, subscription.accountID, subscription.id, periodKey(subscription.periodStart),
      tier.monthlyMinutes, tier.rolloverCapMinutes);
  });
}

/** One free drip per period for registered free accounts. Guests already
 *  received their attested welcome grant and stay out of the drip. */
export async function runDripForAccount(db: Database, account: string, now = new Date()): Promise<number> {
  const config = await subscriptionConfig(db);
  const key = `${now.getUTCFullYear()}-${String(now.getUTCMonth() + 1).padStart(2, '0')}`;
  return transaction(db, async sql => {
    if ((await sql.query('SELECT 1 FROM accounts WHERE id=$1 AND is_guest', [account])).rowCount) return 0;
    if ((await sql.query(`SELECT 1 FROM subscriptions WHERE account_id=$1 AND status IN ('trialing','active') LIMIT 1`, [account])).rowCount)
      return 0;
    if (!config.freeDripEnabled || config.freeDripMinutes === 0) return 0;
    if (config.lifetimeDripBudgetMinutes > 0) {
      const used = (await sql.query(`SELECT COALESCE(sum(minutes),0) AS used FROM subscription_grants WHERE subscription_id IS NULL`)).rows[0];
      if (Number(used.used) >= config.lifetimeDripBudgetMinutes) return 0;
    }
    return grantWithin(sql, account, null, key, config.freeDripMinutes, config.freeDripRolloverCapMinutes);
  });
}

/** Shared grant body. The claim row makes it idempotent; the wallet cap keeps
 *  the granted balance inside the tier's rollover ceiling. */
async function grantWithin(sql: PoolClient, account: string, subscriptionID: string | null, periodKey: string,
  minutes: number, capMinutes: number): Promise<number> {
  const claimed = (await sql.query(`INSERT INTO subscription_grants(id,subscription_id,account_id,period_key,minutes)
    VALUES($1,$2,$3,$4,$5) ON CONFLICT (COALESCE(subscription_id::text,'free'), account_id, period_key) DO NOTHING RETURNING id`,
    [randomUUID(), subscriptionID, account, periodKey, minutes])).rows[0];
  if (!claimed) return 0;
  await lockMinuteWallet(sql, account);
  const wallet = (await sql.query('SELECT balance_ms,reserved_ms FROM minute_wallets WHERE account_id=$1', [account])).rows[0];
  const available = Math.max(0, Number(wallet?.balance_ms ?? 0) - Number(wallet?.reserved_ms ?? 0));
  const grantMs = Math.max(0, Math.min(minutes * MS_PER_MINUTE, capMinutes * MS_PER_MINUTE - available));
  const reference = `${subscriptionID === null ? 'free-drip' : `subscription:${subscriptionID}`}:${account}:${periodKey}`;
  if (grantMs > 0) await appendMinuteEntry(sql, account, reference, 'subscription', grantMs, 0);
  return Math.round(grantMs / MS_PER_MINUTE);
}
