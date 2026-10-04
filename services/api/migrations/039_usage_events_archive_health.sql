-- The archive_health heartbeat telemetry (one coarse, content-free event per
-- install per day), mirrored by USAGE_CATALOG in src/usage.ts and the native
-- UsageEvents catalogs; the pinning test keeps the SQL list and the TS
-- catalog in lockstep. The props are ordinal buckets decided by the client —
-- the database-size bracket and the session-count bracket, never exact values.
ALTER TABLE usage_events DROP CONSTRAINT usage_events_name_check;
ALTER TABLE usage_events ADD CONSTRAINT usage_events_name_check CHECK (name IN ('app_open','session_started','session_ended','session_resumed',
  'theme_selected','lesson_started','lesson_completed','assessment_completed','practice_completed',
  'word_lookup','replay_used','tts_speed_changed','help_requested','report_requested','paywall_viewed',
  'exercise_completed','listening_completed','scenario_started','scenario_completed','archive_health'));
