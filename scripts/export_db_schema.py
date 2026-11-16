#!/usr/bin/env python3
"""Export shared/db/schema.sql into the Android and iOS apps as source.

Reads the canonical schema (the single source of truth, validated by
check_db_schema.py), splits it into top-level statements — a ';' ends a
statement only when it is outside single-quoted strings and comments, and
inside a CREATE TRIGGER body only after its final END (the trigger's own
interior statements keep their semicolons) — and generates two files holding
the same statement list for the platform openers:

- apps/android/.../data/SchemaData.kt, a plain Kotlin object holding
  LEARNING_SCHEMA_STATEMENTS for both production openers (AndroidX SQLite)
  and JVM tests (sqlite-jdbc);
- apps/ios/Core/Database/SchemaData.swift, a Swift enumeration holding
  learningSchemaStatements for the GRDB opener.

The PRAGMA user_version = 2 stays as its own final statement. Both files also
carry SCHEMA_UPGRADES / schemaUpgrades: the per-version statement deltas an
ALREADY-INSTALLED database applies on upgrade — the statements between the
previous `PRAGMA user_version = N-1` and this version's block (the closing
PRAGMA excluded; the platform opener sets user_version itself). Fresh
installs run the full list; onUpgrade and onCreate therefore converge on the
same end state, from the one source.

Every run also smoke-tests the split: each statement must execute, in order,
against python sqlite3 in memory, the database must end at user_version 2,
and the FTS index must answer a MATCH over a row written through the sync
triggers. This is the same portable SQL Android and iOS must accept verbatim.

Modes:
- default : regenerate SchemaData.kt and SchemaData.swift (after the smoke
            test) and say so
- --check : compare the generated sources against the files on disk instead
            of writing; exit 1 on any drift (run after editing schema.sql)
"""
import argparse
import pathlib
import re
import sqlite3
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
SCHEMA = ROOT / 'shared/db/schema.sql'
OUT = ROOT / ('apps/android/app/src/main/java/com/jkdc/app/'
              'data/SchemaData.kt')
SWIFT_OUT = ROOT / 'apps/ios/Core/Database/SchemaData.swift'

SCHEMA_VERSION = 9

_PRAGMA_VERSION = re.compile(r'PRAGMA\s+user_version\s*=\s*(\d+)',
                             re.IGNORECASE)


# ---------------------------------------------------------------------------
# Statement splitting


def split_statements(sql_text):
    """Split schema SQL into its top-level statements, dropping comments.

    A ';' ends a statement only when it is outside single-quoted strings
    (with SQL's '' escape) and outside -- line / /* block */ comments — and,
    inside a CREATE TRIGGER body, only after the body's final END: the
    trigger's interior statements keep their semicolons and stay one
    statement (they must be exec'd whole). The terminator test is "the
    statement so far ends with the token END", so a body may not end a line
    on an END that is not the body's final one (a CASE ... END inside the
    last body statement, say) — the canonical schema has no such body.
    Quoted identifiers ("name", `name`, [name]) are NOT honored — the
    canonical schema quotes none — and a ';' inside them would split.
    Every statement keeps its interior newlines and loses the trailing ';'.
    """
    statements = []
    buffer = []
    i, n = 0, len(sql_text)
    in_string = False
    while i < n:
        ch = sql_text[i]
        if in_string:
            buffer.append(ch)
            if ch == "'":
                if sql_text[i + 1:i + 2] == "'":  # escaped quote inside the literal
                    buffer.append("'")
                    i += 2
                    continue
                in_string = False
            i += 1
            continue
        if ch == "'":
            in_string = True
            buffer.append(ch)
            i += 1
        elif sql_text[i:i + 2] == '--':
            end = sql_text.find('\n', i)
            i = n if end == -1 else end  # keep the newline itself
        elif sql_text[i:i + 2] == '/*':
            end = sql_text.find('*/', i + 2)
            if end == -1:
                raise ValueError('unterminated block comment in schema.sql')
            i = end + 2
        elif ch == ';':
            statement = ''.join(buffer).strip()
            in_trigger = statement.upper().startswith('CREATE TRIGGER')
            if in_trigger and not statement.upper().endswith('END'):
                buffer.append(';')  # an interior trigger statement: keep going
                i += 1
                continue
            if statement:
                statements.append(statement)
            buffer = []
            i += 1
        else:
            buffer.append(ch)
            i += 1
    if in_string:
        raise ValueError('unterminated string literal in schema.sql')
    tail = ''.join(buffer).strip()
    if tail:
        raise ValueError(f'statement without a terminating ";": {tail[:60]!r}')
    return statements


