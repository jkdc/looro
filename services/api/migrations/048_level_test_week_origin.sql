-- Who triggered the generation of each week: an operator from the panel or the
-- automatic worker (scheduler/CLI). Rows created before this migration keep
-- 'unknown' instead of inventing provenance.
ALTER TABLE level_test_weeks
  ADD COLUMN origin text NOT NULL DEFAULT 'unknown'
    CHECK (origin IN ('user_operator','automatic_worker','unknown'));

COMMENT ON COLUMN level_test_weeks.origin IS
  'Who triggered the generation: user_operator (panel), automatic_worker (scheduler/CLI), unknown (pre-048).';
