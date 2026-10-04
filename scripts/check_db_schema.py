#!/usr/bin/env python3
"""Learning DB schema checker: pin the canonical SQLite schema and the
migration fixtures so any drift fails loudly and offline.

Checks:
- shared/db/schema.sql executes verbatim in an in-memory SQLite database
  (the same portable SQL AndroidX SQLite and GRDB must accept)
- PRAGMA user_version is 2
- every table/column/index matches the PINNED inventory below (type,
  NOT NULL, PK position, DEFAULT) — any delta is a clear, named failure
- the PINNED constraints hold: PRAGMA foreign_key_list must match exactly
  (fragments.session_id -> sessions.id, ON DELETE CASCADE) and the required
  SQL constraints (preferences CHECK, user_version pragma) must be present
  in the stored schema
- the PINNED virtual table (fragments_fts, the FTS5 external-content search
  index) and the three sync triggers exist with exactly the declared shape,
  read straight from sqlite_master
- no `words` table exists (words are a LearningEngine projection; learner
  hidden words live in preferences.hidden_words)
- migration fixtures under shared/fixtures/db/migration: every
  case-*.json is a realistic Archive v2 (exactly the Models.kt field
  names, required fields present), and every *.expected.json counts all
  schema tables and only references table.column pairs that exist

Modes:
- default      : run all checks, print a report, exit 0/1
- --print-inventory : print the inventory derived from the executed schema
"""
import json
import pathlib
import re
import sqlite3
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
SCHEMA = ROOT / 'shared/db/schema.sql'
FIXTURES = ROOT / 'shared/fixtures/db/migration'

SCHEMA_VERSION = 2

