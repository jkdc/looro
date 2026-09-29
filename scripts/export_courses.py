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
SKILLS_PATH = ROOT / 'shared' / 'skills' / 'skill-catalog.json'
CANDO_PATH = ROOT / 'shared' / 'skills' / 'cando.json'
SWIFT_DEST = ROOT / 'apps/ios/Core/CoursesData.swift'
SKILLS_SWIFT_DEST = ROOT / 'apps/ios/Core/SkillsData.swift'

REQUIRED_COURSE = ('id', 'languageID', 'title', 'subtitle', 'symbol', 'description')
REQUIRED_UNIT = ('id', 'title', 'subtitle', 'level')
REQUIRED_QUESTION = ('prompt', 'expected', 'focus', 'watch')
REQUIRED_EXERCISE = ('id', 'kind', 'prompt', 'explain')
REQUIRED_THEME = ('id', 'title', 'subtitle', 'symbol', 'category', 'situation', 'colorIndex')
EXERCISE_KINDS = ('choice', 'gap', 'order', 'written')
SCHEMA_VERSION = 2
CEFR_LEVELS = ('A0', 'A1', 'A2', 'B1', 'B2', 'C1')
LESSON_SKILL_FIELDS = ('skills', 'prerequisites', 'exposureSkills')


def skill_catalog():
    """(skill ids, canDo ids) from the shared pedagogical catalogs."""
    catalog = json.loads(SKILLS_PATH.read_text())
    skills = {s['id']: s for s in catalog['skills']}
    competences = {c['id'] for c in json.loads(CANDO_PATH.read_text())['competences']}
    for skill in skills.values():
        for prerequisite in skill.get('prerequisites', []):
            if prerequisite not in skills:
                raise SystemExit(f'skill-catalog: {skill["id"]} references unknown prerequisite {prerequisite!r}.')
    return skills, competences


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


def validate(course, known_languages, seen_ids, skills, competences):
    def need(cond, message):
        if not cond:
            raise SystemExit(f'{course.get("id", "?")}: {message}')

    for key in REQUIRED_COURSE:
        need(isinstance(course.get(key), str) and course[key].strip(), f'missing course field {key}.')
    need(course['languageID'] in known_languages, f'unknown languageID {course["languageID"]!r}.')
    need(course.get('schemaVersion') == SCHEMA_VERSION,
         f'schemaVersion must be {SCHEMA_VERSION}, got {course.get("schemaVersion")!r}.')
    need(course['id'] not in seen_ids, f'duplicate course id {course["id"]!r}.')
    seen_ids.add(course['id'])
    need(isinstance(course.get('units'), list) and course['units'], 'course needs at least one unit.')
    for unit in course['units']:
        for key in REQUIRED_UNIT:
            need(isinstance(unit.get(key), str) and unit[key].strip(), f'missing unit field {key}.')
        need(unit['level'] in CEFR_LEVELS, f'unit {unit["id"]}: level {unit["level"]!r} is not one of {CEFR_LEVELS}.')
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
            for field in LESSON_SKILL_FIELDS:
                refs = lesson.get(field, [])
                need(isinstance(refs, list), f'lesson {lesson["id"]}: {field} must be a list.')
                for ref in refs:
                    need(ref in skills, f'lesson {lesson["id"]}: unknown {field} entry {ref!r}.')
            can_do = lesson.get('canDo', [])
            need(isinstance(can_do, list), f'lesson {lesson["id"]}: canDo must be a list.')
            for ref in can_do:
                need(ref in competences, f'lesson {lesson["id"]}: unknown canDo entry {ref!r}.')
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
            exercises = lesson.get('exercises', [])
            need(len(exercises) >= 3, f'lesson {lesson["id"]} needs at least 3 exercises.')
            for exercise in exercises:
                for key in REQUIRED_EXERCISE:
                    need(isinstance(exercise.get(key), str) and exercise[key].strip(),
                         f'lesson {lesson["id"]}: exercise missing {key}.')
                need(exercise['kind'] in EXERCISE_KINDS,
                     f'lesson {lesson["id"]}: unknown exercise kind {exercise["kind"]!r}.')
                need(isinstance(exercise['answers'], list) and exercise['answers'],
                     f'lesson {lesson["id"]}: exercise {exercise["id"]!r} needs answers.')
                options = exercise.get('options', [])
                if exercise['kind'] == 'choice':
                    need(len(options) >= 2 and all(isinstance(o, str) and o.strip() for o in options),
                         f'lesson {lesson["id"]}: choice exercise {exercise["id"]!r} needs 2+ options.')
                    need(all(a in options for a in exercise['answers']),
                         f'lesson {lesson["id"]}: choice answers must be listed options.')
                else:
                    need(not options, f'lesson {lesson["id"]}: {exercise["kind"]} exercises carry no options.')
                    if exercise['kind'] == 'gap':
                        need('___' in exercise['prompt'],
                             f'lesson {lesson["id"]}: gap exercise {exercise["id"]!r} needs a ___ blank.')
                    if exercise['kind'] == 'order':
                        need(len(exercise['answers']) == 1 and len(exercise['answers'][0].split()) >= 3,
                             f'lesson {lesson["id"]}: order exercise {exercise["id"]!r} needs one 3+ word sentence.')


