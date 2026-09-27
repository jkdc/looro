# Design: subscription-monetization

## Config shape (the tunable dial)

```sql
CREATE TABLE subscription_config (
  singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
  version integer NOT NULL,
  free_drip_enabled boolean NOT NULL DEFAULT true,
  free_drip_minutes integer NOT NULL DEFAULT 5 CHECK (free_drip_minutes BETWEEN 0 AND 60),
  free_drip_rollover_cap_minutes integer NOT NULL DEFAULT 15,
  trial_days integer NOT NULL DEFAULT 7 CHECK (trial_days BETWEEN 0 AND 30),
  lifetime_drip_budget_minutes bigint NOT NULL DEFAULT 0, -- 0 = unlimited, audited
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE subscription_plans (
  tier text PRIMARY KEY,          -- 'standard' | 'pro' (free is not a row)
  monthly_minutes integer NOT NULL CHECK (monthly_minutes BETWEEN 15 AND 100000),
  rollover_cap_minutes integer NOT NULL,   -- total balance cap from grants
  features jsonb NOT NULL DEFAULT '{}'::jsonb,
  sort_order integer NOT NULL
);
CREATE TABLE subscriptions (
  id uuid PRIMARY KEY,
  account_id uuid NOT NULL REFERENCES accounts(id),
  tier text NOT NULL REFERENCES subscription_plans(tier),
  status text NOT NULL CHECK (status IN ('trialing','active','past_due','canceled')),
  store text NOT NULL CHECK (store IN ('stripe','play')),
  store_reference text,           -- Stripe subscription id / Play purchase token
  period_start timestamptz NOT NULL,
  period_end timestamptz NOT NULL,
  cancel_at_period_end boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (store, store_reference)
);
CREATE TABLE subscription_grants (
  id uuid PRIMARY KEY,
  subscription_id uuid REFERENCES subscriptions(id), -- null for free drip
  account_id uuid NOT NULL REFERENCES accounts(id),
  period_key text NOT NULL,       -- 'YYYY-MM' or ISO period start
  minutes integer NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (COALESCE(subscription_id::text,'free'), account_id, period_key)
);
```

`minute_entries` gains kind `'subscription'` (constraint rebuild in the
migration; entries carry `reference = subscription:<id>:<period_key>` or
`free-drip:<account>:<period_key>` for idempotency).

## Grant path

`grantPeriod(subscription|null, account, periodKey)` runs in one
transaction: advisory lock per account, insert `subscription_grants`
(conflict → no-op), compute grant minutes = tier monthly (capped by
`rollover_cap − current granted balance`) or the free drip, then
`appendMinuteEntry(..., 'subscription', +minutes*60000, 0, 'funded')`.
Existing reserve→settle hosted math consumes from the same wallet, so no
new enforcement code exists anywhere.

The drip runner runs opportunistically: `GET /v1/minutes` (and the hosted
admission path) calls `runDripForAccount(account, now)` — cheap, indexed,
one idempotent row per month — instead of a cron that must enumerate users.

## Renewal wiring

- **Stripe**: Checkout `mode: 'subscription'` with metadata `account_id`;
  existing webhook path (same endpoint, same signature verification)
  handles `checkout.session.completed`, `invoice.paid` (→ grant + extend
  period), `invoice.payment_failed` (→ `past_due`), and
  `customer.subscription.deleted/updated` (→ canceled / sync status).
- **Play**: subscription purchase token verified + acknowledged via the
  existing transport; a poll on app open (`GET /v1/subscriptions/mine`)
  refreshes status from the Play Developer API. Renewal periods are
  derived from the token's expiry; RTDN intentionally deferred.

## Client contract

`GET /v1/minutes` gains:
```json
{ "tier": "pro", "trialing": false,
  "minutes": { "remaining": 42130, "periodEnd": "…", "drip": { "perMonth": 5 } },
  "features": { "aiReports": true, "unlimitedThemes": true } }
```
`GET /v1/subscriptions/plans` returns the versioned catalog (tiers,
monthly minutes, rollover caps, store product IDs) for paywall rendering.
`GET /v1/subscriptions/mine` returns the caller's subscription state.

## Panel

- `#planes` view: renders the config + tiers as a form; PUT requires
  `version` (conflict → 409) and `reason` (audited to `admin_actions` as
  `plans.update`), mirroring the welcome-policy contract.
- `subscriptions` added to the browse whitelist (read-only columns).
- Economy dashboard: subscription snapshot tiles (active, trialing,
  canceled 30d) and a granted-vs-consumed minutes timeseries.

## Non-goals

Roles/permission systems, per-feature billing beyond the `features` map,
regional pricing, proration customization, push RTDN.
