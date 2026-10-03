#!/usr/bin/env python3
"""Learning DB schema checker: pin the canonical SQLite schema and the
migration fixtures so any drift fails loudly and offline.

Checks:
- shared/db/schema.sql executes verbatim in an in-memory SQLite database
  (the same portable SQL AndroidX SQLite and GRDB must accept)
- PRAGMA user_version is 1
- every table/column/index matches the PINNED inventory below (type,
  NOT NULL, PK position, DEFAULT) — any delta is a clear, named failure
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

SCHEMA_VERSION = 1

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
    (name, table, columns) explicitly-created indexes from a live schema."""
    tables = {}
    names = [r[0] for r in conn.execute(
        "SELECT name FROM sqlite_master WHERE type = 'table' "
        "AND name NOT LIKE 'sqlite_%' ORDER BY name")]
    for name in names:
        # PRAGMA table_info rows: (cid, name, type, notnull, dflt_value, pk)
        cols = [(r[1], r[2], r[3], r[5], r[4])
                for r in conn.execute(f'PRAGMA table_info("{name}")')]
        tables[name] = cols
    indexes = set()
    for name in names:
        for row in conn.execute(f'PRAGMA index_list("{name}")'):
            idx_name, origin = row[1], row[3]
            if origin != 'c':  # 'c' = explicitly CREATE INDEX'd
                continue
            cols = [r[2] for r in conn.execute(f"PRAGMA index_info('{idx_name}')")]
            indexes.add((idx_name, name, tuple(cols)))
    return tables, indexes


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
    cases = sorted(p for p in fixture_dir.glob('*.json') if not p.name.endswith('.expected.json'))
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


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    print_only = '--print-inventory' in argv
    try:
        conn = build_schema(SCHEMA.read_text())
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
    issues += check_words_guard(tables)
    issues += check_fixtures(FIXTURES, tables)

    print(f"DB Schema: {SCHEMA.relative_to(ROOT)} — "
          f"user_version {conn.execute('PRAGMA user_version').fetchone()[0]}, "
          f"{len(tables)} tables, {len(indexes)} indexes, "
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