def object_name(raw):
    """en-foundations-u1 -> EnFoundationsU1 — a stable, unique namespace per unit."""
    return ''.join(part[:1].upper() + part[1:] for part in re.split(r'[-_]', raw))

def skill_args(lesson, field, swift):
    values = lesson.get(field, [])
    if not values:
        return ''
    if swift:
        return ', ' + field + ': [' + ', '.join(q(v) for v in values) + ']'
    return ', ' + field + ' = listOf(' + ', '.join(q(v) for v in values) + ')'


def lesson_skill_suffix(lesson, swift):
    return ''.join(skill_args(lesson, field, swift)
                   for field in ('skills', 'canDo', 'prerequisites', 'exposureSkills'))


def emit_swift(courses):
    """One namespace per unit, mirroring the Kotlin split: `static let` initialises
    lazily, so no single initialiser ever carries the whole catalogue."""
    out = ['// Generated by scripts/export_courses.py; edit shared/courses/*.json instead.', '',
           'public enum CoursesData {']
    units_by_course = []
    for course in courses:
        unit_refs = []
        for unit in course['units']:
            obj = object_name(unit['id'])
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
                exercises = ',\n                            '.join(
                    f'Exercise(id: {q(e["id"])}, kind: {q(e["kind"])}, prompt: {q(e["prompt"])}, '
                    f'options: [{", ".join(q(o) for o in e.get("options", []))}], '
                    f'answers: [{", ".join(q(a) for a in e["answers"])}], explain: {q(e["explain"])})'
                    for e in lesson['exercises'])
                lesson_blocks.append(
                    '            Lesson(id: ' + q(lesson['id']) + ', title: ' + q(lesson['title'])
                    + ', goal: ' + q(lesson['goal']) + ',\n'
                    '                intro: LessonIntro(explanation: [\n                            '
                    + explanation + '], examples: [\n                            ' + examples + ']),\n'
                    '                expressions: [\n                            ' + expressions + '],\n'
                    '                situation: ' + q(lesson['situation']) + ',\n'
                    '                practice: LessonPractice(questions: [\n                                '
                    + questions + ']),\n'
                    '                exercises: [\n                            ' + exercises + ']'
                    + lesson_skill_suffix(lesson, swift=True) + ')')
            out += ['public enum ' + obj + ' {',
                    '    public static let unit: CourseUnit = CourseUnit(id: ' + q(unit['id'])
                    + ', title: ' + q(unit['title']) + ', subtitle: ' + q(unit['subtitle'])
                    + ', level: ' + q(unit['level']) + ', themes: [\n                    ' + theme_args
                    + '], lessons: [\n' + ',\n'.join(lesson_blocks) + '])',
                    '}', '']
            unit_refs.append(obj + '.unit')
        units_by_course.append((course, unit_refs))
    out.append('    public static let all: [Course] = [')
    for course, unit_refs in units_by_course:
        out.append('        Course(id: ' + q(course['id']) + ', languageID: ' + q(course['languageID'])
                   + ', title: ' + q(course['title']) + ', subtitle: ' + q(course['subtitle'])
                   + ', symbol: ' + q(course['symbol']) + ', description: ' + q(course['description'])
                   + ', units: [\n' + ',\n'.join(unit_refs) + '\n        ]),')
    out += ['    ]', '}', '']
    return '\n'.join(out)

