import { randomUUID } from 'node:crypto';
import type { Database } from './db.js';
import { ServiceError } from './errors.js';
import { activeSubscription, grantSubscriptionPeriod, SUBSCRIPTION_TIERS, upsertSubscriptionFromStore,
  type SubscriptionRow, type SubscriptionTier } from './subscriptions.js';

/** Store billing for plan subscriptions. Stripe events arrive on the existing
 *  minutes webhook (same secret, same signature verification); Play
 *  subscriptions are verified and then poll-synced. Grants always go through
 *  the shared subscription path — this module never touches wallets directly. */

const SUBSCRIPTION_EVENTS = new Set(['checkout.session.completed', 'invoice.paid', 'invoice.payment_failed',
  'customer.subscription.updated', 'customer.subscription.deleted']);

export interface StripeSubscriptionTransport {
  create(params: Record<string, unknown>, key: string): Promise<{ id: string; url?: string | null }>;
  verifyEvent(raw: Buffer, signature: string): { type: string; data: { object: any } };
}

const epochToDate = (value: unknown): Date => {
  if (typeof value !== 'number' || !Number.isFinite(value)) throw new ServiceError('invalid_subscription');
  return new Date(value * 1000);
};

export class SubscriptionBilling {
  constructor(readonly db: Database, readonly transport: StripeSubscriptionTransport,
    readonly options: { origin: string; defaultTier?: SubscriptionTier }) {}

  async checkout(accountID: string, tier: string, key: string): Promise<{ checkoutURL: string }> {
    if (!SUBSCRIPTION_TIERS.includes(tier as SubscriptionTier)) throw new ServiceError('invalid_subscription_tier');
    const tierRow = (await this.db.query('SELECT stripe_price_id FROM subscription_plans WHERE tier=$1', [tier])).rows[0];
    if (!tierRow?.stripe_price_id) throw new ServiceError('subscription_unavailable', 503);
    if (!/^[\x21-\x7e]{8,128}$/.test(key)) throw new ServiceError('idempotency_key_required');
    const session = await this.transport.create({
      mode: 'subscription', client_reference_id: accountID,
      line_items: [{ price: tierRow.stripe_price_id, quantity: 1 }],
      metadata: { account_id: accountID, tier },
      subscription_data: { metadata: { account_id: accountID, tier } },
      success_url: `${this.options.origin}/payment-return?subscription=1`,
      cancel_url: `${this.options.origin}/payment-return?subscription=0`
    }, `subscription-checkout:${accountID}:${key}`);
    if (typeof session.url !== 'string' || !session.url.startsWith('https://')) throw new ServiceError('stripe_checkout_invalid', 502);
    return { checkoutURL: session.url };
  }

