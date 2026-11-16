ALTER TABLE daily_packages
  ADD COLUMN judge_text_hash text CHECK (judge_text_hash IS NULL OR judge_text_hash ~ '^[0-9a-f]{64}$');

CREATE FUNCTION prevent_ready_daily_judge_text_hash_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.state IN ('ready','published','withdrawn') AND NEW.judge_text_hash IS DISTINCT FROM OLD.judge_text_hash
  THEN RAISE EXCEPTION 'daily_package_judge_evidence_immutable'; END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER daily_packages_judge_hash_immutable BEFORE UPDATE ON daily_packages
  FOR EACH ROW EXECUTE FUNCTION prevent_ready_daily_judge_text_hash_mutation();