def emit_kotlin(courses, package_line):
    """One object per unit: each gets its own small <clinit>, so the generated
    data can grow past the JVM's 64 KB per-method limit (hit at 63 lessons)."""
    out = ['// Generated by scripts/export_courses.py; edit shared/courses/*.json instead.', '',
           package_line, '']
    for course in courses:
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
                exercises = ', '.join(
                    f'Exercise(id = {q(e["id"])}, kind = {q(e["kind"])}, prompt = {q(e["prompt"])}, '
                    f'options = listOf({", ".join(q(o) for o in e.get("options", []))}), '
                    f'answers = listOf({", ".join(q(a) for a in e["answers"])}), explain = {q(e["explain"])})'
                    for e in lesson['exercises'])
                lesson_blocks.append(
                    '    Lesson(id = ' + q(lesson['id']) + ', title = ' + q(lesson['title'])
                    + ', goal = ' + q(lesson['goal']) + ', intro = LessonIntro(explanation = listOf('
                    + explanation + '), examples = listOf(' + examples + ')), expressions = listOf('
                    + expressions + '), situation = ' + q(lesson['situation'])
                    + ', practice = LessonPractice(questions = listOf(' + questions + ')), exercises = listOf('
                    + exercises + ')' + lesson_skill_suffix(lesson, swift=False) + ')')
            out += ['object ' + object_name(unit['id']) + ' {',
                    '    val unit = CourseUnit(id = ' + q(unit['id']) + ', title = ' + q(unit['title'])
                    + ', subtitle = ' + q(unit['subtitle']) + ', level = ' + q(unit['level'])
                    + ', themes = listOf(' + theme_args
                    + '), lessons = listOf(' + ',\n'.join(lesson_blocks) + '))',
                    '}', '']
    out += ['object CoursesData { val all = listOf(']
    for course in courses:
        unit_refs = ', '.join(object_name(u['id']) + '.unit' for u in course['units'])
        out.append('    Course(id = ' + q(course['id']) + ', languageID = ' + q(course['languageID'])
                   + ', title = ' + q(course['title']) + ', subtitle = ' + q(course['subtitle'])
                   + ', symbol = ' + q(course['symbol']) + ', description = ' + q(course['description'])
                   + ', units = listOf(' + unit_refs + ')),')
    out += [') }', '']
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
    return path.parent / 'CoursesData.kt', path.parent / 'SkillsData.kt', package_line


def emit_skills(skills, swift):
    """The runtime slice of the pedagogical catalog: id → category and the
    prerequisite graph, so MasteryEngine and the recommender never re-parse JSON."""
    if swift:
        lines = ['// Generated by scripts/export_courses.py; edit shared/skills/*.json instead.', '',
                 'public enum SkillsData {',
                 '    public static let categories: [String: String] = [']
        lines += [f'        {q(skill_id)}: {q(skill["category"])},' for skill_id, skill in sorted(skills.items())]
        lines += ['    ]', '    public static let prerequisites: [String: [String]] = [']
        lines += [f'        {q(skill_id)}: [{", ".join(q(p) for p in skill.get("prerequisites", []))}],'
                  for skill_id, skill in sorted(skills.items())]
        lines += ['    ]', '}', '']
        return '\n'.join(lines)
    lines = ['// Generated by scripts/export_courses.py; edit shared/skills/*.json instead.', '',
             'object SkillsData {',
             '    val categories = mapOf(']
    lines += [f'        {q(skill_id)} to {q(skill["category"])},' for skill_id, skill in sorted(skills.items())]
    lines += ['    )', '    val prerequisites = mapOf(']
    lines += [f'        {q(skill_id)} to listOf({", ".join(q(p) for p in skill.get("prerequisites", []))}),'
              for skill_id, skill in sorted(skills.items())]
    lines += ['    )', '}', '']
    return '\n'.join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='fail if the generated files are stale')
    args = parser.parse_args()

    files = sorted(COURSES_DIR.glob('*.json'))
    if not files:
        raise SystemExit(f'No course JSON files found in {COURSES_DIR}.')
    known = language_ids()
    skills, competences = skill_catalog()
    seen = set()
    courses = []
    for path in files:
        course = json.loads(path.read_text())
        validate(course, known, seen, skills, competences)
        courses.append(course)

    swift_path = SWIFT_DEST
    kotlin_path, skills_kotlin_path, package_line = kotlin_dest_and_package()
    generated = {swift_path: emit_swift(courses), kotlin_path: emit_kotlin(courses, package_line),
                 SKILLS_SWIFT_DEST: emit_skills(skills, swift=True),
                 skills_kotlin_path: emit_skills(skills, swift=False)}

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