# ---------------------------------------------------------------------------
# Pinned inventory.
#
# Columns are (name, type, notnull, pk, default) exactly as PRAGMA
# table_info reports them: notnull 0/1, pk = 1-based position in the
# primary key (0 when not part of it), default = the literal TEXT sqlite
# stores (None when the column has no DEFAULT). Composite primary keys keep
# their declared column order. Only explicitly created indexes are pinned;
# SQLite's own sqlite_autoindex_* entries (from PRIMARY KEY/UNIQUE) and the
# sqlite_sequence side table of AUTOINCREMENT are internal and skipped.
#
# (name, type, notnull, pk, default)
TABLES = {
    'meta': [
        ('key', 'TEXT', 0, 1, None),
        ('value', 'TEXT', 1, 0, None),
    ],
    'preferences': [
        ('id', 'INTEGER', 0, 1, None),
        ('learning_language_id', 'TEXT', 1, 0, None),
        ('meaning_visible', 'INTEGER', 1, 0, None),
        ('meaning_language', 'TEXT', 1, 0, None),
        ('session_minutes', 'INTEGER', 1, 0, None),
        ('hidden_words', 'TEXT', 1, 0, "'[]'"),
        ('interests', 'TEXT', 1, 0, "''"),
        ('has_onboarded', 'INTEGER', 1, 0, None),
        ('ai_consent_version', 'INTEGER', 0, 0, None),
        ('slow_speech', 'INTEGER', 0, 0, None),
        ('listening_practice', 'INTEGER', 0, 0, None),
        ('speech_rate', 'REAL', 0, 0, None),
        ('usage_metrics_enabled', 'INTEGER', 0, 0, None),
        ('stt_engine', 'TEXT', 0, 0, None),
        ('pronunciation_enabled', 'INTEGER', 0, 0, None),
    ],
    'sessions': [
        ('id', 'TEXT', 0, 1, None),
        ('language_id', 'TEXT', 1, 0, None),
        ('provider_id', 'TEXT', 0, 0, None),
        ('started_at', 'REAL', 1, 0, None),
        ('ended_at', 'REAL', 0, 0, None),
        ('theme_id', 'TEXT', 0, 0, None),
        ('title', 'TEXT', 1, 0, None),
        ('voice_seconds', 'REAL', 1, 0, '0'),
        ('usage_final', 'INTEGER', 1, 0, '0'),
        ('input_tokens', 'INTEGER', 1, 0, '0'),
        ('output_tokens', 'INTEGER', 1, 0, '0'),
        ('search_calls', 'INTEGER', 1, 0, '0'),
        ('end_reason', 'TEXT', 0, 0, None),
        ('mode', 'TEXT', 0, 0, None),
        ('lesson_id', 'TEXT', 0, 0, None),
        ('scenario_id', 'TEXT', 0, 0, None),
        ('assessments_json', 'TEXT', 1, 0, "'[]'"),
        ('translations_json', 'TEXT', 1, 0, "'{}'"),
        ('topics_json', 'TEXT', 1, 0, "'[]'"),
        ('scenario_review_json', 'TEXT', 0, 0, None),
        ('review_created_at', 'REAL', 0, 0, None),
    ],
    'fragments': [
        ('id', 'TEXT', 0, 1, None),
        ('session_id', 'TEXT', 1, 0, None),
        ('sort_index', 'INTEGER', 1, 0, None),
        ('speaker', 'TEXT', 1, 0, None),
        ('text', 'TEXT', 1, 0, None),
        ('revision', 'INTEGER', 1, 0, None),
        ('previous_texts', 'TEXT', 1, 0, "'[]'"),
        ('start_ms', 'INTEGER', 1, 0, None),
        ('end_ms', 'INTEGER', 1, 0, None),
        ('received_at', 'REAL', 1, 0, None),
        ('meaning_visible', 'INTEGER', 1, 0, '0'),
        ('typed', 'INTEGER', 1, 0, '0'),
    ],
    'expressions': [
        ('norm_key', 'TEXT', 1, 1, None),
        ('language_id', 'TEXT', 0, 2, None),
        ('text', 'TEXT', 1, 0, None),
        ('meaning', 'TEXT', 1, 0, None),
        ('source', 'TEXT', 1, 0, None),
        ('bars', 'INTEGER', 1, 0, '0'),
        ('attempts', 'INTEGER', 1, 0, '0'),
        ('last_seen', 'REAL', 1, 0, '0'),
        ('due_at', 'REAL', 1, 0, None),
        ('lesson_id', 'TEXT', 0, 0, None),
    ],
    'lesson_state': [
        ('lesson_id', 'TEXT', 0, 1, None),
        ('attempts', 'INTEGER', 1, 0, None),
        ('last_seen', 'REAL', 1, 0, None),
    ],
    'completions': [
        ('kind', 'TEXT', 1, 1, None),
        ('item_id', 'TEXT', 1, 2, None),
        ('at', 'REAL', 1, 3, None),
        ('best_score', 'INTEGER', 1, 0, '0'),
        ('language_id', 'TEXT', 0, 0, None),
    ],
    'skill_errors': [
        ('skill_id', 'TEXT', 0, 1, None),
        ('count', 'INTEGER', 1, 0, None),
        ('last_at', 'REAL', 1, 0, None),
        ('last_prompt', 'TEXT', 1, 0, "''"),
    ],
    'usage_outbox': [
        ('id', 'INTEGER', 0, 1, None),
        ('name', 'TEXT', 1, 0, None),
        ('at', 'REAL', 1, 0, None),
        ('props_json', 'TEXT', 1, 0, "'{}'"),
        ('dispatched', 'INTEGER', 1, 0, '0'),
    ],
    'assessment_tickets': [
        ('session_id', 'TEXT', 0, 1, None),
        ('attempts', 'INTEGER', 1, 0, '0'),
        ('last_attempt_at', 'REAL', 0, 0, None),
    ],
}

# (name, table, columns) — explicitly created indexes only.
INDEXES = [
    ('fragments_session', 'fragments', ['session_id', 'sort_index']),
    ('expressions_due', 'expressions', ['language_id', 'due_at']),
    ('completions_lang', 'completions', ['language_id', 'at']),
    ('outbox_pending', 'usage_outbox', ['dispatched', 'at']),
]

# Pinned constraints.
#
# (child_table, parent_table, [(from_col, to_col)], on_delete) — compared
# EXACTLY against PRAGMA foreign_key_list for every pinned table, so removing
# the FK, re-pointing it, or weakening ON DELETE CASCADE all fail.
FOREIGN_KEYS = [
    ('fragments', 'sessions', [('session_id', 'id')], 'CASCADE'),
]

