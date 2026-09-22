#!/usr/bin/env python3
"""Generate the per-platform course content from the canonical JSON in
`shared/courses/`.

Course temarios are content, not logic: they are authored once as JSON and
emitted into BOTH cores (`CoursesData.swift` / `CoursesData.kt`), so neither
platform can drift — the symmetric counterpart of what
`export_android_content.py` does for language content (there Swift is the
canonical side because it predates the pipeline). Run `--check` in CI or
before committing to detect drift.
"""
import argparse
import glob
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
COURSES_DIR = ROOT / 'shared' / 'courses'
SWIFT_DEST = ROOT / 'apps/ios/Core/CoursesData.swift'

REQUIRED_COURSE = ('id', 'languageID', 'title', 'subtitle', 'symbol', 'description')
REQUIRED_UNIT = ('id', 'title', 'subtitle', 'level')
REQUIRED_QUESTION = ('prompt', 'expected', 'focus', 'watch')
REQUIRED_THEME = ('id', 'title', 'subtitle', 'symbol', 'category', 'situation', 'colorIndex')


def q(text):
    """A string literal valid in both Swift and Kotlin (both accept \\" and \\\\;
    non-ASCII stays literal so the files stay readable)."""
    out = json.dumps(text, ensure_ascii=False)
    return out.replace('$', r'\$')


def language_ids():
    """Target-language ids declared by the Swift language registry."""
    registry = (ROOT / 'apps/ios/Core/Languages/LanguageModule.swift').read_text()
    match = re.search(r'public static let all:\s*\[LanguageModule\]\s*=\s*\[([^\]]*)\]', registry)
    if not match:
        raise SystemExit('LanguageRegistry.all not found in apps/ios/Core/Languages/LanguageModule.swift.')
    names = re.findall(r'\.(\w+)', match.group(1))
    ids = []
    for name in names:
        text = (ROOT / 'apps/ios/Core/Languages' / f'{name[0].upper()}{name[1:]}.swift').read_text()
        id_match = re.search(r'\bid:\s*"([^"]+)"', text)
        if not id_match:
            raise SystemExit(f'Language id not found in {name}.swift.')
        ids.append(id_match.group(1))
    return ids


def validate(course, known_languages, seen_ids):
    def need(cond, message):
        if not cond:
            raise SystemExit(f'{course.get("id", "?")}: {message}')

    for key in REQUIRED_COURSE:
        need(isinstance(course.get(key), str) and course[key].strip(), f'missing course field {key}.')
    need(course['languageID'] in known_languages, f'unknown languageID {course["languageID"]!r}.')
    need(course['id'] not in seen_ids, f'duplicate course id {course["id"]!r}.')
    seen_ids.add(course['id'])
    need(isinstance(course.get('units'), list) and course['units'], 'course needs at least one unit.')
    for unit in course['units']:
        for key in REQUIRED_UNIT:
            need(isinstance(unit.get(key), str) and unit[key].strip(), f'missing unit field {key}.')
        need(unit['id'] not in seen_ids, f'duplicate unit id {unit["id"]!r}.')
        seen_ids.add(unit['id'])
        themes = unit.get('themes', [])
        need(isinstance(themes, list) and themes, f'unit {unit["id"]} needs at least one focused theme.')
        unit_theme_ids = set()
        for theme in themes:
            for key in REQUIRED_THEME:
                need(key in theme, f'unit {unit["id"]}: theme missing {key}.')
            need(theme['id'] not in unit_theme_ids, f'unit {unit["id"]}: duplicate theme {theme["id"]!r}.')
            unit_theme_ids.add(theme['id'])
        lessons = unit.get('lessons', [])
        need(isinstance(lessons, list) and lessons, f'unit {unit["id"]} needs at least one lesson.')
        for lesson in lessons:
            for key in ('id', 'title', 'goal', 'situation'):
                need(isinstance(lesson.get(key), str) and lesson[key].strip(), f'missing lesson field {key}.')
            need(lesson['id'] not in seen_ids, f'duplicate lesson id {lesson["id"]!r}.')
            seen_ids.add(lesson['id'])
            intro = lesson.get('intro', {})
            need(intro.get('explanation') and intro.get('examples'),
                 f'lesson {lesson["id"]} needs explanation and examples.')
            expressions = lesson.get('expressions', [])
            need(expressions, f'lesson {lesson["id"]} needs target expressions.')
            for expression in expressions:
                for key in ('text', 'meaning', 'note'):
                    need(isinstance(expression.get(key), str) and expression[key].strip(),
                         f'lesson {lesson["id"]}: expression missing {key}.')
            questions = lesson.get('practice', {}).get('questions', [])
            need(questions, f'lesson {lesson["id"]} needs practice questions.')
            for question in questions:
                for key in REQUIRED_QUESTION:
                    need(key in question and question[key], f'lesson {lesson["id"]}: question missing {key}.')
                need(isinstance(question['expected'], list) and question['expected'],
                     f'lesson {lesson["id"]}: question needs expected answers.')


