import copy
import hashlib
import json
import pathlib
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
import export_leveltests as exporter

ROOT = pathlib.Path(__file__).resolve().parents[2]


class AssessmentExportTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = pathlib.Path(self.directory.name)
        self.design = json.loads((ROOT / 'shared/leveltests/en-assessment-design.json').read_text())
        for relative in ['shared/leveltests/exam-models.json', 'shared/skills/skill-catalog.json']:
            destination = self.root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_text((ROOT / relative).read_text())

    def write(self, rehash=True):
        if rehash:
            digest = hashlib.sha256(json.dumps({k: v for k, v in self.design.items() if k != 'review'},
                sort_keys=True, ensure_ascii=False, separators=(',', ':')).encode()).hexdigest()
            self.design['review'] = {'status': 'approved', 'kind': 'automated-llm', 'model': 'unit-test-reviewer',
                'rubricVersion': 1, 'contentHash': digest, 'evidence': 'Controlled test review, not production approval.'}
        (self.root / 'shared/leveltests/en-assessment-design.json').write_text(json.dumps(self.design))

    def models(self):
        with patch.object(exporter, 'ROOT', self.root):
            return exporter.exam_models()['levels']

    def test_embeds_reviewed_coverage_examples_and_catalog_only_for_english(self):
        self.write()
        models = self.models()
        english = next(m for m in models if m['languageID'] == 'en' and m['level'] == 'A1')
        self.assertIn('assessment', english)
        self.assertEqual(english['assessment']['revision'], 1)
        self.assertIn('grammar:verb-be', english['assessment']['sections']['recognition']['requiredSkills'])
        reference = english['assessment']['sections']['reading']['example']
        self.assertIn('The library is open', reference['passage']['text'])
        self.assertEqual(len(reference['items']), 3)
        self.assertIn('reading:gist', english['assessment']['catalogIDs'])
        self.assertTrue(all('assessment' not in m for m in models if m['languageID'] != 'en'))

    def test_unapproved_or_stale_automated_review_cannot_activate(self):
        for change in ['pending', 'tampered']:
            with self.subTest(change=change):
                self.write()
                if change == 'pending':
                    self.design['review']['status'] = 'pending'
                else:
                    self.design['levels'][0]['sections']['reading']['example']['passage']['text'] += ' Changed.'
                self.write(rehash=False)
                with self.assertRaises(SystemExit):
                    self.models()

    def test_unknown_catalog_and_infeasible_coverage_are_rejected(self):
        for invalid in ['unknown', 'infeasible', 'missing_level', 'bad_example']:
            with self.subTest(invalid=invalid):
                original = copy.deepcopy(self.design)
                if invalid == 'unknown':
                    self.design['levels'][0]['sections']['recognition']['requiredSkills'][0] = 'grammar:invented'
                elif invalid == 'infeasible':
                    self.design['levels'][0]['sections']['writing']['requiredSkills'] *= 5
                elif invalid == 'missing_level':
                    self.design['levels'].pop()
                else:
                    self.design['levels'][0]['sections']['reading']['example']['items'][0]['answers'] = ['absent key']
                self.write()
                with self.assertRaises(SystemExit):
                    self.models()
                self.design = original


if __name__ == '__main__':
    unittest.main()
