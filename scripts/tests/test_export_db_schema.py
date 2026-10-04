import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import export_db_schema


class SplitTest(unittest.TestCase):
    def setUp(self):
        self.statements = export_db_schema.split_statements(
            export_db_schema.SCHEMA.read_text())

    def test_schema_splits_into_pinned_statements(self):
        self.assertEqual(15, len(self.statements))
        self.assertTrue(self.statements[0].startswith('CREATE TABLE meta'))
        self.assertTrue(self.statements[-1].startswith('PRAGMA user_version'))
        self.assertEqual([], [s for s in self.statements if ';' in s])

    def test_every_pinned_statement_is_accounted_for(self):
        joined = '\n'.join(self.statements)
        for needle in ('CREATE TABLE meta', 'CREATE TABLE preferences',
                       'CREATE TABLE sessions', 'CREATE TABLE fragments',
                       'CREATE TABLE expressions', 'CREATE TABLE lesson_state',
                       'CREATE TABLE completions', 'CREATE TABLE skill_errors',
                       'CREATE TABLE usage_outbox', 'CREATE TABLE assessment_tickets',
                       'CREATE INDEX fragments_session', 'CREATE INDEX expressions_due',
                       'CREATE INDEX completions_lang', 'CREATE INDEX outbox_pending'):
            self.assertIn(needle, joined)

    def test_semicolons_inside_strings_and_comments_do_not_split(self):
        sql = ("INSERT INTO meta VALUES ('a;b'); -- a comment; with a semicolon\n"
               "INSERT INTO meta VALUES ('it''s; fine'); /* block; comment */\n"
               "PRAGMA user_version = 1;\n")
        self.assertEqual(["INSERT INTO meta VALUES ('a;b')",
                          "INSERT INTO meta VALUES ('it''s; fine')",
                          'PRAGMA user_version = 1'],
                         export_db_schema.split_statements(sql))

    def test_unterminated_string_or_block_comment_and_missing_semicolon_fail(self):
        with self.assertRaisesRegex(ValueError, 'unterminated string'):
            export_db_schema.split_statements("INSERT INTO meta VALUES ('a;b)")
        with self.assertRaisesRegex(ValueError, 'unterminated block comment'):
            export_db_schema.split_statements('/* never closed')
        with self.assertRaisesRegex(ValueError, 'without a terminating'):
            export_db_schema.split_statements('PRAGMA user_version = 1')

    def test_smoke_test_executes_every_statement_and_ends_at_version_one(self):
        conn = export_db_schema.smoke_test(self.statements)
        self.assertEqual(export_db_schema.SCHEMA_VERSION,
                         conn.execute('PRAGMA user_version').fetchone()[0])
        self.assertEqual(1, conn.execute(
            "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' "
            "AND name = 'fragments'").fetchone()[0])

    def test_smoke_test_fails_when_the_pragma_is_missing(self):
        without_pragma = self.statements[:-1]
        with self.assertRaisesRegex(ValueError, 'user_version'):
            export_db_schema.smoke_test(without_pragma)


class KotlinTest(unittest.TestCase):
    def test_string_literal_escapes_kotlin_specials(self):
        self.assertEqual('"a\\\\b\\"c\\$d\\n"', export_db_schema.kotlin_string('a\\b"c$d\n'))

    def test_generated_source_is_plain_kotlin_with_the_statement_list(self):
        source = export_db_schema.generate_kotlin(
            export_db_schema.split_statements(export_db_schema.SCHEMA.read_text()))
        self.assertIn('package com.jkdc.app.data', source)
        self.assertIn('object SchemaData', source)
        self.assertIn('val LEARNING_SCHEMA_STATEMENTS: List<String> = listOf(', source)
        self.assertIn('const val SCHEMA_VERSION = 1', source)
        self.assertIn('"PRAGMA user_version = 1"', source)


class SwiftTest(unittest.TestCase):
    def test_string_literal_escapes_swift_specials_but_not_the_dollar(self):
        self.assertEqual('"a\\\\b\\"c$d\\n\\t"',
                         export_db_schema.swift_string('a\\b"c$d\n\t'))

    def test_generated_source_is_swift_with_the_same_statement_list(self):
        statements = export_db_schema.split_statements(
            export_db_schema.SCHEMA.read_text())
        source = export_db_schema.generate_swift(statements)
        self.assertIn('public enum SchemaData', source)
        self.assertIn('public static let learningSchemaStatements: [String] = [', source)
        self.assertIn('public static let schemaVersion = 1', source)
        self.assertIn('"PRAGMA user_version = 1"', source)
        # the Kotlin and Swift literals carry exactly the same statements
        kotlin = export_db_schema.generate_kotlin(statements)
        for statement in statements:
            self.assertIn(export_db_schema.swift_string(statement), source)
            self.assertIn(export_db_schema.kotlin_string(statement), kotlin)
        self.assertEqual(source.count('\\n'), kotlin.count('\\n'))

    def test_check_mode_passes_on_the_committed_files(self):
        self.assertEqual(0, export_db_schema.main(['--check']))


if __name__ == '__main__':
    unittest.main()
