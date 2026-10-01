import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import content_health


class ParityTest(unittest.TestCase):
    def test_gap_detected_when_below_target(self):
        courses = [{'id': 'es-foundations', 'units': [{'level': 'A1', 'lessons': [{}, {}]}]}]
        issues = []
        content_health.check_parity(courses, [], [], [], issues)
        self.assertIn('PARITY_GAP: es A1 lessons 2/40', issues)
        self.assertIn('PARITY_GAP: es checkpoint A1 missing', issues)

    def test_no_lessons_gap_when_target_met(self):
        units = [{'level': lvl, 'lessons': [{}] * n}
                 for lvl, n in content_health.PARITY_LESSONS.items()]
        listens = [{'languageID': 'es', 'level': lvl, 'id': f'x{lvl}-{i}'}
                   for lvl, n in content_health.PARITY_LISTENINGS.items()
                   for i in range(n)]
        reads = [{'languageID': 'es', 'level': lvl, 'id': f'r{lvl}-{i}'}
                 for lvl, n in content_health.PARITY_READINGS.items()
                 for i in range(n)]
        cps = [{'languageID': 'es', 'level': lvl, 'id': f'cp-{lvl}'}
               for lvl in content_health.PARITY_CHECKPOINTS]
        issues = []
        content_health.check_parity([{'id': 'es-foundations', 'units': units}],
                                    listens, reads, cps, issues)
        self.assertEqual([], [i for i in issues if i.startswith('PARITY_GAP: es')])

    def test_english_and_tracks_are_not_measured(self):
        issues = []
        content_health.check_parity([{'id': 'en-foundations', 'units': []},
                                     {'id': 'en-work-track', 'units': []}],
                                    [], [], [], issues)
        self.assertEqual([], [i for i in issues if i.startswith('PARITY_GAP: en')])


if __name__ == '__main__':
    unittest.main()
