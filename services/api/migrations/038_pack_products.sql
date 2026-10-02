-- Pack products (token-billing 1.2): the BYOK unlock and the prepaid token pack
-- ride the shared purchase order registry, receipt vault and fulfillment router.
-- No product or price is seeded here; catalogs are protected server configuration.

-- The shared order registry gains the two pack entitlement kinds. A pack order
-- grants no fixed minutes and no AI allocation; its quote carries the kind.
ALTER TABLE minute_purchase_orders DROP CONSTRAINT minute_purchase_orders_entitlement_kind_check;
ALTER TABLE minute_purchase_orders ADD CONSTRAINT minute_purchase_orders_entitlement_kind_check
  CHECK (entitlement_kind IN ('minutes','ai_value','byok_unlock','token_pack'));
ALTER TABLE minute_purchase_orders DROP CONSTRAINT purchase_entitlement_amount;
ALTER TABLE minute_purchase_orders ADD CONSTRAINT purchase_entitlement_amount CHECK (
  (entitlement_kind='minutes' AND allowance_ms IS NOT NULL AND allowance_ms BETWEEN 60000 AND 86400000 AND allowance_ms%60000=0)
  OR (entitlement_kind='ai_value' AND allowance_ms IS NULL)
  OR (entitlement_kind IN ('byok_unlock','token_pack') AND allowance_ms IS NULL));

-- Set-once unlock delivery needs to know which order granted the active unlock so
-- a refund or chargeback of that order revokes only its own grant.
ALTER TABLE entitlements ADD COLUMN unlock_order_id uuid REFERENCES minute_purchase_orders(id);

-- Immutable purchase-time pack quote. The pack's nanoUSD credit is fixed at order
-- time together with the rate version that keeps the balance interpretable.
CREATE TABLE pack_purchase_quotes (
  order_id uuid PRIMARY KEY REFERENCES minute_purchase_orders(id),
  kind text NOT NULL CHECK (kind IN ('byok_unlock','token_pack')),
  credit_nano bigint NOT NULL CHECK (credit_nano BETWEEN 0 AND 1000000000000000),
  rate_version text REFERENCES rate_versions(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  CHECK ((kind='byok_unlock' AND credit_nano=0 AND rate_version IS NULL)
    OR (kind='token_pack' AND credit_nano>0))
);
CREATE TRIGGER pack_purchase_quotes_immutable BEFORE UPDATE OR DELETE ON pack_purchase_quotes
FOR EACH ROW EXECUTE FUNCTION immutable_ledger();

-- A pack-kind order cannot exist without a matching immutable quote.
CREATE FUNCTION verify_pack_quote_link() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE ordered minute_purchase_orders%ROWTYPE;
BEGIN
  SELECT * INTO ordered FROM minute_purchase_orders WHERE id=NEW.id;
  IF ordered.entitlement_kind IN ('byok_unlock','token_pack') THEN
    IF NOT EXISTS (SELECT 1 FROM pack_purchase_quotes
      WHERE order_id=NEW.id AND kind=ordered.entitlement_kind) THEN
      RAISE EXCEPTION 'pack_quote_required' USING ERRCODE='P0001';
    END IF;
  END IF;
  RETURN NEW;
END; $$;
CREATE CONSTRAINT TRIGGER pack_quote_required AFTER INSERT ON minute_purchase_orders
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_pack_quote_link();

CREATE TABLE pack_purchase_transactions (
  order_id uuid PRIMARY KEY REFERENCES pack_purchase_quotes(order_id),
  account_id uuid NOT NULL REFERENCES accounts(id),
  provider text NOT NULL CHECK (provider IN ('stripe','play')),
  environment text NOT NULL CHECK (environment IN ('test','live')),
  merchant text NOT NULL,
  transaction_hash text NOT NULL CHECK (transaction_hash ~ '^[a-f0-9]{64}$'),
  state text NOT NULL CHECK (state IN ('pending','purchased','voided')),
  credit_nano bigint NOT NULL CHECK (credit_nano BETWEEN 0 AND 1000000000000000),
  granted_nano bigint NOT NULL DEFAULT 0 CHECK (granted_nano IN (0,credit_nano)),
  refunded_minor bigint NOT NULL DEFAULT 0 CHECK (refunded_minor BETWEEN 0 AND 100000000),
  reversed_nano bigint NOT NULL DEFAULT 0 CHECK (reversed_nano>=0 AND reversed_nano<=granted_nano),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(provider,environment,merchant,transaction_hash),
  CHECK (state <> 'pending' OR granted_nano=0),
  CHECK (state <> 'purchased' OR granted_nano=credit_nano)
);
CREATE FUNCTION protect_pack_purchase_transaction() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF ROW(NEW.order_id,NEW.account_id,NEW.provider,NEW.environment,NEW.merchant,NEW.transaction_hash,NEW.credit_nano,NEW.created_at)
      IS DISTINCT FROM ROW(OLD.order_id,OLD.account_id,OLD.provider,OLD.environment,OLD.merchant,OLD.transaction_hash,OLD.credit_nano,OLD.created_at)
    OR NEW.granted_nano<OLD.granted_nano OR NEW.refunded_minor<OLD.refunded_minor OR NEW.reversed_nano<OLD.reversed_nano
    OR (NEW.granted_nano<>OLD.granted_nano AND (OLD.state<>'pending' OR NEW.state<>'purchased'))
    OR (OLD.state='voided' AND NEW.state<>'voided') OR (OLD.state='purchased' AND NEW.state='pending') THEN
    RAISE EXCEPTION 'pack_purchase_history_conflict' USING ERRCODE='P0001';
  END IF;
  RETURN NEW;
END; $$;
CREATE TRIGGER pack_purchase_transaction_protected BEFORE UPDATE ON pack_purchase_transactions
FOR EACH ROW EXECUTE FUNCTION protect_pack_purchase_transaction();
CREATE TRIGGER pack_purchase_transaction_no_delete BEFORE DELETE ON pack_purchase_transactions
FOR EACH ROW EXECUTE FUNCTION immutable_ledger();
