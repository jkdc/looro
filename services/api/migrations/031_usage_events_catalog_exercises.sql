-- The usage catalog grows with the deterministic temario exercises and the
-- listening section (mirrored by USAGE_CATALOG in src/usage.ts; the pinning
-- test keeps the SQL list and the TS catalog in lockstep).
ALTER TABLE usage_events DROP CONSTRAINT usage_events_name_check;
ALTER TABLE usage_events ADD CONSTRAINT usage_events_name_check CHECK (name IN ('app_open','session_started','session_ended','session_resumed',
  'theme_selected','lesson_started','lesson_completed','assessment_completed','practice_completed',
  'word_lookup','replay_used','tts_speed_changed','help_requested','report_requested','paywall_viewed',
  'exercise_completed','listening_completed'));
