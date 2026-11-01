#!/usr/bin/env python3
"""Generate the per-platform reading content from the canonical JSON in
`shared/readings/`.

Readings are the Reading skill's content: short authored texts that stay
VISIBLE while the reader answers comprehension questions — the written
sibling of the listenings, and the same bargain: authored once as JSON and
emitted into BOTH cores (`ReadingData.swift` / `ReadingData.kt`), with
questions reusing the Exercise shape so `ExerciseEngine` grades them
unchanged. Run `--check` in CI or before committing to detect drift.
"""
import argparse
import glob
import json
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
READINGS_DIR = ROOT / 'shared' / 'readings'
SKILLS_PATH = ROOT / 'shared' / 'skills' / 'skill-catalog.json'
SWIFT_DEST = ROOT / 'apps/ios/Core/ReadingData.swift'

REQUIRED_TEXT = ('id', 'languageID', 'title', 'level', 'topic', 'text')
REQUIRED_QUESTION = ('id', 'kind', 'prompt', 'explain')
QUESTION_KINDS = ('choice', 'written')
MINIMUM_QUESTIONS = 4
SCHEMA_VERSION = 1
TEXT_SKILL_FIELDS = ('skills', 'exposureSkills')
CEFR_LEVELS = ('A0', 'A1', 'A2', 'B1', 'B2', 'C1')


def q(text):
    """A string literal valid in both Swift and Kotlin (both accept \\" and \\\\;
    non-ASCII stays literal so the files stay readable)."""
    out = json.dumps(text, ensure_ascii=False)
    return out.replace('$', r'\$')

def q_swift(text):
    """Swift string literal: $ is not special, so no escaping needed."""
    return json.dumps(text, ensure_ascii=False)


def language_ids():
    registry = (ROOT / 'apps/ios/Core/Languages/LanguageModule.swift').read_text()
    import re
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


def course_units():
    units = {}
    for path in (ROOT / 'shared/courses').glob('*.json'):
        course = json.loads(path.read_text())
        for unit in course.get('units', []):
            units[unit['id']] = (course['languageID'], unit['level'])
    return units


def skill_ids():
    return {s['id'] for s in json.loads(SKILLS_PATH.read_text())['skills']}


def validate(text, known_languages, seen_ids, units, skills):
    def need(cond, message):
        if not cond:
            raise SystemExit(f'{text.get("id", "?")}: {message}')

    for key in REQUIRED_TEXT:
        need(isinstance(text.get(key), str) and text[key].strip(), f'missing field {key}.')
    need(text['id'] not in seen_ids, f'duplicate id {text["id"]!r}.')
    seen_ids.add(text['id'])
    need(text['languageID'] in known_languages, f'unknown languageID {text["languageID"]!r}.')
    need(text['level'] in CEFR_LEVELS, f'level {text["level"]!r} is not one of {CEFR_LEVELS}.')
    for field in TEXT_SKILL_FIELDS:
        refs = text.get(field, [])
        need(isinstance(refs, list), f'{field} must be a list.')
        for ref in refs:
            need(ref in skills, f'unknown {field} entry {ref!r}.')
    if 'unitID' in text:
        unit = units.get(text['unitID'])
        need(unit is not None, f'unknown unitID {text["unitID"]!r}.')
        need(unit[0] == text['languageID'],
             f'unitID {text["unitID"]!r} belongs to {unit[0]}, not {text["languageID"]}.')
        need(unit[1] == text['level'],
             f'text is {text["level"]} but unit {text["unitID"]!r} is {unit[1]}.')
    words = text['text'].split()
    need(40 <= len(words) <= 220, f'text needs 40..220 words, has {len(words)}.')
    questions = text.get('questions', [])
    need(len(questions) >= MINIMUM_QUESTIONS, f'needs at least {MINIMUM_QUESTIONS} questions.')
    for question in questions:
        for key in REQUIRED_QUESTION:
            need(isinstance(question.get(key), str) and question[key].strip(),
                 f'question {question.get("id", "?")!r}: missing {key}.')
        need(question['id'] not in seen_ids, f'duplicate question id {question["id"]!r}.')
        seen_ids.add(question['id'])
        need(question['kind'] in QUESTION_KINDS,
             f'question {question["id"]!r}: kind must be one of {QUESTION_KINDS}.')
        need(isinstance(question['answers'], list) and question['answers'],
             f'question {question["id"]!r} needs answers.')
        options = question.get('options', [])
        if question['kind'] == 'choice':
            need(len(options) >= 2 and all(isinstance(o, str) and o.strip() for o in options),
                 f'question {question["id"]!r}: choice needs 2+ options.')
            need(all(a in options for a in question['answers']),
                 f'question {question["id"]!r}: choice answers must be listed options.')
        else:
            need(not options, f'question {question["id"]!r}: written questions carry no options.')


def question_literal_swift(question):
    return (f'Exercise(id: {q(question["id"])}, kind: {q(question["kind"])}, prompt: {q(question["prompt"])}, '
            f'options: [{", ".join(q(o) for o in question.get("options", []))}], '
            f'answers: [{", ".join(q(a) for a in question["answers"])}], explain: {q(question["explain"])})')


