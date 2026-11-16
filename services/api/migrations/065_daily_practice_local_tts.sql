-- Daily listening audio is synthesized by each client from the published transcript.
-- Keep legacy provider-operation rows for audit, but reject obsolete phases on new writes.
CREATE OR REPLACE FUNCTION prevent_ready_daily_package_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.state IN ('ready','published','withdrawn') AND
    (NEW.public_payload IS DISTINCT FROM OLD.public_payload OR NEW.private_payload IS DISTINCT FROM OLD.private_payload OR
     NEW.content_hash IS DISTINCT FROM OLD.content_hash OR NEW.artifact_hash IS DISTINCT FROM OLD.artifact_hash OR
     NEW.text_hash IS DISTINCT FROM OLD.text_hash OR NEW.judge_artifact_hash IS DISTINCT FROM OLD.judge_artifact_hash)
  THEN RAISE EXCEPTION 'daily_package_payload_immutable'; END IF;
  IF (OLD.state='ready' AND NEW.state NOT IN ('ready','published','withdrawn')) OR
     (OLD.state='published' AND NEW.state NOT IN ('published','withdrawn')) OR
     (OLD.state='withdrawn' AND NEW.state<>'withdrawn')
  THEN RAISE EXCEPTION 'daily_package_state_terminal'; END IF;
  RETURN NEW;
END $$;

DROP TRIGGER IF EXISTS daily_audio_immutable_update ON daily_audio;
DROP FUNCTION IF EXISTS prevent_published_daily_audio_mutation();
ALTER TABLE daily_packages DROP CONSTRAINT IF EXISTS daily_packages_audio_fk;
DROP TABLE IF EXISTS daily_audio;
ALTER TABLE daily_packages
  DROP COLUMN IF EXISTS audio_state,
  DROP COLUMN IF EXISTS audio_artifact_hash,
  DROP COLUMN IF EXISTS audio_id;

ALTER TABLE daily_provider_operations DROP CONSTRAINT IF EXISTS daily_provider_operations_phase_check;
ALTER TABLE daily_provider_operations
  ADD CONSTRAINT daily_provider_operations_phase_check CHECK (phase IN ('generate','judge','review')) NOT VALID;
