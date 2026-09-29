-- Content-free feature-usage events ingested from the apps and rendered by the
-- admin panel. Event names are a closed catalog (mirrored by USAGE_CATALOG in
-- src/usage.ts; a test pins both lists together), props carry only catalog ids
-- and coarse buckets — never user text. Rows cannot be rewritten in place;
-- retention deletion happens server-side after 180 days.
CREATE TABLE usage_events (
  id uuid PRIMARY KEY,
  client_event_id uuid NOT NULL,
  account_id uuid NOT NULL REFERENCES accounts(id),
  name text NOT NULL CHECK (name IN ('app_open','session_started','session_ended','session_resumed',
    'theme_selected','lesson_started','lesson_completed','assessment_completed','practice_completed',
    'word_lookup','replay_used','tts_speed_changed','help_requested','report_requested','paywall_viewed')),
  platform text NOT NULL CHECK (platform IN ('android','ios')),
  app_version text NOT NULL CHECK (char_length(app_version) BETWEEN 1 AND 32),
  props jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(props) = 'object'),
  client_at timestamptz NOT NULL CHECK (client_at > '2025-01-01' AND client_at <= created_at + interval '2 days'),
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX usage_events_client_id_idx ON usage_events(client_event_id);
CREATE INDEX usage_events_name_time_idx ON usage_events(name, created_at);
CREATE INDEX usage_events_account_time_idx ON usage_events(account_id, created_at);

-- Updates would let usage history be rewritten after the fact; deletes must stay
-- possible for the retention prune, so unlike the ledgers this guards UPDATE only.
CREATE FUNCTION protect_usage_events() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'usage events are immutable';
END; $$;
CREATE TRIGGER usage_events_immutable BEFORE UPDATE ON usage_events
  FOR EACH ROW EXECUTE FUNCTION protect_usage_events();
