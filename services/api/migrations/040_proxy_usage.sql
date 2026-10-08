-- Durable proxy usage records + operator grant audit (informe login/tokens/cuotas).
-- One row per successful relay: the token counts reported by the provider, the
-- admission estimate, the computed cost and what actually settled. The settle
-- ledger entry stays the money truth; this table is the usage truth, so
-- zero-cost calls are recorded without a ledger row.
CREATE TABLE proxy_usage (
  id uuid PRIMARY KEY,
  account_id uuid NOT NULL REFERENCES accounts(id),
  rate_version text NOT NULL REFERENCES rate_versions(id),
  input_tokens bigint NOT NULL CHECK (input_tokens >= 0),
  output_tokens bigint NOT NULL CHECK (output_tokens >= 0),
  estimate_nano bigint NOT NULL CHECK (estimate_nano >= 0),
  cost_nano bigint NOT NULL CHECK (cost_nano >= 0),
  settled_nano bigint NOT NULL CHECK (settled_nano >= 0),
  ledger_reference text UNIQUE REFERENCES ledger(reference),
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX proxy_usage_account_created ON proxy_usage (account_id, created_at);
CREATE INDEX proxy_usage_created ON proxy_usage (created_at);

-- Immutable audit of local-operator grants (tokens-admin): who assigned an
-- unlock or wallet credit, for which account, and why. Journal entries
-- themselves carry no actor, so this is the accountability record.
CREATE TABLE token_admin_audit (
  id uuid PRIMARY KEY,
  actor text NOT NULL,
  reason text NOT NULL,
  action text NOT NULL CHECK (action IN ('grant_unlock','revoke_unlock','grant_balance')),
  account_id uuid NOT NULL REFERENCES accounts(id),
  reference text,
  detail jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER token_admin_audit_immutable BEFORE UPDATE OR DELETE ON token_admin_audit
FOR EACH ROW EXECUTE FUNCTION immutable_ledger();
