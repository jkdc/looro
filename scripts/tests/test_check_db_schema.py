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

    # -- schema v2: the pinned FTS4 virtual table and its sync triggers ------

    def test_pinned_virtual_table_and_triggers_pass(self):
        self.assertEqual([], check_db_schema.check_virtual_tables(self.conn))
        self.assertEqual([], check_db_schema.check_triggers(self.conn))

    def test_shadow_tables_are_internal_to_the_inventory(self):
        tables, _ = check_db_schema.read_inventory(self.conn)
        # fragments_fts itself and its FTS4 external-content shadow tables
        # (_segments/_segdir/_docsize/_stat — external content keeps no
        # _content, and _data would be FTS5-only) are pinned or skipped,
        # never inventory drift; the pinned count stays at ten.
        self.assertEqual(10, len(tables))
        self.assertNotIn('fragments_fts', tables)
        self.assertTrue(check_db_schema.is_internal_table('fragments_fts'))
        for shadow in ('fragments_fts_segments', 'fragments_fts_segdir',
                       'fragments_fts_docsize', 'fragments_fts_stat'):
            self.assertTrue(check_db_schema.is_internal_table(shadow))
        self.assertFalse(check_db_schema.is_internal_table('fragments'))

    def test_dropped_virtual_table_and_triggers_fail(self):
        # the coherent "no FTS" mutation: a v1-shaped schema. Both pins must
        # name every missing object.
        mutated = re.sub(r'\nCREATE VIRTUAL TABLE fragments_fts.*?user_version = 2;',
                         '\nPRAGMA user_version = 2;',
                         check_db_schema.SCHEMA.read_text(), count=1, flags=re.S)
        self.assertNotEqual(mutated, check_db_schema.SCHEMA.read_text())
        conn = check_db_schema.build_schema(mutated)
        virtual_issues = check_db_schema.check_virtual_tables(conn)
        trigger_issues = check_db_schema.check_triggers(conn)
        self.assertTrue(any("MISSING_VIRTUAL_TABLE: 'fragments_fts'" in i
                            for i in virtual_issues), virtual_issues)
        for name in ('fragments_fts_after_insert', 'fragments_fts_after_update',
                     'fragments_fts_before_delete', 'fragments_fts_before_update'):
            self.assertTrue(any(f"MISSING_TRIGGER: '{name}'" in i
                                for i in trigger_issues), trigger_issues)

    def test_stripped_content_options_fail_virtual_table_pin(self):
        # still a valid fts4 table, but no longer external content and with
        # the default tokenizer: the pin must catch the lost options without
        # the schema failing to build
        mutated = re.sub(r",\n    content='fragments',\n    tokenize='unicode61'\n",
                         '\n', check_db_schema.SCHEMA.read_text(), count=1)
        self.assertNotEqual(mutated, check_db_schema.SCHEMA.read_text())
        conn = check_db_schema.build_schema(mutated)
        issues = check_db_schema.check_virtual_tables(conn)
        self.assertTrue(any('VIRTUAL_TABLE_DRIFT: fragments_fts is missing '
                            '"content=\'fragments\'"' in i for i in issues), issues)
        self.assertTrue(any('VIRTUAL_TABLE_DRIFT: fragments_fts is missing '
                            '"tokenize=\'unicode61\'"' in i for i in issues), issues)

    def test_dropped_trigger_fails_trigger_pin(self):
        mutated = re.sub(r'\nCREATE TRIGGER fragments_fts_after_update.*?END;\n', '\n',
                         check_db_schema.SCHEMA.read_text(), count=1, flags=re.S)
        conn = check_db_schema.build_schema(mutated)
        issues = check_db_schema.check_triggers(conn)
        self.assertTrue(any("MISSING_TRIGGER: 'fragments_fts_after_update'" in i
                            for i in issues), issues)

    def test_mutated_trigger_event_fails_trigger_pin(self):
        # a valid trigger with the wrong event: the pin must catch both the
        # missing AFTER on the insert trigger and the lost 'OF text' on the
        # before-update trigger (the column the index actually tracks)
        mutated = check_db_schema.SCHEMA.read_text().replace(
            'AFTER INSERT ON fragments', 'BEFORE INSERT ON fragments', 1)
        mutated = mutated.replace('BEFORE UPDATE OF text ON fragments',
                                  'BEFORE UPDATE ON fragments', 1)
        conn = check_db_schema.build_schema(mutated)
        issues = check_db_schema.check_triggers(conn)
        self.assertTrue(any("TRIGGER_DRIFT: fragments_fts_after_insert must be a "
                            "'AFTER INSERT' trigger" in i for i in issues), issues)
        self.assertTrue(any("TRIGGER_DRIFT: fragments_fts_before_update must be a "
                            "'BEFORE UPDATE OF text' trigger" in i for i in issues), issues)

    def test_unexpected_trigger_fails_trigger_pin(self):
        conn = check_db_schema.build_schema(
            check_db_schema.SCHEMA.read_text()
            + "\nCREATE TRIGGER meta_spy AFTER INSERT ON meta BEGIN\n"
              "    SELECT 1;\nEND;\n")
        issues = check_db_schema.check_triggers(conn)
        self.assertTrue(any("UNEXPECTED_TRIGGER: 'meta_spy'" in i for i in issues),
                        issues)

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
