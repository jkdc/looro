CREATE TABLE level_test_generation_jobs (
  language_id text NOT NULL CHECK (language_id IN ('en','es','fr','de','it','pt','nb','zh')),
  level text NOT NULL CHECK (level IN ('A1','A2','B1','B2','C1')),
  week_index integer NOT NULL CHECK (week_index >= 0),
  state text NOT NULL CHECK (state IN ('running','finished','failed','unknown')),
  attempts integer NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 100000),
  error_code text CHECK (error_code IS NULL OR error_code ~ '^[a-z0-9_]{1,80}$'),
  started_at timestamptz,
  finished_at timestamptz,
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (language_id, level, week_index)
);
