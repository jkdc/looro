import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
import export_readings as er
import export_assessments as ea


def minimal_reading():
    return {
        "id": "en-read-test",
        "languageID": "en",
        "title": "A test reading",
        "level": "A1",
        "topic": "tests",
        "text": " ".join(["word"] * 60),
        "questions": [
            {"id": "en-read-test-q1", "kind": "choice", "prompt": "What?",
             "options": ["A thing", "Another thing"], "answers": ["A thing"], "explain": "Because."},
            {"id": "en-read-test-q2", "kind": "written", "prompt": "Who?", "answers": ["<name>"], "explain": "Because."},
            {"id": "en-read-test-q3", "kind": "choice", "prompt": "When?",
             "options": ["Now", "Later", "Never"], "answers": ["Now"], "explain": "Because."},
            {"id": "en-read-test-q4", "kind": "written", "prompt": "Why?", "answers": ["why not"], "explain": "Because."},
        ],
    }


def minimal_checkpoint():
    questions = []
    for index in range(1, 13):
        questions.append({"id": f"checkpoint-en-a9-q{index}", "kind": "choice", "skill": "grammar:verb-be",
                          "prompt": f"Question {index}?", "options": ["a", "b"], "answers": ["a"], "explain": "Because."})
    return {
        "id": "checkpoint-en-a9",
        "languageID": "en",
        "level": "A1",
        "title": "A9 checkpoint",
        "thresholds": {"overall": 70},
        "questions": questions,
    }


class ExportReadingsTest(unittest.TestCase):
    def test_valid_reading_passes_validation(self):
        seen = set()
        er.validate(minimal_reading(), ["en"], seen, {}, set())
        self.assertIn("en-read-test", seen)

    def test_reading_needs_four_questions(self):
        text = minimal_reading()
        del text["questions"][3]
        with self.assertRaises(SystemExit):
            er.validate(text, ["en"], set(), {}, set())

    def test_unknown_skill_rejected(self):
        text = minimal_reading()
        text["skills"] = ["reading:nonexistent"]
        with self.assertRaises(SystemExit):
            er.validate(text, ["en"], set(), {}, set())

    def test_root_rejects_missing_or_wrong_schema_version(self):
        with self.assertRaises(SystemExit):
            er.validate_root("en-readings.json", {"readings": []})
        with self.assertRaises(SystemExit):
            er.validate_root("en-readings.json", {"schemaVersion": 3, "readings": []})
        self.assertEqual(1, len(er.validate_root("en-readings.json", {"schemaVersion": 1, "readings": [{"id": "x"}]})))

    def test_emitters_carry_topic_and_skills(self):
        text = minimal_reading()
        text["skills"] = ["reading:gist"]
        swift = er.emit_swift([text])
        kotlin = er.emit_kotlin([text], "package com.jkdc.app.core")
        self.assertIn('ReadingText(id: "en-read-test"', swift)
        self.assertIn('skills: ["reading:gist"]', swift)
        self.assertIn('ReadingText(id = "en-read-test"', kotlin)
        self.assertIn('skills = listOf("reading:gist")', kotlin)


class ExportAssessmentsTest(unittest.TestCase):
    def test_valid_checkpoint_passes_validation(self):
        seen = set()
        ea.validate(minimal_checkpoint(), ["en"], seen, ea.skill_ids())
        self.assertIn("checkpoint-en-a9", seen)

    def test_checkpoint_needs_twelve_questions(self):
        checkpoint = minimal_checkpoint()
        del checkpoint["questions"][11]
        with self.assertRaises(SystemExit):
            ea.validate(checkpoint, ["en"], set(), ea.skill_ids())

    def test_checkpoint_question_needs_a_known_skill(self):
        checkpoint = minimal_checkpoint()
        checkpoint["questions"][0]["skill"] = "grammar:nonexistent"
        with self.assertRaises(SystemExit):
            ea.validate(checkpoint, ["en"], set(), ea.skill_ids())

    def test_gap_needs_a_blank(self):
        checkpoint = minimal_checkpoint()
        checkpoint["questions"][0]["kind"] = "gap"
        with self.assertRaises(SystemExit):
            ea.validate(checkpoint, ["en"], set(), ea.skill_ids())

    def test_thresholds_must_be_percentages(self):
        checkpoint = minimal_checkpoint()
        checkpoint["thresholds"] = {"overall": 250}
        with self.assertRaises(SystemExit):
            ea.validate(checkpoint, ["en"], set(), ea.skill_ids())

    def test_emitters_carry_thresholds_and_skills(self):
        checkpoint = minimal_checkpoint()
        swift = ea.emit_swift([checkpoint])
        kotlin = ea.emit_kotlin([checkpoint], "package com.jkdc.app.core")
        self.assertIn('Checkpoint(id: "checkpoint-en-a9"', swift)
        self.assertIn('skill: "grammar:verb-be"', swift)
        self.assertIn('Checkpoint(id = "checkpoint-en-a9"', kotlin)
        self.assertIn('skill = "grammar:verb-be"', kotlin)
        self.assertIn('"overall" to 70', kotlin)


if __name__ == "__main__":
    unittest.main()