# ---------------------------------------------------------------------------
# Version deltas


def version_deltas(statements):
    """Map {version: [upgrade statements]} for versions >= 2: the statements
    between the previous `PRAGMA user_version = N-1` and this version's own
    PRAGMA (both boundary PRAGMAs excluded — the closing one because the
    opener sets user_version itself, the opening one because it belongs to
    the previous install). Version 1 is the fresh-install baseline and has
    no delta."""
    deltas = {}
    current = None
    for statement in statements:
        match = _PRAGMA_VERSION.fullmatch(statement)
        if match:
            version = int(match.group(1))
            if version >= 2:
                deltas[version] = current or []
            current = []
            continue
        if current is not None:
            current.append(statement)
    return deltas


# ---------------------------------------------------------------------------
# Smoke test: the split statements must rebuild the schema on their own


def smoke_test(statements):
    """Execute each statement, in order, in an in-memory SQLite database and
    assert the final PRAGMA user_version plus one end-to-end round of the
    FTS sync triggers (insert → MATCH, edit → old token gone). Returns the
    connection."""
    conn = sqlite3.connect(':memory:')
    conn.execute('PRAGMA foreign_keys = ON;')
    for statement in statements:
        conn.execute(statement)
    version = conn.execute('PRAGMA user_version').fetchone()[0]
    if version != SCHEMA_VERSION:
        raise ValueError(f'smoke test failed: expected user_version '
                         f'{SCHEMA_VERSION}, found {version}')
    conn.execute("INSERT INTO sessions (id, language_id, started_at, title) "
                 "VALUES ('smoke', 'es', 0, 'smoke')")
    conn.execute("INSERT INTO fragments (id, session_id, sort_index, speaker, "
                 "text, revision, start_ms, end_ms, received_at) "
                 "VALUES ('f', 'smoke', 0, 'user', 'Humo de prueba', 0, 0, 1, 0)")
    if not conn.execute("SELECT rowid FROM fragments_fts "
                        "WHERE fragments_fts MATCH 'humo'").fetchall():
        raise ValueError('smoke test failed: fragments_fts does not answer '
                         'MATCH after the sync triggers ran')
    conn.execute("UPDATE fragments SET text = 'Cambio limpio' WHERE id = 'f'")
    if conn.execute("SELECT rowid FROM fragments_fts "
                    "WHERE fragments_fts MATCH 'humo'").fetchall():
        raise ValueError('smoke test failed: fragments_fts kept a stale token '
                         'after an edit')
    conn.execute('DELETE FROM sessions')
    return conn


# ---------------------------------------------------------------------------
# Kotlin generation


def kotlin_string(text):
    """Encode one statement as a (single-line) Kotlin string literal. '$' must
    be escaped or Kotlin would read it as a template expression."""
    escaped = (text.replace('\\', '\\\\').replace('"', '\\"')
               .replace('$', '\\$').replace('\n', '\\n').replace('\r', '\\r'))
    return f'"{escaped}"'


def generate_kotlin(statements, deltas):
    """Render SchemaData.kt for the given statements and upgrade deltas."""
    literals = ',\n'.join(f'        {kotlin_string(s)}' for s in statements)
    upgrades = ',\n'.join(
        f'        {version} to listOf(\n' +
        ',\n'.join(f'            {kotlin_string(s)}' for s in delta) +
        ',\n        )'
        for version, delta in sorted(deltas.items()))
    return f"""// Generated by scripts/export_db_schema.py from shared/db/schema.sql; edit the schema, not this file.
// {len(statements)} top-level statements; the last one sets PRAGMA user_version = {SCHEMA_VERSION}.

package com.jkdc.app.data

/** The canonical learning database schema (v{SCHEMA_VERSION}), split into top-level
 *  statements: the one list both production and test openers execute, in order,
 *  to build a fresh learning database. */
object SchemaData {{
    /** Schema this file was exported from; matches LearningDb.VERSION. */
    const val SCHEMA_VERSION = {SCHEMA_VERSION}

    val LEARNING_SCHEMA_STATEMENTS: List<String> = listOf(
{literals},
    )

    /** The upgrade deltas, keyed by TARGET schema version: the statements an
     *  already-installed database at version - 1 applies to reach [version]
     *  (the opener then sets PRAGMA user_version itself). A version without
     *  an entry here only ever exists as a fresh install (v1). */
    val SCHEMA_UPGRADES: Map<Int, List<String>> = mapOf(
{upgrades},
    )
}}
"""