# (table or None for the schema source, required SQL substring) — asserted by
# inspecting the stored CREATE statement in sqlite_master.sql (or the raw
# schema source for schema-level directives).
REQUIRED_CONSTRAINTS = [
    ('preferences', 'CHECK (id = 1)'),
    (None, 'PRAGMA user_version = 2;'),
]

# ---------------------------------------------------------------------------
# Pinned virtual tables and triggers (schema v2).
#
# PRAGMA table_info cannot describe a virtual table's options, and triggers
# are not tables at all — both are pinned straight from their stored
# sqlite_master statements: the name must exist with the right type and its
# stored CREATE must contain every pinned substring (verbatim: SQLite keeps
# the original spelling).
#
# FTS4 — not FTS5 — because Android's framework SQLite enables the
# FTS3/FTS4 module only (the four triggers implement the documented
# external-content sync pattern for it: BEFORE DELETE / BEFORE UPDATE remove
# the index entry while the content row is still readable, AFTER INSERT /
# AFTER UPDATE index the new text). The internal FTS4 shadow tables
# (fragments_fts_content/_segments/_segdir/_docsize/_stat) are SQLite
# internals like sqlite_sequence: the table inventory skips them, so they
# can drift only by the virtual table itself changing shape. A trigger body
# that stops keeping the index in sync is caught functionally by the
# exporter's smoke test and the app-side search tests; here the trigger must
# exist, target fragments, and carry its event.

VIRTUAL_TABLES = {
    'fragments_fts': ('CREATE VIRTUAL TABLE', 'USING fts4',
                      "content='fragments'", "tokenize='unicode61'"),
}

TRIGGERS = {
    'fragments_fts_after_insert': ('fragments', 'AFTER INSERT'),
    'fragments_fts_after_update': ('fragments', 'AFTER UPDATE OF text'),
    'fragments_fts_before_delete': ('fragments', 'BEFORE DELETE'),
    'fragments_fts_before_update': ('fragments', 'BEFORE UPDATE OF text'),
}

# ---------------------------------------------------------------------------
# Archive v2 shape (Models.kt field names — the JSON uses the Kotlin/Swift
# property names verbatim; there is no @SerialName on these classes).
# Required keys must be present and non-null (ArchiveCodec.requireFields plus
# the non-optional data-class properties); optional keys may be present with
# null. No key outside these sets may appear.

ARCHIVE_REQUIRED = {
    'archive': ('schemaVersion', 'sessions', 'preferences'),
    'preferences': ('learningLanguageID', 'meaningVisible', 'meaningLanguage',
                    'sessionMinutes', 'hiddenWords', 'interests', 'hasOnboarded'),
    'session': ('id', 'languageID', 'startedAt', 'title', 'fragments', 'assessments',
                'translations', 'topics', 'voiceSeconds', 'usageFinal', 'inputTokens',
                'outputTokens', 'searchCalls'),
    'fragment': ('id', 'revision', 'previousTexts', 'speaker', 'text', 'startMS',
                 'endMS', 'receivedAt', 'meaningVisible', 'typed'),
    'assessment': ('passageID', 'revisionKey', 'outcome', 'suggestedLevel', 'nextGoal',
                   'capability', 'words', 'createdAt', 'context'),
    'word': ('lemma', 'meaning', 'form', 'kind', 'confidence', 'sourceIDs', 'quote',
             'language'),
    'topic': ('id', 'languageID', 'query', 'text', 'sources', 'retrievedAt'),
    'reviewedError': ('quote', 'correction', 'kind', 'note', 'sourceIDs'),
    'reviewedPhrase': ('phrase', 'meaning'),
    'lesson': ('lessonID', 'bars', 'attempts', 'lastSeen', 'dueAt'),
    'expression': ('text', 'meaning', 'source', 'bars', 'attempts',
                   'lastSeen', 'dueAt'),
    'completion': ('kind', 'itemID', 'at', 'bestScore'),
    'skillError': ('skillID', 'count', 'lastAt', 'lastPrompt'),
}

