import json
import re
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import check_db_schema


class SchemaTest(unittest.TestCase):
    def setUp(self):
        self.conn = check_db_schema.build_schema(check_db_schema.SCHEMA.read_text())

    def test_schema_executes_and_pins_user_version(self):
        self.assertEqual(check_db_schema.SCHEMA_VERSION,
                         self.conn.execute('PRAGMA user_version').fetchone()[0])
        self.assertEqual([], check_db_schema.check_user_version(self.conn))

    def test_pinned_inventory_passes(self):
        tables, indexes = check_db_schema.read_inventory(self.conn)
        self.assertEqual([], check_db_schema.check_inventory(tables, indexes))

    def test_dropped_column_fails_inventory(self):
        mutated = re.sub(r'\n\s*title\s+TEXT\s+NOT NULL,', '',
                         check_db_schema.SCHEMA.read_text(), count=1)
        self.assertNotEqual(mutated, check_db_schema.SCHEMA.read_text())
        conn = check_db_schema.build_schema(mutated)
        tables, indexes = check_db_schema.read_inventory(conn)
        issues = check_db_schema.check_inventory(tables, indexes)
        self.assertTrue(any('MISSING_COLUMN: sessions.title' in i for i in issues),
                        issues)

    def test_dropped_table_fails_inventory(self):
        mutated = re.sub(r'\nCREATE TABLE skill_errors.*?\n\);\n', '\n',
                         check_db_schema.SCHEMA.read_text(), count=1, flags=re.S)
        conn = check_db_schema.build_schema(mutated)
        tables, indexes = check_db_schema.read_inventory(conn)
        issues = check_db_schema.check_inventory(tables, indexes)
        self.assertTrue(any("MISSING_TABLE: 'skill_errors'" in i for i in issues),
                        issues)

    def test_words_table_is_forbidden(self):
        conn = check_db_schema.build_schema(
            check_db_schema.SCHEMA.read_text()
            + "\nCREATE TABLE words (id TEXT PRIMARY KEY);\n")
        tables, indexes = check_db_schema.read_inventory(conn)
        issues = check_db_schema.check_inventory(tables, indexes)
        issues += check_db_schema.check_words_guard(tables)
        self.assertTrue(any(i.startswith('FORBIDDEN_TABLE: ') and "'words'" in i
                            for i in issues), issues)

    def test_removed_foreign_key_fails_constraints(self):
        mutated = re.sub(r'\s+REFERENCES sessions\(id\) ON DELETE CASCADE', '',
                         check_db_schema.SCHEMA.read_text(), count=1)
        self.assertNotEqual(mutated, check_db_schema.SCHEMA.read_text())
        conn = check_db_schema.build_schema(mutated)
        issues = check_db_schema.check_constraints(conn, mutated)
        self.assertTrue(any(i.startswith('FOREIGN_KEY_DRIFT: fragments')
                            and "'sessions'" in i and "'CASCADE'" in i
                            for i in issues), issues)

    def test_removed_check_constraint_fails_constraints(self):
        mutated = re.sub(r'\s+CHECK \(id = 1\)', '',
                         check_db_schema.SCHEMA.read_text(), count=1)
        conn = check_db_schema.build_schema(mutated)
        issues = check_db_schema.check_constraints(conn, mutated)
        self.assertTrue(any("MISSING_CONSTRAINT: preferences must define "
                            "'CHECK (id = 1)'" in i for i in issues), issues)

    def test_pinned_constraints_pass(self):
        schema_text = check_db_schema.SCHEMA.read_text()
        issues = check_db_schema.check_constraints(self.conn, schema_text)
        self.assertEqual([], issues)

    def test_fixtures_are_valid(self):
        tables, indexes = check_db_schema.read_inventory(self.conn)
        self.assertEqual([],
                         check_db_schema.check_fixtures(check_db_schema.FIXTURES, tables))

    def test_expected_fixture_with_bogus_column_fails(self):
        tables, _ = check_db_schema.read_inventory(self.conn)
        with tempfile.TemporaryDirectory() as tmp:
            bad = Path(tmp) / 'case-bogus.expected.json'
            bad.write_text(json.dumps({
                'tables': dict.fromkeys(tables, 0),
                'invariants': ['every row sets sessions.titel correctly'],
            }))
            issues, _ = check_db_schema.check_expected_fixture(bad, tables)
        self.assertTrue(any('FIXTURE_UNKNOWN_COLUMN: case-bogus.expected.json '
                            'references sessions.titel' in i for i in issues), issues)

    def test_expected_fixture_must_count_every_table(self):
        tables, _ = check_db_schema.read_inventory(self.conn)
        with tempfile.TemporaryDirectory() as tmp:
            bad = Path(tmp) / 'case-partial.expected.json'
            bad.write_text(json.dumps({'tables': {'sessions': 0}, 'invariants': []}))
            issues, _ = check_db_schema.check_expected_fixture(bad, tables)
        self.assertTrue(any('FIXTURE_MISSING_COUNT' in i and 'fragments' in i
                            for i in issues), issues)


if __name__ == '__main__':
    unittest.main()
