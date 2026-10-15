CREATE TABLE level_test_attempt_events (
  account_id uuid NOT NULL,
  event_id uuid NOT NULL,
  attempt_id uuid NOT NULL,
  revision integer NOT NULL CHECK (revision > 1),
  event_hash text NOT NULL CHECK (event_hash ~ '^[a-f0-9]{64}$'),
  ack_json jsonb NOT NULL CHECK (jsonb_typeof(ack_json) = 'object'),
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (account_id, event_id),
  FOREIGN KEY (account_id, attempt_id)
    REFERENCES level_test_attempts(account_id, attempt_id) ON DELETE CASCADE
);

CREATE INDEX level_test_attempt_events_attempt
  ON level_test_attempt_events(account_id, attempt_id, revision);