ARCHIVE_OPTIONAL = {
    'archive': ('lessons', 'expressions', 'completions', 'errors', 'masteryCache'),
    'preferences': ('aiConsentVersion', 'slowSpeech', 'listeningPractice',
                    'speechRate', 'usageMetricsEnabled', 'sttEngine',
                    'pronunciationEnabled'),
    'session': ('providerID', 'endedAt', 'themeID', 'endReason', 'mode', 'lessonID',
                'scenarioID', 'scenarioReview'),
    'expression': ('lessonID', 'languageID'),
    'completion': ('languageID',),
    'assessment': (),
    'scenarioReview': ('createdAt', 'strengths', 'errors', 'keyPhrases',
                       'retryFocus'),
}

# scenarioReview has no required fields because a ScenarioReview carrying
# only defaults is still valid; when present, its keys must stay inside
# SCENARIO_REVIEW_KEYS and its errors/keyPhrases entries use reviewedError /
# reviewedPhrase.
SCENARIO_REVIEW_KEYS = ('createdAt', 'strengths', 'errors', 'keyPhrases', 'retryFocus')


# ---------------------------------------------------------------------------
# Schema execution and inventory


def build_schema(sql_text, connection=None):
    """Execute schema SQL verbatim in an in-memory database (or a supplied
    connection, so tests can build mutated copies) and return it."""
    conn = connection if connection is not None else sqlite3.connect(':memory:')
    conn.execute('PRAGMA foreign_keys = ON;')
    conn.executescript(sql_text)
    return conn


def read_inventory(conn):
    """Derive {table: [(column, type, notnull, pk, default)]} and the set of
    (name, table, columns) explicitly-created indexes from a live schema.

    Internal tables are skipped: SQLite's own sqlite_* entries and the FTS5
    shadow tables of every pinned virtual table (fragments_fts_data and
    friends — implementation details of the FTS5 module, like
    sqlite_sequence). The virtual tables themselves are also skipped here:
    PRAGMA table_info cannot describe their options, so they are pinned
    separately from sqlite_master by check_virtual_tables."""
    tables = {}
    names = [r[0] for r in conn.execute(
        "SELECT name FROM sqlite_master WHERE type = 'table' "
        "AND name NOT LIKE 'sqlite_%' ORDER BY name")]
    for name in names:
        if is_internal_table(name):
            continue
        # PRAGMA table_info rows: (cid, name, type, notnull, dflt_value, pk)
        cols = [(r[1], r[2], r[3], r[5], r[4])
                for r in conn.execute(f'PRAGMA table_info("{name}")')]
        tables[name] = cols
    indexes = set()
    for name in names:
        if is_internal_table(name):
            continue
        for row in conn.execute(f'PRAGMA index_list("{name}")'):
            idx_name, origin = row[1], row[3]
            if origin != 'c':  # 'c' = explicitly CREATE INDEX'd
                continue
            cols = [r[2] for r in conn.execute(f"PRAGMA index_info('{idx_name}')")]
            indexes.add((idx_name, name, tuple(cols)))
    return tables, indexes


def is_internal_table(name):
    """True for the internal tables the pinned inventory must not describe:
    sqlite_* internals and the FTS5 shadow tables of the pinned virtual
    tables (fragments_fts_data / _idx / _content / _docsize / _config)."""
    if name.startswith('sqlite_'):
        return True
    return any(name == virtual or name.startswith(virtual + '_')
               for virtual in VIRTUAL_TABLES)


def check_user_version(conn):
    version = conn.execute('PRAGMA user_version').fetchone()[0]
    if version == SCHEMA_VERSION:
        return []
    return [f"SCHEMA_VERSION: expected user_version {SCHEMA_VERSION}, found {version}"]


