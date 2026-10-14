-- ===========================================================================
-- Looro/Palique — canonical learning database, schema v3
-- ===========================================================================

-- Version history
-- ---------------
--   v1  Initial frozen schema: ten tables (meta, preferences, sessions,
--       fragments, expressions, lesson_state, completions, skill_errors,
--       usage_outbox, assessment_tickets), one foreign key, four indexes.
--   v2  Full-text search over conversation transcripts: the FTS4
--       external-content virtual table fragments_fts (content = fragments,
--       unicode61 tokenizer) plus the four triggers that keep it in sync,
--       and a one-time 'rebuild' that indexes every fragment a v1 database
--       already held. No existing table or column changed.
--   v3  Immutable, content-deduplicated exam snapshots with examId aliases;
--       durable attempts, revisioned answers, original results and a dedicated
--       sync outbox. Existing rows are untouched; legacy summaries are not
--       backfilled because their original questions and answers are unavailable.
--
-- Fresh databases run every statement below in order and end at
-- PRAGMA user_version = 3. Openers upgrade an existing database by applying
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
--   - Android: AndroidX SQLite (API 26+ ships SQLite 3.18; the framework
--     build enables the FTS3/FTS4 module with the unicode61 tokenizer — FTS5
--     is NOT in the framework build, which is why v2 uses fts4)
--   - iOS: GRDB
-- Therefore it uses only portable SQL: no STRICT tables, no generated
-- columns, no RETURNING, no WITHOUT ROWID. Openers run it once when
-- `PRAGMA user_version` is 0 and must then find it set to 3 (the script
-- ends with `PRAGMA user_version = 3;`).
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
-- Foreign keys: fragments.session_id -> sessions.id ON DELETE CASCADE;
--               exam_snapshot_ids.content_id -> exam_snapshots.content_id
--                 ON DELETE RESTRICT;
--               exam_attempts.exam_id -> exam_snapshot_ids.exam_id ON DELETE RESTRICT;
--               exam_answers/result/outbox.attempt_id -> exam_attempts.attempt_id
--                 ON DELETE CASCADE. Openers MUST enable enforcement at
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
-- fragments_fts is an EXTERNAL-CONTENT FTS4 index over fragments.text: it
-- stores only the inverted index (the text itself stays in fragments), so
-- it adds no duplicate content. FTS4 — not FTS5 — is the portable choice:
-- Android's framework SQLite enables the FTS3/FTS4 module only (one module;
-- `fts4` is its current name), so FTS5 does not exist on device and every
-- install would fail to open. The module ships everywhere else the schema
-- must run: iOS (GRDB's bundled SQLite), sqlite-jdbc (the Android JVM-test
-- backend) and CPython (the checker). The unicode61 tokenizer keeps
-- searching case- and accent-insensitive.
--
-- External-content FTS4 cannot keep itself in sync, and it reads the
-- ORIGINAL text from the content table while unindexing — which is why the
-- documented sync pattern is FOUR triggers: the BEFORE DELETE / BEFORE
-- UPDATE triggers remove the index entry while the content row is still
-- readable, and the AFTER INSERT / AFTER UPDATE triggers index the new
-- text. (FTS5's single-'delete'-command trigger pattern does not exist
-- here.) The upgrade path (a database already at user_version 1) applies
-- exactly this block: the CREATE VIRTUAL TABLE, the four triggers, and the
-- one-time 'rebuild' command that indexes every fragment the database
-- already held. Fresh installs run the same statements (the 'rebuild' is a
-- no-op on an empty fragments table).

CREATE VIRTUAL TABLE fragments_fts USING fts4(
    text,
    content='fragments',
    tokenize='unicode61'
);

CREATE TRIGGER fragments_fts_after_insert AFTER INSERT ON fragments BEGIN
    INSERT INTO fragments_fts(docid, text) VALUES (new.rowid, new.text);
END;

CREATE TRIGGER fragments_fts_before_delete BEFORE DELETE ON fragments BEGIN
    DELETE FROM fragments_fts WHERE docid = old.rowid;
END;

CREATE TRIGGER fragments_fts_before_update BEFORE UPDATE OF text ON fragments BEGIN
    DELETE FROM fragments_fts WHERE docid = old.rowid;
END;

CREATE TRIGGER fragments_fts_after_update AFTER UPDATE OF text ON fragments BEGIN
    INSERT INTO fragments_fts(docid, text) VALUES (new.rowid, new.text);
END;

-- One-time index fill: 'rebuild' re-reads every fragments row through the
-- content option, so a v1→v2 upgrade can search the history it already had.
INSERT INTO fragments_fts(fragments_fts) VALUES('rebuild');

PRAGMA user_version = 2;