# ---------------------------------------------------------------------------
# Swift generation


def swift_string(text):
    """Encode one statement as a (single-line) Swift string literal. Unlike
    Kotlin, Swift needs no '$' escape (interpolation is '\\('), but the same
    backslash, quote and control-character escapes apply."""
    escaped = (text.replace('\\', '\\\\').replace('"', '\\"')
               .replace('\n', '\\n').replace('\r', '\\r').replace('\t', '\\t'))
    return f'"{escaped}"'


def generate_swift(statements, deltas):
    """Render SchemaData.swift for the given statements and upgrade deltas."""
    literals = ',\n'.join(f'        {swift_string(s)}' for s in statements)
    upgrades = ',\n'.join(
        f'        {version}: [\n' +
        ',\n'.join(f'            {swift_string(s)}' for s in delta) +
        ',\n        ]'
        for version, delta in sorted(deltas.items()))
    return f"""// Generated by scripts/export_db_schema.py from shared/db/schema.sql; edit the schema, not this file.
// {len(statements)} top-level statements; the last one sets PRAGMA user_version = {SCHEMA_VERSION}.

/// The canonical learning database schema (v{SCHEMA_VERSION}), split into top-level
/// statements: the one list both production and test openers execute, in order,
/// to build a fresh learning database.
public enum SchemaData {{
    /// Schema this file was exported from; matches LearningDatabase.version.
    public static let schemaVersion = {SCHEMA_VERSION}

    public static let learningSchemaStatements: [String] = [
{literals},
    ]

    /// The upgrade deltas, keyed by TARGET schema version: the statements an
    /// already-installed database at version - 1 applies to reach `version`
    /// (the opener then sets PRAGMA user_version itself). A version without
    /// an entry here only ever exists as a fresh install (v1).
    public static let schemaUpgrades: [Int: [String]] = [
{upgrades},
    ]
}}
"""


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('--check', action='store_true',
                        help='verify the committed SchemaData.kt and SchemaData.swift match the schema instead of writing them')
    args = parser.parse_args(argv)

    try:
        statements = split_statements(SCHEMA.read_text())
        deltas = version_deltas(statements)
        smoke_test(statements)
    except (sqlite3.Error, ValueError, OSError) as exc:
        print(f"DB Schema export: schema failed to split or execute: {exc}")
        return 1
    generated = {'kotlin': generate_kotlin(statements, deltas),
                 'swift': generate_swift(statements, deltas)}
    outputs = {'kotlin': OUT, 'swift': SWIFT_OUT}

    if args.check:
        failed = False
        for kind, output in outputs.items():
            if not output.exists():
                print(f"DB Schema export: {output.relative_to(ROOT)} is missing; "
                      f"run scripts/export_db_schema.py to generate it")
                failed = True
            elif output.read_text() != generated[kind]:
                print(f"DB Schema export: {output.relative_to(ROOT)} is stale; "
                      f"run scripts/export_db_schema.py after editing shared/db/schema.sql")
                failed = True
        if failed:
            return 1
        print(f"DB Schema export: both generated sources are up to date ✓ "
              f"({len(statements)} statements, user_version {SCHEMA_VERSION})")
        return 0

    for kind, output in outputs.items():
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(generated[kind])
        print(f"DB Schema export: wrote {output.relative_to(ROOT)} "
              f"({len(statements)} statements, user_version {SCHEMA_VERSION}) ✓")
    return 0


if __name__ == '__main__':
    sys.exit(main())
