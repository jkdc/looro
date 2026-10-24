-- Preserve the exact blueprint contract used for each generated exam bank.
ALTER TABLE level_test_weeks
  ADD COLUMN blueprint_version integer,
  ADD COLUMN blueprint_hash text;

ALTER TABLE level_test_weeks
  ADD CONSTRAINT level_test_weeks_blueprint_provenance_check
  CHECK ((blueprint_version IS NULL AND blueprint_hash IS NULL) OR
         (blueprint_version IS NOT NULL AND blueprint_version > 0 AND blueprint_hash IS NOT NULL AND
          blueprint_hash ~ '^[a-f0-9]{64}$'));