-- ===========================================================================
-- v3 — immutable exam snapshots and durable local attempts
-- ===========================================================================
--
-- The JSON payload preserves the full exam contract, including bundled content,
-- passages and grading thresholds. `content_id` deduplicates identical content;
-- `exam_snapshot_ids` keeps each immutable examId/generation alias so deduplication
-- never discards publication identity. Attempts refer to an examId, not the
-- current week or bundle. Answers are keyed by question ID and attempt revision,
-- not a mutable display index. The result payload freezes original verdicts and
-- grading rules. This outbox is intentionally separate from content-free usage.

CREATE TABLE exam_snapshots (
    content_id       TEXT PRIMARY KEY,
    content_hash     TEXT NOT NULL UNIQUE,
    schema_version   INTEGER NOT NULL CHECK (schema_version > 0),
    content_revision INTEGER NOT NULL CHECK (content_revision > 0),
    grading_version  INTEGER NOT NULL CHECK (grading_version > 0),
    source           TEXT NOT NULL CHECK (source IN ('bundled', 'deterministic', 'llm')),
    language_id      TEXT NOT NULL,
    level            TEXT NOT NULL,
    week_id          INTEGER NOT NULL CHECK (week_id > 0),
    content_json     TEXT NOT NULL,
    created_at       REAL NOT NULL
);

CREATE TABLE exam_snapshot_ids (
    exam_id    TEXT PRIMARY KEY,
    content_id TEXT NOT NULL REFERENCES exam_snapshots(content_id) ON DELETE RESTRICT,
    created_at REAL NOT NULL
);

CREATE TABLE exam_attempts (
    attempt_id       TEXT PRIMARY KEY,
    exam_id          TEXT NOT NULL REFERENCES exam_snapshot_ids(exam_id) ON DELETE RESTRICT,
    owner_account_id TEXT NULL,
    state            TEXT NOT NULL CHECK (state IN ('in_progress', 'completed')),
    sync_state       TEXT NOT NULL CHECK (sync_state IN ('local_only', 'pending', 'synced', 'conflict')),
    position         INTEGER NOT NULL DEFAULT 0 CHECK (position >= 0),
    revision         INTEGER NOT NULL CHECK (revision > 0),
    started_at       REAL NOT NULL,
    updated_at       REAL NOT NULL,
    completed_at     REAL NULL,
    CHECK ((state = 'in_progress' AND completed_at IS NULL) OR
           (state = 'completed' AND completed_at IS NOT NULL))
);

CREATE INDEX exam_attempts_owner_updated ON exam_attempts(owner_account_id, updated_at);

CREATE TABLE exam_answers (
    attempt_id    TEXT NOT NULL REFERENCES exam_attempts(attempt_id) ON DELETE CASCADE,
    question_id   TEXT NOT NULL,
    revision      INTEGER NOT NULL CHECK (revision > 0),
    response_json TEXT NOT NULL,
    saved_at      REAL NOT NULL,
    PRIMARY KEY (attempt_id, question_id, revision)
);

CREATE TABLE exam_results (
    attempt_id  TEXT PRIMARY KEY REFERENCES exam_attempts(attempt_id) ON DELETE CASCADE,
    result_json TEXT NOT NULL,
    recorded_at REAL NOT NULL
);

CREATE TABLE exam_sync_outbox (
    event_id        TEXT PRIMARY KEY,
    attempt_id      TEXT NOT NULL REFERENCES exam_attempts(attempt_id) ON DELETE CASCADE,
    revision        INTEGER NOT NULL CHECK (revision > 0),
    event_type      TEXT NOT NULL CHECK (event_type IN ('created', 'progress', 'completed')),
    payload_json    TEXT NOT NULL,
    delivery_state  TEXT NOT NULL DEFAULT 'pending'
        CHECK (delivery_state IN ('pending', 'in_flight', 'acknowledged')),
    created_at      REAL NOT NULL,
    acknowledged_at REAL NULL,
    UNIQUE (attempt_id, revision),
    CHECK ((delivery_state = 'acknowledged' AND acknowledged_at IS NOT NULL) OR
           (delivery_state != 'acknowledged' AND acknowledged_at IS NULL))
);

CREATE INDEX exam_sync_outbox_pending ON exam_sync_outbox(delivery_state, created_at);

CREATE TRIGGER exam_snapshots_no_update BEFORE UPDATE ON exam_snapshots BEGIN
    SELECT RAISE(ABORT, 'exam snapshots are immutable');
END;

CREATE TRIGGER exam_snapshot_ids_no_update BEFORE UPDATE ON exam_snapshot_ids BEGIN
    SELECT RAISE(ABORT, 'exam snapshot identities are immutable');
END;

PRAGMA user_version = 3;
