-- Null means unreported, never a confirmed zero. Existing runs remain unknown.
ALTER TABLE level_test_generation_jobs
  ADD COLUMN tokens_in bigint,
  ADD COLUMN tokens_out bigint,
  ADD COLUMN usage_state text NOT NULL DEFAULT 'unknown';

ALTER TABLE level_test_generation_jobs
  ADD CONSTRAINT level_test_generation_usage_amounts_check CHECK (
    (tokens_in IS NULL OR tokens_in BETWEEN 0 AND 9007199254740991) AND
    (tokens_out IS NULL OR tokens_out BETWEEN 0 AND 9007199254740991)),
  ADD CONSTRAINT level_test_generation_usage_state_check CHECK (
    (usage_state = 'complete' AND tokens_in IS NOT NULL AND tokens_out IS NOT NULL) OR
    (usage_state = 'partial' AND (tokens_in IS NOT NULL OR tokens_out IS NOT NULL)) OR
    (usage_state = 'unknown' AND tokens_in IS NULL AND tokens_out IS NULL));

COMMENT ON COLUMN level_test_generation_jobs.usage_state IS
  'Completeness of reported provider usage, independent of run outcome. Partial amounts are known subtotals, not estimates. Checkpointed before and after each provider attempt.';
