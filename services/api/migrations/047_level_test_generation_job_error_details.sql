-- Generation jobs kept only the coarse error_code, which erased the section and
-- reason of the failure (`reading: only 2 passages (needed 4)` became just
-- `generation_failed`). The panel needs the real list to explain a red row.
--
-- Bounded on purpose: the writer sanitizes every entry (lowercase codes,
-- counts and punctuation only) so provider response bodies can never land here.
ALTER TABLE level_test_generation_jobs
  ADD COLUMN error_details jsonb NOT NULL DEFAULT '[]'::jsonb;

ALTER TABLE level_test_generation_jobs
  ADD CONSTRAINT level_test_generation_jobs_error_details_shape
  CHECK (jsonb_typeof(error_details) = 'array' AND jsonb_array_length(error_details) <= 10);

COMMENT ON COLUMN level_test_generation_jobs.error_details IS
  'Sanitized messages from the last generation attempt (section-prefixed codes and counts).';