def check_inventory(tables, indexes):
    """Compare a derived inventory against the pinned one; return failure lines."""
    issues = []
    for name, expected_cols in TABLES.items():
        if name not in tables:
            issues.append(f"MISSING_TABLE: '{name}' is pinned but absent from the schema")
            continue
        actual_cols = tables[name]
        expected_by_name = {c[0]: c for c in expected_cols}
        actual_by_name = {c[0]: c for c in actual_cols}
        for cname, exp in expected_by_name.items():
            if cname not in actual_by_name:
                issues.append(f"MISSING_COLUMN: {name}.{cname} is pinned but absent")
                continue
            act = actual_by_name[cname]
            if act != exp:
                issues.append(
                    f"COLUMN_DRIFT: {name}.{cname} expected "
                    f"(type={exp[1]}, notnull={exp[2]}, pk={exp[3]}, default={exp[4]!r}), "
                    f"found (type={act[1]}, notnull={act[2]}, pk={act[3]}, default={act[4]!r})")
        for cname in actual_by_name:
            if cname not in expected_by_name:
                issues.append(f"UNEXPECTED_COLUMN: {name}.{cname} is not in the pinned inventory")
        order_expected = [c[0] for c in expected_cols]
        order_actual = [c[0] for c in actual_cols if c[0] in expected_by_name]
        if order_expected != order_actual:
            issues.append(
                f"COLUMN_ORDER: {name} columns drifted from the pinned order "
                f"{order_expected}")
    for name in tables:
        if name not in TABLES:
            issues.append(f"UNEXPECTED_TABLE: '{name}' is not in the pinned inventory")
    expected_idx = set((n, t, tuple(c)) for n, t, c in INDEXES)
    for missing in sorted(expected_idx - indexes):
        issues.append(f"MISSING_INDEX: {missing[0]} ON {missing[1]}{tuple(missing[2])}")
    for extra in sorted(indexes - expected_idx):
        issues.append(f"UNEXPECTED_INDEX: {extra[0]} ON {extra[1]}{tuple(extra[2])}")
    return issues


def check_words_guard(tables):
    if 'words' in tables:
        return ["FORBIDDEN_TABLE: 'words' must not exist — words are a "
                "LearningEngine projection of content + progress, never persisted; "
                "learner hidden words live in preferences.hidden_words"]
    return []


# ---------------------------------------------------------------------------
# Pinned virtual tables and triggers


def read_virtual_tables(conn):
    """Derive {name: stored CREATE VIRTUAL TABLE sql} for the pinned virtual
    tables (None when the table is absent)."""
    sqls = {}
    for name in VIRTUAL_TABLES:
        row = conn.execute("SELECT sql FROM sqlite_master WHERE type = 'table' "
                           "AND name = ?", (name,)).fetchone()
        sqls[name] = row[0] if row is not None else None
    return sqls


def read_triggers(conn):
    """Derive {name: stored CREATE TRIGGER sql} for every trigger in the
    schema (type = 'trigger'), used both for the exact pinned set and to
    flag anything unexpected."""
    return {row[0]: row[1] for row in conn.execute(
        "SELECT name, sql FROM sqlite_master WHERE type = 'trigger'")}


def check_virtual_tables(conn):
    """Every pinned virtual table must exist and its stored CREATE VIRTUAL
    TABLE must carry every pinned substring (the module and the external
    content options) — a virtual table silently swapped to a plain table,
    re-pointed at the wrong content table, or dropped outright fails here."""
    issues = []
    sqls = read_virtual_tables(conn)
    for name, needles in VIRTUAL_TABLES.items():
        sql = sqls.get(name)
        if sql is None:
            issues.append(f"MISSING_VIRTUAL_TABLE: '{name}' is pinned but absent "
                          f"from sqlite_master (type = 'table')")
            continue
        for needle in needles:
            if needle not in sql:
                issues.append(f"VIRTUAL_TABLE_DRIFT: {name} is missing "
                              f"{needle!r} in its stored CREATE statement")
    return issues


