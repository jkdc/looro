-- ===========================================================================
-- Looro/Palique — canonical learning database, schema v2
-- ===========================================================================

-- Version history
-- ---------------
--   v1  Initial frozen schema: ten tables (meta, preferences, sessions,
--       fragments, expressions, lesson_state, completions, skill_errors,
--       usage_outbox, assessment_tickets), one foreign key, four indexes.
--   v2  Full-text search over conversation transcripts: the FTS5
--       external-content virtual table fragments_fts (content = fragments)
--       plus the three triggers that keep it in sync, and a one-time
--       'rebuild' that indexes every fragment a v1 database already held.
--       No existing table or column changed.
--
-- Fresh databases run every statement below in order and end at
-- PRAGMA user_version = 2. Openers upgrade an existing database by applying
-- only the statements between its own user_version's PRAGMA and the next
-- one (see the v2 block's own comment), then setting user_version.
--
-- Source of truth for every field: apps/android/.../core/Models.kt
-- (SessionRecord, Fragment, ExpressionState, CompletionMark, Preferences,
-- LessonState, SkillError, Archive.masteryCache, ScenarioReview) and
-- UsageMetrics.kt (PendingUsageEvent) / FinalAssessmentQueue.kt
-- (FinalAssessmentTicket).
--
-- This file must execute VERBATIM on a fresh database from both platforms:
--   - Android: AndroidX SQLite (API 26+ ships SQLite 3.18, FTS5 available)
--   - iOS: GRDB
-- Therefore it uses only portable SQL: no STRICT tables, no generated
-- columns, no RETURNING, no WITHOUT ROWID. Openers run it once when
-- `PRAGMA user_version` is 0 and must then find it set to 2 (the script
-- ends with `PRAGMA user_version = 2;`).
--
-- Conventions
-- -----------
-- Timestamps  : Apple-epoch seconds as REAL (Double), i.e. Java/Swift
--               `timeIntervalSinceReferenceDate` = unix seconds - 978307200
--               (Models.kt `nowSeconds()`). The one exception in the source
--               data is PendingUsageEvent.at, a Long of unix milliseconds;
--               the store layer must convert it to Apple seconds REAL when
--               writing usage_outbox.at.
-- Booleans    : INTEGER 0/1. Columns whose Kotlin/Swift type is `Boolean?`
--               are NULLable INTEGER (NULL = "unset", distinct from false).
-- JSON columns: TEXT holding encoded JSON, documented per column. Write-once
--               payloads validated by ArchiveCodec/LearningEngine
--               (Assessment, TopicBrief, ScenarioReview, fragment edit
--               history, expression props) are stored denormalized on
--               purpose — do not normalize them without re-running the
--               cross-platform parity check.
-- language_id : NULL means a LEGACY entry (pre-multi-language archive):
--                 - expressions.language_id NULL  -> visible/adoptable in
--                   every language (RecallEngine semantics today);
--                 - completions.language_id NULL  -> counts as global
--                   activity in the progress ledger.
--               New writes always carry a language id.
-- Foreign keys: exactly one, fragments.session_id -> sessions.id
--               ON DELETE CASCADE. Openers MUST enable enforcement at
--               connection open: `PRAGMA foreign_keys = ON;`
--               (AndroidX: SQLiteOpenHelper.onConfigure;
--               GRDB: Configuration.foreignKeysEnabled = true).
--
-- Expressions primary key decision
-- --------------------------------
-- The logical identity of a saved expression is
--   (norm_key, language_id)  with language_id NULL on legacy rows.
-- `PRIMARY KEY (norm_key, COALESCE(language_id, ''))` is not valid SQL in a
-- table constraint (SQLite bans expressions there), and a generated column
-- is out for portability. Decision: declare
--   PRIMARY KEY (norm_key, language_id)
-- In a rowid table SQLite implements this as a UNIQUE index where NULLs are
-- distinct, so (a) legacy rows (language_id NULL) never collide with each
-- other or with language rows, and (b) two non-legacy rows with the same
-- norm_key AND language are rejected by the database. Full uniqueness
-- including legacy duplicates remains the repository layer's job at import/
-- merge time — exactly what ArchiveCodec.merge already enforces today via
-- RecallEngine.expressionKey. scripts/check_db_schema.py pins this shape.
--
-- NO `words` TABLE
-- ----------------
-- Words are a LearningEngine projection of course content + progress, not
-- user data; the learner's hidden words live in preferences.hidden_words.
-- The schema therefore has no words table, and the checker treats one as a
-- hard error.
--
-- meta keys
-- ---------
--   'schema_flags'    value = JSON object of schema feature flags.
--   'mastery:<key>'   one row per Archive.masteryCache entry (the cached
--                     mastery projection; keys are the map keys, e.g.
--                     per-skill ids). value = the Int encoded as TEXT.
-- ===========================================================================

CREATE TABLE meta (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

CREATE TABLE preferences (
    id                    INTEGER PRIMARY KEY CHECK (id = 1),
    learning_language_id  TEXT    NOT NULL,
    meaning_visible       INTEGER NOT NULL,
    meaning_language      TEXT    NOT NULL,
    session_minutes       INTEGER NOT NULL,
    hidden_words          TEXT    NOT NULL DEFAULT '[]',
    interests             TEXT    NOT NULL DEFAULT '',
    has_onboarded         INTEGER NOT NULL,
    ai_consent_version    INTEGER NULL,
    slow_speech           INTEGER NULL,
    listening_practice    INTEGER NULL,
    speech_rate           REAL    NULL,
    usage_metrics_enabled INTEGER NULL,
    stt_engine            TEXT    NULL,
    pronunciation_enabled INTEGER NULL
);

CREATE TABLE sessions (
    id                   TEXT PRIMARY KEY,
    language_id          TEXT    NOT NULL,
    provider_id          TEXT    NULL,
    started_at           REAL    NOT NULL,
    ended_at             REAL    NULL,
    theme_id             TEXT    NULL,
    title                TEXT    NOT NULL,
    voice_seconds        REAL    NOT NULL DEFAULT 0,
    usage_final          INTEGER NOT NULL DEFAULT 0,
    input_tokens         INTEGER NOT NULL DEFAULT 0,
    output_tokens        INTEGER NOT NULL DEFAULT 0,
    search_calls         INTEGER NOT NULL DEFAULT 0,
    end_reason           TEXT    NULL,
    mode                 TEXT    NULL,
    lesson_id            TEXT    NULL,
    scenario_id          TEXT    NULL,
    assessments_json     TEXT    NOT NULL DEFAULT '[]',
    translations_json    TEXT    NOT NULL DEFAULT '{}',
    topics_json          TEXT    NOT NULL DEFAULT '[]',
    scenario_review_json TEXT    NULL,
    review_created_at    REAL    NULL
);

-- sessions JSON columns (TEXT):
--   assessments_json     : JSON array of Assessment (write-once, validated)
--   translations_json    : JSON object {fragmentID: translation}
--   topics_json          : JSON array of TopicBrief (write-once, validated)
--   scenario_review_json : JSON ScenarioReview for mode="scenario" sessions,
--                          NULL otherwise; review_created_at mirrors
--                          ScenarioReview.createdAt for cheap queries.

CREATE TABLE fragments (
    id              TEXT PRIMARY KEY,
    session_id      TEXT    NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    sort_index      INTEGER NOT NULL,
    speaker         TEXT    NOT NULL,
    text            TEXT    NOT NULL,
    revision        INTEGER NOT NULL,
    previous_texts  TEXT    NOT NULL DEFAULT '[]',
    start_ms        INTEGER NOT NULL,
    end_ms          INTEGER NOT NULL,
    received_at     REAL    NOT NULL,
    meaning_visible INTEGER NOT NULL DEFAULT 0,
    typed           INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX fragments_session ON fragments(session_id, sort_index);

-- fragments JSON columns (TEXT):
--   previous_texts : JSON array of the fragment's earlier texts, oldest
--                    first (edit history; empty when revision = 0).

CREATE TABLE expressions (
    norm_key    TEXT    NOT NULL,
    language_id TEXT    NULL,
    text        TEXT    NOT NULL,
    meaning     TEXT    NOT NULL,
    source      TEXT    NOT NULL,
    bars        INTEGER NOT NULL DEFAULT 0,
    attempts    INTEGER NOT NULL DEFAULT 0,
    last_seen   REAL    NOT NULL DEFAULT 0,
    due_at      REAL    NOT NULL,
    lesson_id   TEXT    NULL,
    PRIMARY KEY (norm_key, language_id)
);

CREATE INDEX expressions_due ON expressions(language_id, due_at);

-- expressions:
--   norm_key = RecallEngine.normalizeKey(text) (trim + lowercase);
--   source is "lesson" or "lookup"; NULL language_id = legacy entry
--   visible in every language (see header).

CREATE TABLE lesson_state (
    lesson_id TEXT PRIMARY KEY,
    attempts  INTEGER NOT NULL,
    last_seen REAL    NOT NULL
);

-- lesson_state: v1 persists attempts/last_seen only. LessonState.bars and
-- LessonState.dueAt stay zero until the evidence-based progress change —
-- they are intentionally not stored yet.

CREATE TABLE completions (
    kind        TEXT    NOT NULL,
    item_id     TEXT    NOT NULL,
    at          REAL    NOT NULL,
    best_score  INTEGER NOT NULL DEFAULT 0,
    language_id TEXT    NULL,
    PRIMARY KEY (kind, item_id, at)
);

CREATE INDEX completions_lang ON completions(language_id, at DESC);

CREATE TABLE skill_errors (
    skill_id    TEXT PRIMARY KEY,
    count       INTEGER NOT NULL,
    last_at     REAL    NOT NULL,
    last_prompt TEXT    NOT NULL DEFAULT ''
);

CREATE TABLE usage_outbox (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    name       TEXT    NOT NULL,
    at         REAL    NOT NULL,
    props_json TEXT    NOT NULL DEFAULT '{}',
    dispatched INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX outbox_pending ON usage_outbox(dispatched, at);

-- usage_outbox: durable PendingUsageEvent queue (content-free usage events).
-- at is Apple-epoch seconds REAL — convert from PendingUsageEvent's Long
-- unix milliseconds when writing. props_json is the event's JSON props
-- object ('{}' when empty).

CREATE TABLE assessment_tickets (
    session_id      TEXT PRIMARY KEY,
    attempts        INTEGER NOT NULL DEFAULT 0,
    last_attempt_at REAL    NULL
);

-- assessment_tickets: FinalAssessmentTicket, one per session at most
-- (FinalAssessmentRecovery keeps the newest). passageID/revisionKey are not
-- stored: like FinalAssessmentTicket.matches, a ticket whose transcript
-- changed after enqueue silently stops matching, so v1 loses stale-ticket
-- detection rather than surfacing it.

PRAGMA user_version = 1;

-- ===========================================================================
-- v2 — full-text search over conversation transcripts
-- ===========================================================================
--
-- fragments_fts is an EXTERNAL-CONTENT FTS5 index over fragments.text: it
-- stores only the inverted index (the text itself stays in fragments), so
-- it adds no duplicate content and must be kept in sync by the three
-- triggers below — exactly the "external content table" pattern from the
-- SQLite FTS5 documentation. The upgrade path (a database already at
-- user_version 1) applies exactly this block: the CREATE VIRTUAL TABLE,
-- the three triggers, and the one-time 'rebuild' command that indexes
-- every fragment the database already held. Fresh installs run the same
-- statements (the 'rebuild' is a no-op on an empty fragments table).

CREATE VIRTUAL TABLE fragments_fts USING fts5(
    text,
    content='fragments',
    content_rowid='rowid'
);

CREATE TRIGGER fragments_fts_insert AFTER INSERT ON fragments BEGIN
    INSERT INTO fragments_fts(rowid, text) VALUES (new.rowid, new.text);
END;

CREATE TRIGGER fragments_fts_delete AFTER DELETE ON fragments BEGIN
    INSERT INTO fragments_fts(fragments_fts, rowid, text)
        VALUES ('delete', old.rowid, old.text);
END;

CREATE TRIGGER fragments_fts_update AFTER UPDATE OF text ON fragments BEGIN
    INSERT INTO fragments_fts(fragments_fts, rowid, text)
        VALUES ('delete', old.rowid, old.text);
    INSERT INTO fragments_fts(rowid, text) VALUES (new.rowid, new.text);
END;

-- One-time index fill: 'rebuild' re-reads every fragments row through the
-- content option, so a v1→v2 upgrade can search the history it already had.
INSERT INTO fragments_fts(fragments_fts) VALUES('rebuild');

PRAGMA user_version = 2;
