-- Legacy rows deliberately remain null: provenance alone cannot recover a contract.
ALTER TABLE level_test_weeks ADD COLUMN blueprint_contract jsonb;
ALTER TABLE level_test_weeks ADD CONSTRAINT level_test_weeks_blueprint_contract_check
  CHECK (blueprint_contract IS NULL OR
    (jsonb_typeof(blueprint_contract) = 'object' AND blueprint_version IS NOT NULL AND
     blueprint_version = 2 AND blueprint_hash IS NOT NULL));

COMMENT ON COLUMN level_test_weeks.blueprint_contract IS
  'Complete generation-time blueprint; format 2 hashes recursively sorted object keys. Null legacy English drafts require regeneration; published snapshots are unchanged.';
