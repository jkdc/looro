-- Token billing (token-billing F1): prepaid proxy credit + BYOK unlock.
CREATE TABLE entitlements (
  account_id uuid PRIMARY KEY REFERENCES accounts(id),
  unlocked_at timestamptz,
  unlock_source text CHECK (unlock_source IN ('play','stripe','campaign')),
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE rate_versions (
  id text PRIMARY KEY,
  nano_per_million_input bigint NOT NULL,
  nano_per_million_output bigint NOT NULL,
  valid_from timestamptz NOT NULL DEFAULT now()
);
INSERT INTO rate_versions(id,nano_per_million_input,nano_per_million_output) VALUES
  ('2026-09-minimax-m', 300000, 1200000);
