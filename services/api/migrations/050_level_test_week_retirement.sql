-- Withdrawing an approved week is an operator decision. The motive lives on the
-- row itself so the review page explains a retired week without joining the
-- audit ledger. A force regeneration also retires the superseded row; that path
-- leaves these columns null because the level_test.regenerate audit explains it.
ALTER TABLE level_test_weeks
  ADD COLUMN retired_at timestamptz,
  ADD COLUMN retired_by text,
  ADD COLUMN retired_reason text;

ALTER TABLE level_test_weeks
  ADD CONSTRAINT level_test_weeks_retired_reason_length
    CHECK (retired_reason IS NULL OR length(retired_reason) BETWEEN 3 AND 500);

COMMENT ON COLUMN level_test_weeks.retired_reason IS
  'Operator motive for a manual retirement; null when a force regeneration superseded the row.';
