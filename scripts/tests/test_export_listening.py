import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
import export_listening as el


def minimal_text():
    return {
        "id": "en-listen-test",
        "languageID": "en",
        "title": "A test text",
        "level": "A1",
        "text": " ".join(["word"] * 60),
        "questions": [
            {"id": "en-listen-test-q1", "kind": "choice", "prompt": "What?",
             "options": ["A thing", "Another thing"], "answers": ["A thing"], "explain": "Because."},
            {"id": "en-listen-test-q2", "kind": "written", "prompt": "Who?", "answers": ["<name>"], "explain": "Because."},
            {"id": "en-listen-test-q3", "kind": "choice", "prompt": "When?",
             "options": ["Now", "Later", "Never"], "answers": ["Now"], "explain": "Because."},
        ],
    }


class ExportListeningTest(unittest.TestCase):
    def test_valid_text_passes_validation(self):
        seen = set()
        el.validate(minimal_text(), ["en"], seen)
        self.assertEqual(seen, {"en-listen-test", "en-listen-test-q1", "en-listen-test-q2", "en-listen-test-q3"})

    def test_unknown_language_rejected(self):
        with self.assertRaises(SystemExit):
            el.validate(minimal_text(), ["fr"], set())

    def test_text_length_is_bounded(self):
        text = minimal_text()
        text["text"] = "too short"
        with self.assertRaises(SystemExit):
            el.validate(text, ["en"], set())
        text["text"] = " ".join(["word"] * 201)
        with self.assertRaises(SystemExit):
            el.validate(text, ["en"], set())

    def test_needs_three_questions(self):
        text = minimal_text()
        del text["questions"][2]
        with self.assertRaises(SystemExit):
            el.validate(text, ["en"], set())

    def test_question_kind_is_closed(self):
        text = minimal_text()
        text["questions"][0]["kind"] = "order"
        with self.assertRaises(SystemExit):
            el.validate(text, ["en"], set())

    def test_choice_answers_must_be_options(self):
        text = minimal_text()
        text["questions"][0]["answers"] = ["Not listed"]
        with self.assertRaises(SystemExit):
            el.validate(text, ["en"], set())

    def test_emitters_carry_texts_and_questions(self):
        text = minimal_text()
        swift = el.emit_swift([text])
        kotlin = el.emit_kotlin([text], "package com.jkdc.app.core")
        self.assertIn('ListeningText(id: "en-listen-test"', swift)
        self.assertIn('ListeningText(id = "en-listen-test"', kotlin)
        self.assertIn('Exercise(id: "en-listen-test-q1"', swift)
        self.assertIn("Exercise(id = \"en-listen-test-q1\"", kotlin)


if __name__ == "__main__":
    unittest.main()
