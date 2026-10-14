-- Account-owned immutable exam snapshots and private recovery attempts.
CREATE TABLE level_test_attempt_snapshots (
  account_id uuid NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
  content_id text NOT NULL,
  content_hash text NOT NULL CHECK (content_hash ~ '^[a-f0-9]{64}$'),
  content_json jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (account_id, content_id),
  UNIQUE (account_id, content_hash),
  CHECK (content_id = 'sha256:' || content_hash)
);

CREATE TABLE level_test_attempt_snapshot_ids (
  account_id uuid NOT NULL,
  exam_id text NOT NULL CHECK (length(exam_id) BETWEEN 1 AND 128),
  content_id text NOT NULL,
  metadata_json jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (account_id, exam_id),
  FOREIGN KEY (account_id, content_id)
    REFERENCES level_test_attempt_snapshots(account_id, content_id) ON DELETE RESTRICT
);

CREATE TABLE level_test_attempts (
  account_id uuid NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
  attempt_id uuid NOT NULL,
  exam_id text NOT NULL,
  state text NOT NULL CHECK (state IN ('in_progress', 'completed')),
  position integer NOT NULL CHECK (position BETWEEN 0 AND 1000),
  revision integer NOT NULL CHECK (revision > 0),
  started_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  completed_at timestamptz,
  answers jsonb NOT NULL CHECK (jsonb_typeof(answers) = 'array'),
  result jsonb,
  attempt_json jsonb NOT NULL CHECK (jsonb_typeof(attempt_json) = 'object'),
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (account_id, attempt_id),
  FOREIGN KEY (account_id, exam_id)
    REFERENCES level_test_attempt_snapshot_ids(account_id, exam_id) ON DELETE RESTRICT,
  CHECK (updated_at >= started_at),
  CHECK ((state = 'in_progress' AND completed_at IS NULL AND result IS NULL) OR
         (state = 'completed' AND completed_at IS NOT NULL AND completed_at >= started_at AND result IS NOT NULL))
);

CREATE INDEX level_test_attempts_history
  ON level_test_attempts (account_id, updated_at DESC, attempt_id DESC);

CREATE FUNCTION protect_level_test_attempt_snapshots() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'UPDATE' THEN
    RAISE EXCEPTION 'level_test_attempt_snapshots_immutable' USING ERRCODE = 'P0001';
  END IF;
  RETURN OLD;
END; $$;
CREATE TRIGGER level_test_attempt_snapshots_no_update BEFORE UPDATE ON level_test_attempt_snapshots
  FOR EACH ROW EXECUTE FUNCTION protect_level_test_attempt_snapshots();

CREATE FUNCTION protect_level_test_snapshot_ids() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'UPDATE' THEN
    RAISE EXCEPTION 'level_test_attempt_snapshot_ids_immutable' USING ERRCODE = 'P0001';
  END IF;
  RETURN OLD;
END; $$;
CREATE TRIGGER level_test_attempt_snapshot_ids_no_update BEFORE UPDATE ON level_test_attempt_snapshot_ids
  FOR EACH ROW EXECUTE FUNCTION protect_level_test_snapshot_ids();

-- A published or retired generation keeps its exact variants; only publication
-- metadata may change. Retired generations remain addressable by their owners.
CREATE FUNCTION protect_level_test_week_content() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.id IS DISTINCT FROM NEW.id OR OLD.language_id IS DISTINCT FROM NEW.language_id OR
     OLD.level IS DISTINCT FROM NEW.level OR OLD.week_index IS DISTINCT FROM NEW.week_index OR
     OLD.source IS DISTINCT FROM NEW.source OR OLD.pool IS DISTINCT FROM NEW.pool OR
     OLD.variants IS DISTINCT FROM NEW.variants OR OLD.generator IS DISTINCT FROM NEW.generator OR
     OLD.model IS DISTINCT FROM NEW.model OR OLD.prompt_version IS DISTINCT FROM NEW.prompt_version OR
     OLD.judge_version IS DISTINCT FROM NEW.judge_version OR OLD.judge_mode IS DISTINCT FROM NEW.judge_mode OR
     OLD.review IS DISTINCT FROM NEW.review OR OLD.tokens_in IS DISTINCT FROM NEW.tokens_in OR
     OLD.tokens_out IS DISTINCT FROM NEW.tokens_out OR OLD.created_at IS DISTINCT FROM NEW.created_at THEN
    RAISE EXCEPTION 'level_test_week_content_immutable' USING ERRCODE = 'P0001';
  END IF;
  RETURN NEW;
END; $$;
CREATE TRIGGER level_test_weeks_content_immutable BEFORE UPDATE ON level_test_weeks
  FOR EACH ROW EXECUTE FUNCTION protect_level_test_week_content();
