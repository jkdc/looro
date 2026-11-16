CREATE TABLE daily_packages (
  id uuid PRIMARY KEY,
  language text NOT NULL CHECK (language ~ '^[a-z]{2}(-[A-Z]{2})?$'),
  editorial_date date NOT NULL,
  schema_version integer NOT NULL CHECK (schema_version = 1),
  profile_version text NOT NULL CHECK (profile_version ~ '^[a-z0-9_-]{1,64}$'),
  state text NOT NULL CHECK (state IN ('draft','validating','ready','published','withdrawn','rejected')),
  revision bigint NOT NULL DEFAULT 0 CHECK (revision BETWEEN 0 AND 9007199254740991),
  public_payload jsonb,
  private_payload jsonb,
  content_hash text CHECK (content_hash IS NULL OR content_hash ~ '^[0-9a-f]{64}$'),
  artifact_hash text CHECK (artifact_hash IS NULL OR artifact_hash ~ '^[0-9a-f]{64}$'),
  text_hash text CHECK (text_hash IS NULL OR text_hash ~ '^[0-9a-f]{64}$'),
  judge_state text NOT NULL DEFAULT 'pending' CHECK (judge_state IN ('pending','passed','failed','unknown')),
  judge_artifact_hash text CHECK (judge_artifact_hash IS NULL OR judge_artifact_hash ~ '^[0-9a-f]{64}$'),
  audio_state text NOT NULL DEFAULT 'pending' CHECK (audio_state IN ('pending','passed','failed','unknown')),
  audio_artifact_hash text CHECK (audio_artifact_hash IS NULL OR audio_artifact_hash ~ '^[0-9a-f]{64}$'),
  audio_id uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (id, language, editorial_date),
  CHECK (state NOT IN ('ready','published') OR
    (public_payload IS NOT NULL AND private_payload IS NOT NULL AND content_hash IS NOT NULL AND artifact_hash IS NOT NULL AND text_hash IS NOT NULL))
);

CREATE TABLE daily_audio (
  id uuid PRIMARY KEY,
  package_id uuid NOT NULL REFERENCES daily_packages(id) ON DELETE CASCADE,
  mime_type text NOT NULL CHECK (mime_type = 'audio/mpeg'),
  duration_ms integer NOT NULL CHECK (duration_ms BETWEEN 60000 AND 90000),
  byte_length integer NOT NULL CHECK (byte_length BETWEEN 1 AND 3145728),
  sha256 text NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
  data bytea NOT NULL CHECK (octet_length(data) BETWEEN 1 AND 3145728),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (id, package_id)
);

ALTER TABLE daily_packages ADD CONSTRAINT daily_packages_audio_fk
  FOREIGN KEY (audio_id, id) REFERENCES daily_audio(id, package_id) DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE daily_publications (
  language text NOT NULL,
  editorial_date date NOT NULL,
  package_id uuid NOT NULL,
  revision bigint NOT NULL DEFAULT 1 CHECK (revision > 0),
  consumed_at timestamptz,
  withdrawn_at timestamptz,
  published_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (language, editorial_date),
  FOREIGN KEY (package_id, language, editorial_date) REFERENCES daily_packages(id, language, editorial_date)
);

CREATE TABLE daily_budget_limits (
  cost_center text NOT NULL CHECK (cost_center IN ('content','review')),
  budget_date date NOT NULL,
  max_nano_usd bigint NOT NULL CHECK (max_nano_usd >= 0),
  revision bigint NOT NULL DEFAULT 0 CHECK (revision >= 0),
  PRIMARY KEY (cost_center, budget_date)
);

CREATE TABLE daily_jobs (
  id uuid PRIMARY KEY,
  kind text NOT NULL CHECK (kind IN ('content','review')),
  binding_id uuid NOT NULL,
  language text NOT NULL CHECK (language ~ '^[a-z]{2}(-[A-Z]{2})?$'),
  editorial_date date,
  attempt_id uuid,
  state text NOT NULL CHECK (state IN ('reserved','queued','running','succeeded','failed','unknown','cancelled')),
  revision bigint NOT NULL DEFAULT 0 CHECK (revision BETWEEN 0 AND 9007199254740991),
  lease_owner text,
  lease_until timestamptz,
  config_snapshot jsonb NOT NULL,
  error_code text CHECK (error_code IS NULL OR error_code ~ '^[a-z0-9_]{1,80}$'),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CHECK ((kind='content' AND editorial_date IS NOT NULL) OR kind='review')
);
CREATE UNIQUE INDEX daily_jobs_one_binding ON daily_jobs(kind, binding_id);
CREATE UNIQUE INDEX daily_jobs_one_content_date ON daily_jobs(language,editorial_date) WHERE kind='content';
CREATE INDEX daily_jobs_claim_order ON daily_jobs(created_at, id) WHERE state='queued';

