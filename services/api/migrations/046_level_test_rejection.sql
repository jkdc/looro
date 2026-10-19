ALTER TABLE level_test_weeks
  DROP CONSTRAINT level_test_weeks_status_check,
  ADD CONSTRAINT level_test_weeks_status_check
    CHECK (status IN ('draft', 'approved', 'rejected', 'retired'));
