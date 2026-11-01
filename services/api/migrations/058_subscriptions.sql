-- Daily subscription quota (change proxy-api-provider). The allowance is a
-- grant limit, not stored money: the daily job converts it at the active rate
-- version into an idempotent ledger 'purchase' entry, so admission, metering
-- and the immutable ledger stay the single money path. Named token_ to avoid
-- the hosted-minutes `subscriptions` table (migration 027).
CREATE TABLE token_subscriptions (
  account_id uuid PRIMARY KEY REFERENCES accounts(id),
  plan text NOT NULL CHECK (plan IN ('free','pilot','standard')),
  daily_input_tokens bigint NOT NULL CHECK (daily_input_tokens > 0),
  daily_output_tokens bigint NOT NULL CHECK (daily_output_tokens > 0),
  active_until timestamptz,
  updated_at timestamptz NOT NULL DEFAULT now()
);