CREATE TABLE daily_cost_reservations (
  id uuid PRIMARY KEY,
  job_id uuid NOT NULL REFERENCES daily_jobs(id),
  cost_center text NOT NULL CHECK (cost_center IN ('content','review')),
  budget_date date NOT NULL,
  binding_id uuid NOT NULL,
  max_nano_usd bigint NOT NULL CHECK (max_nano_usd > 0),
  settled_nano_usd bigint CHECK (settled_nano_usd IS NULL OR settled_nano_usd >= 0),
  state text NOT NULL CHECK (state IN ('held','settled','released','unknown')),
  created_at timestamptz NOT NULL DEFAULT now(),
  settled_at timestamptz,
  UNIQUE (cost_center, binding_id)
);
CREATE INDEX daily_cost_reservations_budget ON daily_cost_reservations(cost_center,budget_date,state);

CREATE TABLE daily_provider_operations (
  id uuid PRIMARY KEY,
  job_id uuid NOT NULL REFERENCES daily_jobs(id),
  phase text NOT NULL CHECK (phase IN ('generate','judge','tts','audio_check','review')),
  dispatch_key text NOT NULL UNIQUE,
  state text NOT NULL CHECK (state IN ('reserved','sending','succeeded','failed','unknown')),
  reservation_id uuid REFERENCES daily_cost_reservations(id),
  external_reference text CHECK (external_reference IS NULL OR external_reference ~ '^[A-Za-z0-9_-]{1,128}$'),
  result_hash text CHECK (result_hash IS NULL OR result_hash ~ '^[0-9a-f]{64}$'),
  usage jsonb,
  sent_at timestamptz,
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE daily_config (
  singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
  revision bigint NOT NULL DEFAULT 0 CHECK (revision >= 0),
  read_enabled boolean NOT NULL DEFAULT false,
  new_attempts_enabled boolean NOT NULL DEFAULT false,
  generation_enabled boolean NOT NULL DEFAULT false,
  auto_publish_enabled boolean NOT NULL DEFAULT false,
  enabled_languages text[] NOT NULL DEFAULT ARRAY['en']::text[] CHECK (enabled_languages <@ ARRAY['en']::text[]),
  content_budget_nano_usd bigint NOT NULL DEFAULT 0 CHECK (content_budget_nano_usd >= 0),
  review_budget_nano_usd bigint NOT NULL DEFAULT 0 CHECK (review_budget_nano_usd >= 0),
  retention_days integer NOT NULL DEFAULT 365 CHECK (retention_days BETWEEN 30 AND 3650),
  updated_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO daily_config(singleton) VALUES (true) ON CONFLICT DO NOTHING;

CREATE FUNCTION prevent_ready_daily_package_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.state IN ('ready','published','withdrawn') AND
    (NEW.public_payload IS DISTINCT FROM OLD.public_payload OR NEW.private_payload IS DISTINCT FROM OLD.private_payload OR
     NEW.content_hash IS DISTINCT FROM OLD.content_hash OR NEW.artifact_hash IS DISTINCT FROM OLD.artifact_hash OR
     NEW.text_hash IS DISTINCT FROM OLD.text_hash OR NEW.audio_id IS DISTINCT FROM OLD.audio_id OR
     NEW.judge_artifact_hash IS DISTINCT FROM OLD.judge_artifact_hash OR NEW.audio_artifact_hash IS DISTINCT FROM OLD.audio_artifact_hash)
  THEN RAISE EXCEPTION 'daily_package_payload_immutable'; END IF;
  IF (OLD.state='ready' AND NEW.state NOT IN ('ready','published','withdrawn')) OR
     (OLD.state='published' AND NEW.state NOT IN ('published','withdrawn')) OR
     (OLD.state='withdrawn' AND NEW.state<>'withdrawn')
  THEN RAISE EXCEPTION 'daily_package_state_terminal'; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER daily_packages_immutable_ready BEFORE UPDATE ON daily_packages
  FOR EACH ROW EXECUTE FUNCTION prevent_ready_daily_package_mutation();

CREATE FUNCTION prevent_published_daily_audio_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF EXISTS (SELECT 1 FROM daily_packages WHERE id=OLD.package_id AND state IN ('ready','published','withdrawn'))
  THEN RAISE EXCEPTION 'daily_audio_immutable'; END IF;
  RETURN OLD;
END $$;
CREATE TRIGGER daily_audio_immutable_update BEFORE UPDATE OR DELETE ON daily_audio
  FOR EACH ROW EXECUTE FUNCTION prevent_published_daily_audio_mutation();
