-- Level-tests LLM (level-tests-llm): provider configs with encrypted keys,
-- publication settings, and one immutable row per generated weekly test.

CREATE TABLE llm_provider_configs (
  id uuid PRIMARY KEY,
  provider text NOT NULL CHECK (provider IN ('openai','glm','opencode')),
  label text NOT NULL,
  base_url text NOT NULL,
  model text NOT NULL,
  api_key_encrypted text NOT NULL,         -- base64(iv || ciphertext+tag), AES-256-GCM
  is_active boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
-- At most one active configuration at a time, and one row per provider endpoint.
CREATE UNIQUE INDEX llm_provider_configs_one_active
  ON llm_provider_configs ((is_active)) WHERE is_active;
CREATE UNIQUE INDEX llm_provider_configs_endpoint
  ON llm_provider_configs (provider, base_url, model);

CREATE TABLE level_test_settings (
  singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
  publish_mode text NOT NULL DEFAULT 'review' CHECK (publish_mode IN ('review','auto')),
  verify_mode text NOT NULL DEFAULT 'flag' CHECK (verify_mode IN ('flag','strict','off')),
  variants int NOT NULL DEFAULT 3 CHECK (variants BETWEEN 1 AND 5)
);

CREATE TABLE level_test_weeks (
  id uuid PRIMARY KEY,
  language_id text NOT NULL,
  level text NOT NULL,
  week_index int NOT NULL,                  -- ISO year*100 + week
  source text NOT NULL DEFAULT 'llm' CHECK (source = 'llm'),
  status text NOT NULL DEFAULT 'draft' CHECK (status IN ('draft','approved','retired')),
  pool jsonb NOT NULL,                      -- validated LLM pool (pre-variant)
  variants jsonb NOT NULL,                  -- K composed exams
  generator text NOT NULL,
  model text NOT NULL,
  prompt_version int NOT NULL,
  judge_version int,
  judge_mode text,
  review jsonb,                             -- per-item judge verdicts (flag mode)
  tokens_in bigint,
  tokens_out bigint,
  created_at timestamptz NOT NULL DEFAULT now(),
  approved_at timestamptz,
  approved_by text
);
-- One live (non-retired) week per language+level+week; retired rows remain as
-- audit history and do not block a regenerated week.
CREATE UNIQUE INDEX level_test_weeks_one_live
  ON level_test_weeks (language_id, level, week_index) WHERE status <> 'retired';
CREATE INDEX level_test_weeks_serving ON level_test_weeks (language_id, level, week_index)
  WHERE status = 'approved';

CREATE FUNCTION protect_level_test_weeks() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'UPDATE' AND OLD.status = 'approved' AND NEW.status <> 'approved' THEN
    -- Approved weeks may only leave the serving set via 'retired' (audit keeps the rest).
    IF NEW.status <> 'retired' THEN
      RAISE EXCEPTION 'level_test_weeks_history_conflict' USING ERRCODE = 'P0001';
    END IF;
  END IF;
  IF TG_OP = 'UPDATE' AND OLD.status <> 'approved' AND NEW.status = 'approved'
     AND (OLD.approved_at IS NOT NULL AND NEW.approved_at IS NOT NULL AND OLD.approved_at <> NEW.approved_at) THEN
    RAISE EXCEPTION 'level_test_weeks_history_conflict' USING ERRCODE = 'P0001';
  END IF;
  RETURN NEW;
END; $$;
CREATE TRIGGER level_test_weeks_protected BEFORE UPDATE ON level_test_weeks
  FOR EACH ROW EXECUTE FUNCTION protect_level_test_weeks();
CREATE TRIGGER level_test_weeks_no_delete BEFORE DELETE ON level_test_weeks
  FOR EACH ROW EXECUTE FUNCTION immutable_ledger();
