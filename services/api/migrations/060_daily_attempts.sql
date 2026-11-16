ALTER TABLE daily_config ADD COLUMN max_attempts_per_day integer NOT NULL DEFAULT 1 CHECK (max_attempts_per_day BETWEEN 1 AND 5);

CREATE TABLE daily_attempts (
  id uuid PRIMARY KEY,
  account_id uuid NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
  package_id uuid NOT NULL REFERENCES daily_packages(id) ON DELETE RESTRICT,
  language text NOT NULL,
  editorial_date date NOT NULL,
  attempt_number integer NOT NULL CHECK (attempt_number BETWEEN 1 AND 5),
  explanation_language text NOT NULL CHECK (explanation_language IN ('en','es')),
  daily_consent_version integer NOT NULL CHECK (daily_consent_version=1),
  state text NOT NULL CHECK (state IN ('in_progress','completed','withdrawn')),
  revision bigint NOT NULL DEFAULT 0 CHECK (revision BETWEEN 0 AND 9007199254740991),
  started_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  stage_state jsonb NOT NULL CHECK (jsonb_typeof(stage_state)='object'),
  active_ms bigint NOT NULL DEFAULT 0 CHECK (active_ms >= 0),
  stop_reason text CHECK (stop_reason IS NULL OR stop_reason IN ('learner_choice','natural_close')),
  review_reservation_id uuid REFERENCES daily_cost_reservations(id) ON DELETE SET NULL,
  review_state text NOT NULL DEFAULT 'not_started' CHECK (review_state IN ('not_started','queued','running','ready','partial','failed','unknown')),
  review_id uuid,
  input_hash text CHECK (input_hash IS NULL OR input_hash ~ '^[0-9a-f]{64}$'),
  UNIQUE(account_id,language,editorial_date,attempt_number),
  FOREIGN KEY(package_id,language,editorial_date) REFERENCES daily_packages(id,language,editorial_date),
  CHECK (updated_at >= started_at),
  CHECK ((state='in_progress' AND stop_reason IS NULL) OR (state='completed' AND stop_reason IS NOT NULL))
);
CREATE INDEX daily_attempts_history ON daily_attempts(account_id,language,started_at DESC,id DESC);
CREATE INDEX daily_attempts_daily_slot ON daily_attempts(account_id,language,editorial_date,attempt_number);

ALTER TABLE daily_jobs ADD CONSTRAINT daily_jobs_attempt_fk FOREIGN KEY(attempt_id) REFERENCES daily_attempts(id) ON DELETE SET NULL;
CREATE UNIQUE INDEX daily_jobs_one_review_per_attempt ON daily_jobs(attempt_id) WHERE kind='review' AND attempt_id IS NOT NULL;

CREATE TABLE daily_attempt_events (
  attempt_id uuid NOT NULL REFERENCES daily_attempts(id) ON DELETE CASCADE,
  event_id uuid NOT NULL,
  request_hash text NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
  expected_revision bigint NOT NULL CHECK (expected_revision BETWEEN 0 AND 9007199254740991),
  accepted_revision bigint NOT NULL CHECK (accepted_revision > 0),
  event_type text NOT NULL CHECK (event_type ~ '^[a-z_]{1,64}$'),
  body jsonb NOT NULL CHECK (jsonb_typeof(body)='object'),
  response jsonb NOT NULL CHECK (jsonb_typeof(response)='object'),
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY(attempt_id,event_id),
  UNIQUE(attempt_id,accepted_revision)
);

CREATE TABLE daily_attempt_request_keys (
  account_id uuid NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
  operation text NOT NULL CHECK (operation IN ('create_attempt')),
  idempotency_key uuid NOT NULL,
  request_hash text NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
  http_status integer NOT NULL CHECK (http_status BETWEEN 200 AND 299),
  response jsonb NOT NULL CHECK (jsonb_typeof(response)='object'),
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY(account_id,operation,idempotency_key)
);

CREATE TABLE daily_voice_segments (
  id uuid PRIMARY KEY,
  attempt_id uuid NOT NULL REFERENCES daily_attempts(id) ON DELETE CASCADE,
  hosted_session_id uuid NOT NULL UNIQUE REFERENCES hosted_sessions(id) ON DELETE RESTRICT,
  sequence integer NOT NULL CHECK (sequence>=0),
  state text NOT NULL CHECK (state IN ('active','closed','unknown','withdrawn')),
  consumed_ms bigint NOT NULL DEFAULT 0 CHECK (consumed_ms>=0),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(attempt_id,sequence)
);
CREATE UNIQUE INDEX daily_voice_one_active_segment ON daily_voice_segments(attempt_id) WHERE state='active';

CREATE TABLE daily_review_inputs (
  attempt_id uuid PRIMARY KEY REFERENCES daily_attempts(id) ON DELETE CASCADE,
  input_hash text NOT NULL CHECK (input_hash ~ '^[0-9a-f]{64}$'),
  snapshot jsonb NOT NULL CHECK (jsonb_typeof(snapshot)='object'),
  frozen_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE daily_reviews (
  id uuid PRIMARY KEY,
  attempt_id uuid NOT NULL UNIQUE REFERENCES daily_attempts(id) ON DELETE CASCADE,
  input_hash text NOT NULL CHECK (input_hash ~ '^[0-9a-f]{64}$'),
  rubric_version text NOT NULL CHECK (rubric_version ~ '^[a-z0-9_-]{1,64}$'),
  result jsonb NOT NULL CHECK (jsonb_typeof(result)='object' AND octet_length(result::text)<=32768),
  created_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE daily_attempts ADD CONSTRAINT daily_attempt_review_fk FOREIGN KEY(review_id) REFERENCES daily_reviews(id) ON DELETE SET NULL;

CREATE FUNCTION protect_daily_immutable_rows() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP='DELETE' AND pg_trigger_depth()>1 THEN RETURN OLD; END IF;
  RAISE EXCEPTION 'daily_immutable_record';
END $$;
CREATE TRIGGER daily_attempt_events_immutable BEFORE UPDATE OR DELETE ON daily_attempt_events FOR EACH ROW EXECUTE FUNCTION protect_daily_immutable_rows();
CREATE TRIGGER daily_review_inputs_immutable BEFORE UPDATE OR DELETE ON daily_review_inputs FOR EACH ROW EXECUTE FUNCTION protect_daily_immutable_rows();
CREATE TRIGGER daily_reviews_immutable BEFORE UPDATE OR DELETE ON daily_reviews FOR EACH ROW EXECUTE FUNCTION protect_daily_immutable_rows();
