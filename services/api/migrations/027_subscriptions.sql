-- Subscription monetization: plans as operator-editable versioned data,
-- per-account subscription state, and idempotent periodic minute grants
-- into the existing conversation-minute wallet.
CREATE TABLE subscription_config (
  singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
  version integer NOT NULL,
  free_drip_enabled boolean NOT NULL DEFAULT true,
  free_drip_minutes integer NOT NULL DEFAULT 5 CHECK (free_drip_minutes BETWEEN 0 AND 60),
  free_drip_rollover_cap_minutes integer NOT NULL DEFAULT 15 CHECK (free_drip_rollover_cap_minutes BETWEEN 0 AND 1440),
  trial_days integer NOT NULL DEFAULT 7 CHECK (trial_days BETWEEN 0 AND 30),
  lifetime_drip_budget_minutes bigint NOT NULL DEFAULT 0 CHECK (lifetime_drip_budget_minutes >= 0),
  updated_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO subscription_config(singleton, version) VALUES (true, 1);

-- Free is not a row: it is what an account gets without an active subscription.
CREATE TABLE subscription_plans (
  tier text PRIMARY KEY CHECK (tier IN ('standard','pro')),
  monthly_minutes integer NOT NULL CHECK (monthly_minutes BETWEEN 15 AND 100000),
  rollover_cap_minutes integer NOT NULL CHECK (rollover_cap_minutes >= 15 AND rollover_cap_minutes <= 144000),
  features jsonb NOT NULL DEFAULT '{}'::jsonb,
  stripe_price_id text,
  play_product_id text,
  sort_order integer NOT NULL
);
INSERT INTO subscription_plans(tier,monthly_minutes,rollover_cap_minutes,features,sort_order) VALUES
  ('standard', 75, 150, '{"aiReports":false,"unlimitedThemes":false}', 1),
  ('pro', 250, 500, '{"aiReports":true,"unlimitedThemes":true}', 2);

CREATE TABLE subscriptions (
  id uuid PRIMARY KEY,
  account_id uuid NOT NULL REFERENCES accounts(id),
  tier text NOT NULL REFERENCES subscription_plans(tier),
  status text NOT NULL CHECK (status IN ('trialing','active','past_due','canceled')),
  store text NOT NULL CHECK (store IN ('stripe','play')),
  store_reference text NOT NULL,
  period_start timestamptz NOT NULL,
  period_end timestamptz NOT NULL,
  cancel_at_period_end boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (store, store_reference)
);
CREATE INDEX subscriptions_account_idx ON subscriptions(account_id, status);

-- One grant per (subscription-or-free, account, period). subscription_id is
-- nullable for the free drip, so the uniqueness is on the COALESCE.
CREATE TABLE subscription_grants (
  id uuid PRIMARY KEY,
  subscription_id uuid REFERENCES subscriptions(id),
  account_id uuid NOT NULL REFERENCES accounts(id),
  period_key text NOT NULL,
  minutes integer NOT NULL CHECK (minutes >= 0),
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX subscription_grants_period_idx
  ON subscription_grants (COALESCE(subscription_id::text,'free'), account_id, period_key);

-- Rebuild the kind constraint with the subscription kind. Rows are never
-- updated (immutable trigger), so this is a metadata-only change.
ALTER TABLE minute_entries DROP CONSTRAINT minute_entries_kind_check;
ALTER TABLE minute_entries ADD CONSTRAINT minute_entries_kind_check
  CHECK (kind IN ('welcome','gift','purchase','reserve','settle','release','forfeit','transfer','subscription'));
