import unittest

from scripts import export_leveltests, export_placement


class SwiftExamExportTests(unittest.TestCase):
    def test_placement_swift_export_nests_exercise_and_defaults_missing_options(self):
        bank = {
            'languageID': 'en', 'version': 1,
            'items': [{
                'id': 'pl-en-a1-core-1', 'level': 'A1', 'mode': 'core', 'kind': 'gap',
                'skill': 'grammar:articles', 'prompt': 'an ___', 'answers': ['idea'], 'explain': 'e',
            }],
        }

        swift = export_placement.emit_swift([bank])

        self.assertIn('public enum PlacementData', swift)
        self.assertIn('"options":[]', swift)
        self.assertIn('"exercise":{"id":"pl-en-a1-core-1"', swift)

    def test_level_test_swift_export_preserves_passage_identity_and_unit_id(self):
        bank = {
            'languageID': 'en',
            'levels': [{
                'level': 'A1', 'version': 1, 'thresholds': {'overall': 70},
                'passages': [{'id': 'p1', 'title': 'Passage', 'text': 'Text'}],
                'pool': [{
                    'id': 'unit-e1#r', 'section': 'reading', 'unitID': 'unit-1', 'passageId': 'p1',
                    'kind': 'gap', 'prompt': 'q', 'answers': ['a'], 'options': None, 'explain': 'e',
                }],
            }],
        }

        swift = export_leveltests.emit_swift([bank])

        self.assertIn('public enum LevelTestsData', swift)
        self.assertIn('"unitID":"unit-1"', swift)
        self.assertIn('"passageId":"p1"', swift)
        self.assertIn('"options":[]', swift)


if __name__ == '__main__':
    unittest.main()
