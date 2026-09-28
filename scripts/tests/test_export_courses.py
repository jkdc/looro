import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
import export_courses as ec


def minimal_course():
    return {
        "id": "en-foundations",
        "languageID": "en",
        "title": "Foundations",
        "subtitle": "Start from zero",
        "symbol": "graduationcap",
        "description": "First sentences.",
        "units": [{
            "id": "en-foundations-u1",
            "title": "First sentences",
            "subtitle": "Say who you are",
            "level": "A1",
            "themes": [{
                "id": "lang-exchange", "title": "Café", "subtitle": "Meet and greet",
                "symbol": "person.2", "category": "Course", "situation": "Meet the learner.", "colorIndex": 0,
            }],
            "lessons": [{
                "id": "en-foundations-u1-to-be",
                "title": "The verb “to be”",
                "goal": "Introduce yourself.",
                "intro": {
                    "explanation": ["One verb.", "Three forms."],
                    "examples": [{"text": "I'm Ana.", "meaning": "Your name."}],
                },
                "expressions": [{"text": "I'm …", "meaning": "Introduce yourself.", "note": "With your name."}],
                "situation": "Meet someone new.",
                "practice": {"questions": [{
                    "prompt": "What's your name?",
                    "expected": ["I'm <name>."],
                    "focus": "I am",
                    "watch": "Name without the verb.",
                }]},
                "exercises": [
                    {"id": "en-foundations-u1-to-be-e1", "kind": "choice", "prompt": "She ___ a doctor.",
                     "options": ["is", "are"], "answers": ["is"], "explain": "With she use is."},
                    {"id": "en-foundations-u1-to-be-e2", "kind": "gap", "prompt": "They ___ my friends.",
                     "answers": ["are"], "explain": "With they use are."},
                    {"id": "en-foundations-u1-to-be-e3", "kind": "order", "prompt": "Say your country.",
                     "answers": ["I am from Spain"], "explain": "Am goes with I."},
                    {"id": "en-foundations-u1-to-be-e4", "kind": "written", "prompt": "Say your name.",
                     "answers": ["I'm <name>"], "explain": "The verb is never dropped."},
                ],
            }],
        }],
    }


class ExportCoursesTest(unittest.TestCase):
    def test_valid_course_passes_validation(self):
        seen = set()
        ec.validate(minimal_course(), ["en"], seen)
        self.assertEqual(seen, {"en-foundations", "en-foundations-u1", "en-foundations-u1-to-be"})

    def test_unit_requires_level(self):
        course = minimal_course()
        del course["units"][0]["level"]
        with self.assertRaises(SystemExit):
            ec.validate(course, ["en"], set())

    def test_unit_level_must_be_non_blank(self):
        course = minimal_course()
        course["units"][0]["level"] = "  "
        with self.assertRaises(SystemExit):
            ec.validate(course, ["en"], set())

    def test_unknown_language_rejected(self):
        with self.assertRaises(SystemExit):
            ec.validate(minimal_course(), ["fr"], set())

    def test_duplicate_unit_id_rejected(self):
        course = minimal_course()
        course["units"].append(dict(course["units"][0]))
        with self.assertRaises(SystemExit):
            ec.validate(course, ["en"], set())

    def test_question_missing_guidance_rejected(self):
        course = minimal_course()
        del course["units"][0]["lessons"][0]["practice"]["questions"][0]["watch"]
        with self.assertRaises(SystemExit):
            ec.validate(course, ["en"], set())

    def test_lessons_need_exercises(self):
        course = minimal_course()
        del course["units"][0]["lessons"][0]["exercises"]
        with self.assertRaises(SystemExit):
            ec.validate(course, ["en"], set())

    def test_exercise_kind_is_closed(self):
        course = minimal_course()
        course["units"][0]["lessons"][0]["exercises"][0]["kind"] = "crossword"
        with self.assertRaises(SystemExit):
            ec.validate(course, ["en"], set())

    def test_choice_answers_must_be_options(self):
        course = minimal_course()
        course["units"][0]["lessons"][0]["exercises"][0]["answers"] = ["was"]
        with self.assertRaises(SystemExit):
            ec.validate(course, ["en"], set())

    def test_gap_needs_a_blank_and_order_needs_a_sentence(self):
        course = minimal_course()
        course["units"][0]["lessons"][0]["exercises"][1]["prompt"] = "They are my friends."
        with self.assertRaises(SystemExit):
            ec.validate(course, ["en"], set())
        course = minimal_course()
        course["units"][0]["lessons"][0]["exercises"][2]["answers"] = ["Hi"]
        with self.assertRaises(SystemExit):
            ec.validate(course, ["en"], set())

    def test_emitters_carry_unit_level(self):
        course = minimal_course()
        swift = ec.emit_swift([course])
        kotlin = ec.emit_kotlin([course], "package com.jkdc.app.core")
        self.assertIn('level: "A1"', swift)
        self.assertIn("level = \"A1\"", kotlin)
        self.assertIn('ConversationTheme("lang-exchange"', swift)
        self.assertIn("ConversationTheme(\"lang-exchange\"", kotlin)

    def test_emitters_carry_exercises(self):
        course = minimal_course()
        swift = ec.emit_swift([course])
        kotlin = ec.emit_kotlin([course], "package com.jkdc.app.core")
        self.assertIn('Exercise(id: "en-foundations-u1-to-be-e1"', swift)
        self.assertIn("Exercise(id = \"en-foundations-u1-to-be-e1\"", kotlin)
        self.assertIn("exercises: [", swift)
        self.assertIn("exercises = listOf(", kotlin)


if __name__ == "__main__":
    unittest.main()