  /** Returns true when the event belongs to subscriptions and was applied.
   *  Every branch is idempotent: upserts key on (store, store_reference) and
   *  grants key on (subscription, account, period). */
  async handleEvent(event: { type: string; data: { object: any } }): Promise<boolean> {
    if (!SUBSCRIPTION_EVENTS.has(event.type)) return false;
    const object = event.data.object ?? {};
    if (event.type === 'checkout.session.completed') {
      if (object.mode !== 'subscription' || typeof object.subscription !== 'string' ||
        typeof object.metadata?.account_id !== 'string' || !SUBSCRIPTION_TIERS.includes(object.metadata.tier))
        return false;
      const accountID = object.metadata.account_id as string, tier = object.metadata.tier as SubscriptionTier;
      const existing = await this.#byReference('stripe', object.subscription);
      if (existing) return true;
      const trial = (await this.db.query('SELECT trial_days FROM subscription_config WHERE singleton')).rows[0];
      const trialDays = Math.max(0, Number(trial?.trial_days ?? 0));
      const start = new Date();
      await upsertSubscriptionFromStore(this.db, { accountID, tier, store: 'stripe', storeReference: object.subscription,
        status: trialDays > 0 ? 'trialing' : 'active', periodStart: start,
        periodEnd: new Date(start.getTime() + (trialDays > 0 ? trialDays : 31) * 86_400_000) });
      return true;
    }
    if (event.type === 'invoice.paid') {
      const reference = typeof object.subscription === 'string' ? object.subscription : null;
      const row = reference ? await this.#byReference('stripe', reference) : null;
      if (!row) return false;
      await upsertSubscriptionFromStore(this.db, { accountID: row.accountID, tier: row.tier, store: 'stripe',
        storeReference: reference!, status: 'active', periodStart: epochToDate(object.period_start),
        periodEnd: epochToDate(object.period_end), cancelAtPeriodEnd: row.cancelAtPeriodEnd });
      await grantSubscriptionPeriod(this.db, await this.#byReference('stripe', reference!) as SubscriptionRow);
      return true;
    }
    if (event.type === 'invoice.payment_failed') {
      const reference = typeof object.subscription === 'string' ? object.subscription : null;
      if (!reference) return false;
      const row = await this.#byReference('stripe', reference);
      if (!row) return false;
      await upsertSubscriptionFromStore(this.db, { accountID: row.accountID, tier: row.tier, store: 'stripe',
        storeReference: reference, status: 'past_due', periodStart: row.periodStart, periodEnd: row.periodEnd,
        cancelAtPeriodEnd: row.cancelAtPeriodEnd });
      return true;
    }
    // customer.subscription.updated / deleted
    const reference = typeof object.id === 'string' ? object.id : null;
    if (!reference) return false;
    const row = await this.#byReference('stripe', reference);
    if (!row) return false;
    const status = event.type === 'customer.subscription.deleted' ? 'canceled' :
      ['active', 'past_due', 'trialing'].includes(object.status) ? object.status : 'active';
    const updated = await upsertSubscriptionFromStore(this.db, { accountID: row.accountID, tier: row.tier, store: 'stripe',
      storeReference: reference, status, periodStart: object.current_period_start ? epochToDate(object.current_period_start) : row.periodStart,
      periodEnd: object.current_period_end ? epochToDate(object.current_period_end) : row.periodEnd,
      cancelAtPeriodEnd: Boolean(object.cancel_at_period_end) });
    if (status === 'active' && event.type === 'customer.subscription.updated' && object.current_period_start)
      await grantSubscriptionPeriod(this.db, updated).catch(() => undefined);
    return true;
  }

  /** Poll-sync a Play subscription purchase token into local state. */
  async syncPlay(accountID: string, token: string,
    fetcher: (token: string) => Promise<any>): Promise<SubscriptionRow | null> {
    const state = await fetcher(token).catch(() => null);
    if (!state || typeof state !== 'object') return null;
    const tier = SUBSCRIPTION_TIERS.find(t =>
      state.lineItems?.some((line: any) => typeof line.productId === 'string' && line.productId.endsWith(`-${t}`)));
    if (!tier) throw new ServiceError('invalid_subscription');
    const existing = await this.#byReference('play', token);
    const status = state.cancelledUntilMillis > Date.now() || state.subscriptionState === 5 ? 'canceled' :
      state.subscriptionState === 2 || state.subscriptionState === 3 ? 'active' :
      state.subscriptionState === 4 ? 'past_due' : existing?.status ?? 'active';
    const periodEnd = new Date(Number(state.expiryTimeMillis ?? Date.now() + 31 * 86_400_000));
    const row = await upsertSubscriptionFromStore(this.db, { accountID, tier, store: 'play', storeReference: token,
      status, periodStart: existing?.periodStart ?? new Date(), periodEnd,
      cancelAtPeriodEnd: Boolean(state.autoRenewing) === false && status === 'active' });
    if (status === 'active' && (!existing || existing.periodEnd < periodEnd))
      await grantSubscriptionPeriod(this.db, row);
    return row;
  }

  async #byReference(store: 'stripe' | 'play', reference: string): Promise<SubscriptionRow | null> {
    const row = (await this.db.query('SELECT * FROM subscriptions WHERE store=$1 AND store_reference=$2', [store, reference])).rows[0];
    return row ? { id: row.id, accountID: row.account_id, tier: row.tier, status: row.status, store: row.store,
      storeReference: row.store_reference, periodStart: new Date(row.period_start), periodEnd: new Date(row.period_end),
      cancelAtPeriodEnd: Boolean(row.cancel_at_period_end) } : null;
  }
}

export { activeSubscription };