def emit_swift(courses):
    out = ['// Generated by scripts/export_courses.py; edit shared/courses/*.json instead.', '',
           'public enum CoursesData {', '    public static let all: [Course] = [']
    blocks = []
    for course in courses:
        unit_blocks = []
        for unit in course['units']:
            theme_args = ',\n                    '.join(
                'ConversationTheme(' + ', '.join(q(t[k]) for k in REQUIRED_THEME) + ')' for t in unit['themes'])
            lesson_blocks = []
            for lesson in unit['lessons']:
                explanation = ',\n                            '.join(q(x) for x in lesson['intro']['explanation'])
                examples = ',\n                            '.join(
                    f'ExampleLine(text: {q(e["text"])}, meaning: {q(e["meaning"])})'
                    for e in lesson['intro']['examples'])
                expressions = ',\n                            '.join(
                    f'TargetExpression(text: {q(e["text"])}, meaning: {q(e["meaning"])}, note: {q(e["note"])})'
                    for e in lesson['expressions'])
                questions = ',\n                                '.join(
                    'PracticeQuestion(prompt: ' + q(qq['prompt']) + ', expected: ['
                    + ', '.join(q(x) for x in qq['expected']) + '], focus: ' + q(qq['focus'])
                    + ', watch: ' + q(qq['watch']) + ')'
                    for qq in lesson['practice']['questions'])
                lesson_blocks.append(
                    '                    Lesson(id: ' + q(lesson['id']) + ', title: ' + q(lesson['title'])
                    + ', goal: ' + q(lesson['goal']) + ',\n'
                    '                        intro: LessonIntro(explanation: [\n                            '
                    + explanation + '], examples: [\n                            ' + examples + ']),\n'
                    '                        expressions: [\n                            ' + expressions + '],\n'
                    '                        situation: ' + q(lesson['situation']) + ',\n'
                    '                        practice: LessonPractice(questions: [\n                                '
                    + questions + ']))')
            unit_blocks.append(
                '            CourseUnit(id: ' + q(unit['id']) + ', title: ' + q(unit['title'])
                + ', subtitle: ' + q(unit['subtitle']) + ', level: ' + q(unit['level'])
                + ', themes: [\n                    ' + theme_args
                + '], lessons: [\n' + ',\n'.join(lesson_blocks) + '])')
        blocks.append(
            '        Course(id: ' + q(course['id']) + ', languageID: ' + q(course['languageID'])
            + ', title: ' + q(course['title']) + ', subtitle: ' + q(course['subtitle'])
            + ', symbol: ' + q(course['symbol']) + ', description: ' + q(course['description'])
            + ', units: [\n' + ',\n'.join(unit_blocks) + '])')
    out += [',\n'.join(blocks), '    ]', '}', '']
    return '\n'.join(out)


