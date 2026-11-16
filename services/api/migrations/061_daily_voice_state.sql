CREATE FUNCTION sync_daily_voice_segment_state() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.state IS DISTINCT FROM OLD.state THEN
    UPDATE daily_voice_segments SET
      state=CASE WHEN NEW.state='closed' THEN 'closed' WHEN NEW.state='incomplete' THEN 'unknown' ELSE state END,
      consumed_ms=NEW.observed_ms
    WHERE hosted_session_id=NEW.id AND state='active';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER hosted_session_daily_voice_state
AFTER UPDATE OF state,observed_ms ON hosted_sessions
FOR EACH ROW EXECUTE FUNCTION sync_daily_voice_segment_state();