def check_triggers(conn):
    """The trigger set must match the pinned one EXACTLY: every pinned
    trigger present, targeting its table with its event, and nothing else
    defined (a stray trigger would fire writes the schema never declared)."""
    issues = []
    actual = read_triggers(conn)
    for name, (table, event) in TRIGGERS.items():
        sql = actual.get(name)
        if sql is None:
            issues.append(f"MISSING_TRIGGER: '{name}' is pinned but absent "
                          f"from sqlite_master (type = 'trigger')")
            continue
        if f'ON {table}' not in sql:
            issues.append(f"TRIGGER_DRIFT: {name} must target {table!r} "
                          f"(found no 'ON {table}' in its stored CREATE statement)")
        if event not in sql:
            issues.append(f"TRIGGER_DRIFT: {name} must be a {event!r} trigger")
    for name in sorted(set(actual) - set(TRIGGERS)):
        issues.append(f"UNEXPECTED_TRIGGER: '{name}' is not in the pinned inventory")
    return issues


# ---------------------------------------------------------------------------
# Pinned constraints (foreign keys + required SQL constraints)


def read_foreign_keys(conn):
    """Derive {table: sorted [(parent_table, ((from, to), ...), on_delete)]}
    from PRAGMA foreign_key_list for every pinned table."""
    fks = {}
    for table in TABLES:
        rows = list(conn.execute(f'PRAGMA foreign_key_list("{table}")'))
        if not rows:
            continue
        # Row shape: (id, seq, table, from, to, on_update, on_delete, match);
        # composite foreign keys repeat the id across seq-ordered rows.
        grouped = {}
        for fk_id, seq, parent, col_from, col_to, _upd, on_delete, _match in rows:
            grouped.setdefault(fk_id, {'table': parent, 'on_delete': on_delete,
                                       'cols': []})
            grouped[fk_id]['cols'].append((seq, col_from, col_to))
        entries = []
        for group in grouped.values():
            cols = tuple((f, t) for _s, f, t in sorted(group['cols']))
            entries.append((group['table'], cols, group['on_delete']))
        fks[table] = sorted(entries)
    return fks


def check_constraints(conn, schema_text):
    """Assert the pinned constraint inventory exactly: every pinned foreign
    key present with its exact target/columns/on_delete (and nothing extra),
    and every required SQL constraint present in the stored CREATE
    statements (or the raw schema source)."""
    issues = []
    actual = read_foreign_keys(conn)
    expected = {}
    for child, parent, cols, on_delete in FOREIGN_KEYS:
        expected.setdefault(child, []).append((parent, tuple(cols), on_delete))
    for table in TABLES:
        exp = sorted(expected.get(table, []))
        act = actual.get(table, [])
        if exp != act:
            issues.append(f"FOREIGN_KEY_DRIFT: {table} expected foreign keys {exp}, "
                          f"found {act}")
    for table, needle in REQUIRED_CONSTRAINTS:
        if table is None:
            if needle not in schema_text:
                issues.append(f"MISSING_CONSTRAINT: the schema source must contain "
                              f"{needle!r}")
            continue
        row = conn.execute("SELECT sql FROM sqlite_master WHERE type = 'table' "
                           "AND name = ?", (table,)).fetchone()
        if row is None or row[0] is None or needle not in row[0]:
            issues.append(f"MISSING_CONSTRAINT: {table} must define {needle!r}")
    return issues


# ---------------------------------------------------------------------------
# Migration fixtures


def _check_keys(entity, obj, issues, where):
    """One JSON object must carry exactly the Models.kt field names:
    every required key present and non-null, nothing unknown."""
    required = ARCHIVE_REQUIRED.get(entity, ())
    optional = ARCHIVE_OPTIONAL.get(entity, ())
    known = set(required) | set(optional)
    for key in required:
        if key not in obj or obj[key] is None:
            issues.append(f"FIXTURE_REQUIRED: {where} is missing required field '{key}'")
    for key in obj:
        if known and key not in known:
            issues.append(f"FIXTURE_UNKNOWN_FIELD: {where} has unknown field '{key}' "
                          f"(not a Models.kt property of {entity})")


