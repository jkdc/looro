CREATE TABLE level_test_attempt_creation_receipts (
  account_id uuid NOT NULL,
  attempt_id uuid NOT NULL,
  request_hash text NOT NULL CHECK (request_hash ~ '^[a-f0-9]{64}$'),
  ack_json jsonb NOT NULL CHECK (jsonb_typeof(ack_json) = 'object'),
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (account_id, attempt_id),
  FOREIGN KEY (account_id, attempt_id)
    REFERENCES level_test_attempts(account_id, attempt_id) ON DELETE CASCADE
);

CREATE FUNCTION protect_level_test_attempt_creation_receipts() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.account_id IS DISTINCT FROM NEW.account_id OR OLD.attempt_id IS DISTINCT FROM NEW.attempt_id OR
     OLD.request_hash IS DISTINCT FROM NEW.request_hash OR OLD.ack_json IS DISTINCT FROM NEW.ack_json OR
     OLD.created_at IS DISTINCT FROM NEW.created_at THEN
    RAISE EXCEPTION 'level_test_attempt_creation_receipt_immutable' USING ERRCODE = 'P0001';
  END IF;
  RETURN NEW;
END; $$;
CREATE TRIGGER level_test_attempt_creation_receipts_immutable
  BEFORE UPDATE ON level_test_attempt_creation_receipts
  FOR EACH ROW EXECUTE FUNCTION protect_level_test_attempt_creation_receipts();
