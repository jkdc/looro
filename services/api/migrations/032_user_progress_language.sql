-- Progress is per learning language: the same item id can be earned in several
-- languages (shared conversation-theme ids, "free" assessments), so language
-- joins the primary key. Rows written before languages existed backfill as
-- 'legacy' (content slugs like "en-foundations-…" stay unambiguous; ambiguous
-- ones count for no language). The allowlist mirrors the app's language
-- registry — PROGRESS_LANGUAGES in src/progress.ts pins the same list.
ALTER TABLE user_progress ADD COLUMN language text NOT NULL DEFAULT 'legacy';
ALTER TABLE user_progress DROP CONSTRAINT user_progress_pkey;
ALTER TABLE user_progress ADD PRIMARY KEY (account_id, kind, item_id, language);
ALTER TABLE user_progress ADD CONSTRAINT user_progress_language_check
  CHECK (language IN ('nb','es','en','fr','de','it','pt','zh','legacy'));
