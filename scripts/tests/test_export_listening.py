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
        "topic": "tests",
        "text": " ".join(["word"] * 60),
        "questions": [
            {"id": "en-listen-test-q1", "kind": "choice", "prompt": "What?",
             "options": ["A thing", "Another thing"], "answers": ["A thing"], "explain": "Because."},
            {"id": "en-listen-test-q2", "kind": "written", "prompt": "Who?", "answers": ["<name>"], "explain": "Because."},
            {"id": "en-listen-test-q3", "kind": "choice", "prompt": "When?",
             "options": ["Now", "Later", "Never"], "answers": ["Now"], "explain": "Because."},
            {"id": "en-listen-test-q4", "kind": "choice", "prompt": "Where?",
             "options": ["Here", "There"], "answers": ["Here"], "explain": "Because."},
            {"id": "en-listen-test-q5", "kind": "written", "prompt": "Why?", "answers": ["why not"], "explain": "Because."},
        ],
    }


class ExportListeningTest(unittest.TestCase):
    def test_valid_text_passes_validation(self):
        seen = set()
        el.validate(minimal_text(), ["en"], seen, {})
        self.assertEqual(seen, {f"en-listen-test-q{n}" for n in range(1, 6)} | {"en-listen-test"})

    def test_unknown_language_rejected(self):
        with self.assertRaises(SystemExit):
            el.validate(minimal_text(), ["fr"], set(), {})

    def test_text_length_is_bounded(self):
        text = minimal_text()
        text["text"] = "too short"
        with self.assertRaises(SystemExit):
            el.validate(text, ["en"], set(), {})
        text["text"] = " ".join(["word"] * 201)
        with self.assertRaises(SystemExit):
            el.validate(text, ["en"], set(), {})

    def test_topic_is_required(self):
        text = minimal_text()
        del text["topic"]
        with self.assertRaises(SystemExit):
            el.validate(text, ["en"], set(), {})

    def test_needs_five_questions(self):
        text = minimal_text()
        del text["questions"][4]
        with self.assertRaises(SystemExit):
            el.validate(text, ["en"], set(), {})

    def test_unit_id_must_exist_match_language_and_level(self):
        units = {"en-foundations-u1": ("en", "A1")}
        text = minimal_text()
        text["unitID"] = "en-foundations-u1"
        el.validate(text, ["en"], set(), units)  # ok
        text["unitID"] = "en-foundations-missing"
        with self.assertRaises(SystemExit):
            el.validate(text, ["en"], set(), units)
        text["unitID"] = "es-foundations-u1"
        with self.assertRaises(SystemExit):
            el.validate(text, ["en"], set(), {"es-foundations-u1": ("es", "A1")})
        text["unitID"] = "en-foundations-u1"
        with self.assertRaises(SystemExit):
            el.validate(text, ["en"], set(), {"en-foundations-u1": ("en", "A2")})

    def test_question_kind_is_closed(self):
        text = minimal_text()
        text["questions"][0]["kind"] = "order"
        with self.assertRaises(SystemExit):
            el.validate(text, ["en"], set(), {})

    def test_choice_answers_must_be_options(self):
        text = minimal_text()
        text["questions"][0]["answers"] = ["Not listed"]
        with self.assertRaises(SystemExit):
            el.validate(text, ["en"], set(), {})

    def test_emitters_carry_texts_and_questions(self):
        text = minimal_text()
        text["unitID"] = "en-foundations-u1"
        swift = el.emit_swift([text])
        kotlin = el.emit_kotlin([text], "package com.jkdc.app.core")
        self.assertIn('ListeningText(id: "en-listen-test"', swift)
        self.assertIn('ListeningText(id = "en-listen-test"', kotlin)
        self.assertIn('Exercise(id: "en-listen-test-q1"', swift)
        self.assertIn("Exercise(id = \"en-listen-test-q1\"", kotlin)
        self.assertIn('topic: "tests"', swift)
        self.assertIn('unitID: "en-foundations-u1"', swift)
        self.assertIn("topic = \"tests\"", kotlin)
        self.assertIn("unitID = \"en-foundations-u1\"", kotlin)

    def test_unit_id_is_omitted_when_absent(self):
        swift = el.emit_swift([minimal_text()])
        kotlin = el.emit_kotlin([minimal_text()], "package com.jkdc.app.core")
        self.assertNotIn("unitID", swift)
        self.assertNotIn("unitID", kotlin)


if __name__ == "__main__":
    unittest.main()