def check_archive_fixture(data, issues, where):
    """Validate one case-*.json Archive v2 against the Models.kt field sets."""
    if not isinstance(data, dict):
        issues.append(f"FIXTURE_INVALID: {where} is not a JSON object")
        return
    _check_keys('archive', data, issues, where)
    if data.get('schemaVersion') != 2:
        issues.append(f"FIXTURE_INVALID: {where} schemaVersion must be 2, "
                      f"found {data.get('schemaVersion')!r}")
    prefs = data.get('preferences')
    if isinstance(prefs, dict):
        _check_keys('preferences', prefs, issues, f"{where} preferences")
    for i, session in enumerate(data.get('sessions') or []):
        swhere = f"{where} sessions[{i}]"
        if not isinstance(session, dict):
            issues.append(f"FIXTURE_INVALID: {swhere} is not a JSON object")
            continue
        _check_keys('session', session, issues, swhere)
        for j, frag in enumerate(session.get('fragments') or []):
            if isinstance(frag, dict):
                _check_keys('fragment', frag, issues, f"{swhere} fragments[{j}]")
        for j, ass in enumerate(session.get('assessments') or []):
            awhere = f"{swhere} assessments[{j}]"
            if not isinstance(ass, dict):
                issues.append(f"FIXTURE_INVALID: {awhere} is not a JSON object")
                continue
            _check_keys('assessment', ass, issues, awhere)
            for k, word in enumerate(ass.get('words') or []):
                if isinstance(word, dict):
                    _check_keys('word', word, issues, f"{awhere} words[{k}]")
        for j, topic in enumerate(session.get('topics') or []):
            if isinstance(topic, dict):
                _check_keys('topic', topic, issues, f"{swhere} topics[{j}]")
        review = session.get('scenarioReview')
        if isinstance(review, dict):
            extra = set(review) - set(SCENARIO_REVIEW_KEYS)
            if extra:
                issues.append(f"FIXTURE_UNKNOWN_FIELD: {swhere} scenarioReview has "
                              f"unknown fields {sorted(extra)}")
            for j, err in enumerate(review.get('errors') or []):
                if isinstance(err, dict):
                    _check_keys('reviewedError', err, issues,
                                f"{swhere} scenarioReview errors[{j}]")
            for j, phrase in enumerate(review.get('keyPhrases') or []):
                if isinstance(phrase, dict):
                    _check_keys('reviewedPhrase', phrase, issues,
                                f"{swhere} scenarioReview keyPhrases[{j}]")
    for i, lesson in enumerate(data.get('lessons') or []):
        if isinstance(lesson, dict):
            _check_keys('lesson', lesson, issues, f"{where} lessons[{i}]")
    for i, expr in enumerate(data.get('expressions') or []):
        if isinstance(expr, dict):
            _check_keys('expression', expr, issues, f"{where} expressions[{i}]")
    for i, mark in enumerate(data.get('completions') or []):
        if isinstance(mark, dict):
            _check_keys('completion', mark, issues, f"{where} completions[{i}]")
    for i, err in enumerate(data.get('errors') or []):
        if isinstance(err, dict):
            _check_keys('skillError', err, issues, f"{where} errors[{i}]")


_COLUMN_REF = re.compile(r'\b([a-z][a-z0-9_]*)\.([a-z][a-z0-9_]*)\b')


def check_expected_fixture(path, tables):
    """Validate one *.expected.json: row counts for every schema table, and
    every table.column reference (in invariants or any other string) must
    resolve against the live schema. Returns (issues, tables_section)."""
    issues = []
    try:
        data = json.loads(path.read_text())
    except (json.JSONDecodeError, OSError) as exc:
        return [f"FIXTURE_INVALID: {path.name} does not parse: {exc}"], None
    if not isinstance(data, dict) or not isinstance(data.get('tables'), dict):
        return [f"FIXTURE_INVALID: {path.name} must hold a 'tables' object"], None
    counts = data['tables']
    if not isinstance(data.get('invariants'), list) or \
            not all(isinstance(s, str) for s in data['invariants']):
        issues.append(f"FIXTURE_INVALID: {path.name} 'invariants' must be a list of strings")
    for name in counts:
        if name not in tables:
            issues.append(f"FIXTURE_UNKNOWN_TABLE: {path.name} counts table '{name}' "
                          f"which is not in the schema")
        elif not isinstance(counts[name], int) or counts[name] < 0:
            issues.append(f"FIXTURE_INVALID: {path.name} count for '{name}' must be a "
                          f"non-negative integer")
    for name in tables:
        if name not in counts:
            issues.append(f"FIXTURE_MISSING_COUNT: {path.name} does not count table '{name}'")
    columns = {t: {c[0] for c in cols} for t, cols in tables.items()}
    for match in _COLUMN_REF.finditer(path.read_text()):
        tname, cname = match.group(1), match.group(2)
        if tname in columns and cname not in columns[tname]:
            issues.append(f"FIXTURE_UNKNOWN_COLUMN: {path.name} references "
                          f"{tname}.{cname} which does not exist in the schema")
    return issues, counts


