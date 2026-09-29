-- Per-user learning progress, merged from the apps: completed lessons, practised
-- conversation themes, exercise sets, listening texts and practice rounds. Unlike
-- usage_events this ledger carries content slugs (course/lesson/theme ids) because
-- it feeds the user's own progress screen, allows in-place updates (the merge is
-- monotonic: last_at/attempts/best_score keep the max, first_at the min), and has
-- no retention prune — a finished course is not an event, it is a state.
CREATE TABLE user_progress (
  account_id uuid NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
  kind text NOT NULL CHECK (kind IN ('lesson','conversation_theme','exercise_set','listening',
    'recall_round','words_practice','assessment')),
  item_id text NOT NULL CHECK (item_id ~ '^[a-z0-9][a-z0-9_-]{0,63}$'),
  first_at timestamptz NOT NULL CHECK (first_at > '2025-01-01'),
  last_at timestamptz NOT NULL CHECK (last_at >= first_at),
  attempts integer NOT NULL CHECK (attempts BETWEEN 1 AND 10000),
  best_score smallint NOT NULL DEFAULT 0 CHECK (best_score BETWEEN 0 AND 100),
  PRIMARY KEY (account_id, kind, item_id)
);
CREATE INDEX user_progress_account_recent_idx ON user_progress(account_id, last_at DESC);
