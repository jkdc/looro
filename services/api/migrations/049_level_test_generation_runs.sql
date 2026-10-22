-- One row per generation attempt instead of one row per language+level+week.
-- The old upsert overwrote the previous failure (error_details and timestamps)
-- on every retry, so the monitor could not explain what had happened before.
-- Existing rows keep their state as the first recorded run; `attempts` now
-- carries the ordinal of each run for its key.
ALTER TABLE level_test_generation_jobs ADD COLUMN id uuid NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE level_test_generation_jobs DROP CONSTRAINT level_test_generation_jobs_pkey;
ALTER TABLE level_test_generation_jobs ADD PRIMARY KEY (id);

CREATE INDEX level_test_generation_jobs_key_idx
  ON level_test_generation_jobs (language_id, level, week_index, attempts DESC);

COMMENT ON COLUMN level_test_generation_jobs.id IS
  'Run identity. Each attempt appends a row; previous failures are never overwritten.';
COMMENT ON COLUMN level_test_generation_jobs.attempts IS
  'Attempt ordinal for its language+level+week (1-based). A new run gets the next value.';