def emit_kotlin(courses, package_line):
    out = ['// Generated by scripts/export_courses.py; edit shared/courses/*.json instead.', '',
           package_line, '',
           'object CoursesData { val all = listOf(']
    blocks = []
    for course in courses:
        unit_blocks = []
        for unit in course['units']:
            theme_args = ',\n                '.join(
                'ConversationTheme(' + ', '.join(q(t[k]) for k in REQUIRED_THEME) + ')' for t in unit['themes'])
            lesson_blocks = []
            for lesson in unit['lessons']:
                explanation = ', '.join(q(x) for x in lesson['intro']['explanation'])
                examples = ', '.join(f'ExampleLine(text = {q(e["text"])}, meaning = {q(e["meaning"])})'
                                     for e in lesson['intro']['examples'])
                expressions = ', '.join(f'TargetExpression(text = {q(e["text"])}, meaning = {q(e["meaning"])}, note = {q(e["note"])})'
                                        for e in lesson['expressions'])
                questions = ', '.join(
                    'PracticeQuestion(prompt = ' + q(qq['prompt']) + ', expected = listOf('
                    + ', '.join(q(x) for x in qq['expected']) + '), focus = ' + q(qq['focus'])
                    + ', watch = ' + q(qq['watch']) + ')'
                    for qq in lesson['practice']['questions'])
                lesson_blocks.append(
                    '            Lesson(id = ' + q(lesson['id']) + ', title = ' + q(lesson['title'])
                    + ', goal = ' + q(lesson['goal']) + ', intro = LessonIntro(explanation = listOf('
                    + explanation + '), examples = listOf(' + examples + ')), expressions = listOf('
                    + expressions + '), situation = ' + q(lesson['situation'])
                    + ', practice = LessonPractice(questions = listOf(' + questions + ')))')
            unit_blocks.append(
                '        CourseUnit(id = ' + q(unit['id']) + ', title = ' + q(unit['title'])
                + ', subtitle = ' + q(unit['subtitle']) + ', level = ' + q(unit['level'])
                + ', themes = listOf(' + theme_args
                + '), lessons = listOf(' + ', '.join(lesson_blocks) + '))')
        blocks.append(
            '    Course(id = ' + q(course['id']) + ', languageID = ' + q(course['languageID'])
            + ', title = ' + q(course['title']) + ', subtitle = ' + q(course['subtitle'])
            + ', symbol = ' + q(course['symbol']) + ', description = ' + q(course['description'])
            + ', units = listOf(' + ', '.join(unit_blocks) + '))')
    out += [',\n'.join(blocks), ') }', '']
    return '\n'.join(out)


def kotlin_dest_and_package():
    """Locate the generated language-content file; its directory and package are the
    rebrand-proof anchor for where Kotlin course content must live."""
    matches = glob.glob(str(ROOT / 'apps/android/app/src/main/java/*/*/*/core/Languages.kt'))
    if not matches:
        raise SystemExit('Generated Languages.kt not found under apps/android/app/src/main/java.')
    path = pathlib.Path(matches[0])
    package = path.read_text().splitlines()
    package_line = next((line for line in package if line.startswith('package ')), None)
    if not package_line:
        raise SystemExit(f'No package declaration found in {path}.')
    return path.parent / 'CoursesData.kt', package_line


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='fail if the generated files are stale')
    args = parser.parse_args()

    files = sorted(COURSES_DIR.glob('*.json'))
    if not files:
        raise SystemExit(f'No course JSON files found in {COURSES_DIR}.')
    known = language_ids()
    seen = set()
    courses = []
    for path in files:
        course = json.loads(path.read_text())
        validate(course, known, seen)
        courses.append(course)

    swift_path = SWIFT_DEST
    kotlin_path, package_line = kotlin_dest_and_package()
    generated = {swift_path: emit_swift(courses), kotlin_path: emit_kotlin(courses, package_line)}

    if args.check:
        stale = [str(p.relative_to(ROOT)) for p, text in generated.items()
                 if not p.exists() or p.read_text() != text]
        if stale:
            print('Course content differs. Run python3 scripts/export_courses.py')
            print('Stale: ' + ', '.join(stale))
            return 1
        print('Course content in sync.')
        return 0
    for path, text in generated.items():
        path.write_text(text)
        print(f'Wrote {path.relative_to(ROOT)}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