def question_literal_kotlin(question):
    return (f'Exercise(id = {q(question["id"])}, kind = {q(question["kind"])}, prompt = {q(question["prompt"])}, '
            f'options = listOf({", ".join(q(o) for o in question.get("options", []))}), '
            f'answers = listOf({", ".join(q(a) for a in question["answers"])}), explain = {q(question["explain"])})')


def emit_swift(texts):
    blocks = []
    for text in texts:
        questions = ',\n                            '.join(question_literal_swift(qq) for qq in text['questions'])
        unit = ', unitID: ' + q_swift(text['unitID']) if 'unitID' in text else ''
        suffix = ''.join(', ' + field + ': [' + ', '.join(q_swift(v) for v in text.get(field, [])) + ']'
                         for field in TEXT_SKILL_FIELDS if text.get(field))
        blocks.append(
            '    ReadingText(id: ' + q_swift(text['id']) + ', languageID: ' + q_swift(text['languageID'])
            + ', title: ' + q_swift(text['title']) + ', level: ' + q_swift(text['level']) + ', topic: ' + q_swift(text['topic'])
            + unit + ',\n'
            '        text: ' + q_swift(text['text']) + ',\n'
            '        questions: [\n                            ' + questions + ']' + suffix + ')')
    return '\n'.join(['// Generated by scripts/export_readings.py; edit shared/readings/*.json instead.', '',
                      'public enum ReadingData {', '    public static let all: [ReadingText] = [',
                      ',\n'.join(blocks), '    ]', '}', ''])


def emit_kotlin(texts, package_line):
    # Per-text objects keep <clinit> under the JVM 64KB method limit.
    objects = []
    refs = []
    for i, text in enumerate(texts, 1):
        questions = ', '.join(question_literal_kotlin(qq) for qq in text['questions'])
        unit = ', unitID = ' + q(text['unitID']) if 'unitID' in text else ''
        suffix = ''.join(', ' + field + ' = listOf(' + ', '.join(q(v) for v in text.get(field, [])) + ')'
                         for field in TEXT_SKILL_FIELDS if text.get(field))
        name = 'ReadData%03d' % i
        objects.append('object ' + name + ' { val text = ReadingText(id = ' + q(text['id'])
                       + ', languageID = ' + q(text['languageID'])
                       + ', title = ' + q(text['title']) + ', level = ' + q(text['level']) + ', topic = ' + q(text['topic'])
                       + unit + ', text = ' + q(text['text']) + ', questions = listOf(' + questions + ')' + suffix + ') }')
        refs.append(name + '.text')
    return '\n'.join(['// Generated by scripts/export_readings.py; edit shared/readings/*.json instead.', '',
                      package_line, '', '\n\n'.join(objects), '',
                      'object ReadingData { val all get() = listOf(', ',\n'.join(refs), ') }', ''])


def kotlin_dest_and_package():
    matches = glob.glob(str(ROOT / 'apps/android/app/src/main/java/*/*/*/core/Languages.kt'))
    if not matches:
        raise SystemExit('Generated Languages.kt not found under apps/android/app/src/main/java.')
    path = pathlib.Path(matches[0])
    package_line = next((line for line in path.read_text().splitlines() if line.startswith('package ')), None)
    if not package_line:
        raise SystemExit(f'No package declaration found in {path}.')
    return path.parent / 'ReadingData.kt', package_line


def validate_root(name, root):
    if root.get('schemaVersion') != SCHEMA_VERSION:
        raise SystemExit(f'{name}: schemaVersion must be {SCHEMA_VERSION}, got {root.get("schemaVersion")!r}.')
    entries = root.get('readings', root if isinstance(root, list) else [])
    if not entries:
        raise SystemExit(f'{name}: no readings.')
    return entries


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='fail if the generated files are stale')
    args = parser.parse_args()

    files = sorted(READINGS_DIR.glob('*.json'))
    if not files:
        raise SystemExit(f'No reading JSON files found in {READINGS_DIR}.')
    known = language_ids()
    units = course_units()
    skills = skill_ids()
    seen = set()
    texts = []
    for path in files:
        root = json.loads(path.read_text())
        entries = validate_root(path.name, root)
        for entry in entries:
            validate(entry, known, seen, units, skills)
            texts.append(entry)

    swift_path = SWIFT_DEST
    kotlin_path, package_line = kotlin_dest_and_package()
    generated = {swift_path: emit_swift(texts), kotlin_path: emit_kotlin(texts, package_line)}

    if args.check:
        stale = [str(p.relative_to(ROOT)) for p, text in generated.items()
                 if not p.exists() or p.read_text() != text]
        if stale:
            print('Reading content differs. Run python3 scripts/export_readings.py')
            print('Stale: ' + ', '.join(stale))
            return 1
        print('Reading content in sync.')
        return 0
    for path, text in generated.items():
        path.write_text(text)
        print(f'Wrote {path.relative_to(ROOT)}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
