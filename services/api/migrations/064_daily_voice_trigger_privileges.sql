-- The hosted-session runtime role may settle voice activity but cannot write
-- daily attempt state. Keep the cross-table synchronization inside this
-- narrowly scoped trigger function rather than widening that role's grants.
CREATE OR REPLACE FUNCTION sync_daily_voice_segment_state() RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
BEGIN
  IF NEW.state IS DISTINCT FROM OLD.state THEN
    UPDATE public.daily_voice_segments SET
      state=CASE WHEN NEW.state='closed' THEN 'closed' WHEN NEW.state='incomplete' THEN 'unknown' ELSE state END,
      consumed_ms=NEW.observed_ms
    WHERE hosted_session_id=NEW.id AND state='active';
  END IF;
  RETURN NEW;
END $$;
