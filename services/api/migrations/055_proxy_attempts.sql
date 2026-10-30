-- Operational state for conservative external-outcome reconciliation; no request content.
CREATE TABLE proxy_attempts (
  id uuid PRIMARY KEY,
  account_id uuid NOT NULL REFERENCES accounts(id),
  rate_version text NOT NULL REFERENCES rate_versions(id),
  state text NOT NULL DEFAULT 'prepared' CHECK (state IN ('prepared','dispatched','settled','rejected','unknown')),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX proxy_attempts_pending ON proxy_attempts(updated_at) WHERE state IN ('prepared','dispatched','unknown');
ALTER TABLE proxy_usage ADD COLUMN attempt_id uuid UNIQUE REFERENCES proxy_attempts(id);
-- Preserve every state transition as immutable evidence, even while operational state changes.
CREATE TABLE proxy_attempt_events (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  attempt_id uuid NOT NULL REFERENCES proxy_attempts(id),
  state text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE FUNCTION audit_proxy_attempt() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'proxy attempts cannot be deleted'; END IF;
  IF TG_OP = 'UPDATE' THEN
    IF OLD.state IN ('settled','rejected','unknown') OR
       OLD.id <> NEW.id OR OLD.account_id <> NEW.account_id OR OLD.rate_version <> NEW.rate_version OR OLD.created_at <> NEW.created_at OR
       NOT ((OLD.state='prepared' AND NEW.state IN ('dispatched','rejected')) OR
            (OLD.state='dispatched' AND NEW.state IN ('settled','rejected','unknown'))) THEN
      RAISE EXCEPTION 'invalid proxy attempt transition';
    END IF;
  END IF;
  INSERT INTO proxy_attempt_events(attempt_id,state) VALUES(NEW.id,NEW.state);
  RETURN NEW;
END; $$;
CREATE TRIGGER proxy_attempt_changes AFTER INSERT OR UPDATE ON proxy_attempts FOR EACH ROW EXECUTE FUNCTION audit_proxy_attempt();
CREATE TRIGGER proxy_attempt_no_delete BEFORE DELETE ON proxy_attempts FOR EACH ROW EXECUTE FUNCTION audit_proxy_attempt();
CREATE TRIGGER proxy_attempt_events_immutable BEFORE UPDATE OR DELETE ON proxy_attempt_events FOR EACH ROW EXECUTE FUNCTION immutable_ledger();