def check_fixtures(fixture_dir, tables):
    """Validate every migration fixture in fixture_dir against the live
    inventory `tables` ({table: [column tuples]})."""
    issues = []
    cases = sorted(p for p in fixture_dir.glob('case-*.json')
                   if not p.name.endswith('.expected.json'))
    expecteds = sorted(fixture_dir.glob('*.expected.json'))
    if not cases:
        issues.append(f"FIXTURE_MISSING: no case-*.json archives in {fixture_dir}")
    if not expecteds:
        issues.append(f"FIXTURE_MISSING: no *.expected.json files in {fixture_dir}")
    for path in cases:
        try:
            data = json.loads(path.read_text())
        except (json.JSONDecodeError, OSError) as exc:
            issues.append(f"FIXTURE_INVALID: {path.name} does not parse: {exc}")
            continue
        check_archive_fixture(data, issues, path.name)
    for path in expecteds:
        expected_issues, _ = check_expected_fixture(path, tables)
        issues.extend(expected_issues)
    return issues


# ---------------------------------------------------------------------------
# Report


def print_inventory(conn):
    tables, indexes = read_inventory(conn)
    for name, cols in tables.items():
        print(f"table {name}")
        for cname, ctype, notnull, pk, default in cols:
            flags = []
            if pk:
                flags.append(f"pk={pk}")
            if notnull:
                flags.append("notnull")
            if default is not None:
                flags.append(f"default={default}")
            print(f"  {cname:<24} {ctype:<8} {' '.join(flags)}")
    for idx_name, table, cols in sorted(indexes):
        print(f"index {idx_name} ON {table} ({', '.join(cols)})")
    for table, fks in sorted(read_foreign_keys(conn).items()):
        for parent, cols, on_delete in fks:
            pairs = ', '.join(f'{f} -> {t}' for f, t in cols)
            print(f"foreign key {table} ({pairs}) REFERENCES {parent} "
                  f"ON DELETE {on_delete}")


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    print_only = '--print-inventory' in argv
    schema_text = SCHEMA.read_text()
    try:
        conn = build_schema(schema_text)
    except (sqlite3.Error, OSError) as exc:
        print(f"DB Schema: schema failed to execute: {exc}")
        return 1

    if print_only:
        print_inventory(conn)
        return 0

    tables, indexes = read_inventory(conn)
    issues = []
    issues += check_user_version(conn)
    issues += check_inventory(tables, indexes)
    issues += check_constraints(conn, schema_text)
    issues += check_virtual_tables(conn)
    issues += check_triggers(conn)
    issues += check_words_guard(tables)
    issues += check_fixtures(FIXTURES, tables)

    print(f"DB Schema: {SCHEMA.relative_to(ROOT)} — "
          f"user_version {conn.execute('PRAGMA user_version').fetchone()[0]}, "
          f"{len(tables)} tables, {len(indexes)} indexes, "
          f"{len(VIRTUAL_TABLES)} virtual tables, "
          f"{len(read_triggers(conn))} triggers, "
          f"{sum(len(v) for v in read_foreign_keys(conn).values())} foreign keys, "
          f"fixtures: {len(list(FIXTURES.glob('*.json')))} files")
    if issues:
        print(f"DB Schema: {len(issues)} issue(s) found\n")
        for issue in issues:
            print(f"  ⚠ {issue}")
        return 1
    print("DB Schema: no issues found ✓")
    return 0


if __name__ == '__main__':
    sys.exit(main())
