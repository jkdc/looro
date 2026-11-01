#!/usr/bin/env python3
"""Generate the per-platform checkpoint content from the canonical JSON in
`shared/assessments/`.

Checkpoints are the level gate: authored item banks that mix skills, graded
deterministically by `ExerciseEngine`, with per-category floors plus an
overall threshold. The ledger keeps them under the existing `assessment`
kind (itemID = checkpoint id), so no server catalog change is needed.
Emitted into BOTH cores (`CheckpointsData.swift` / `CheckpointsData.kt`).
Run `--check` in CI or before committing to detect drift.
"""
import argparse
import glob
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
CHECKPOINTS_DIR = ROOT / 'shared' / 'assessments'
SKILLS_PATH = ROOT / 'shared' / 'skills' / 'skill-catalog.json'
SWIFT_DEST = ROOT / 'apps/ios/Core/CheckpointsData.swift'

REQUIRED_CHECKPOINT = ('id', 'languageID', 'level', 'title')
REQUIRED_QUESTION = ('id', 'kind', 'prompt', 'explain', 'skill')
QUESTION_KINDS = ('choice', 'written', 'gap', 'order')
MINIMUM_QUESTIONS = 12
SCHEMA_VERSION = 1
CEFR_LEVELS = ('A0', 'A1', 'A2', 'B1', 'B2', 'C1')


def q(text):
    out = json.dumps(text, ensure_ascii=False)
    return out.replace('$', r'\$')

def q_swift(text):
    """Swift string literal: $ is not special, so no escaping needed."""
    return json.dumps(text, ensure_ascii=False)


def language_ids():
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


def skill_ids():
    return {s['id'] for s in json.loads(SKILLS_PATH.read_text())['skills']}


def validate(checkpoint, known_languages, seen_ids, skills):
    def need(cond, message):
        if not cond:
            raise SystemExit(f'{checkpoint.get("id", "?")}: {message}')

    for key in REQUIRED_CHECKPOINT:
        need(isinstance(checkpoint.get(key), str) and checkpoint[key].strip(), f'missing field {key}.')
    need(checkpoint['id'] not in seen_ids, f'duplicate id {checkpoint["id"]!r}.')
    seen_ids.add(checkpoint['id'])
    need(checkpoint['languageID'] in known_languages, f'unknown languageID {checkpoint["languageID"]!r}.')
    need(checkpoint['level'] in CEFR_LEVELS, f'level {checkpoint["level"]!r} is not one of {CEFR_LEVELS}.')
    thresholds = checkpoint.get('thresholds', {})
    need(isinstance(thresholds, dict) and thresholds, 'thresholds must be a non-empty object.')
    for category, floor in thresholds.items():
        need(isinstance(floor, int) and 0 <= floor <= 100, f'threshold {category} must be 0..100.')
    questions = checkpoint.get('questions', [])
    need(len(questions) >= MINIMUM_QUESTIONS, f'needs at least {MINIMUM_QUESTIONS} questions.')
    for question in questions:
        for key in REQUIRED_QUESTION:
            need(isinstance(question.get(key), str) and question[key].strip(),
                 f'question {question.get("id", "?")!r}: missing {key}.')
        need(question['id'] not in seen_ids, f'duplicate question id {question["id"]!r}.')
        seen_ids.add(question['id'])
        need(question['kind'] in QUESTION_KINDS,
             f'question {question["id"]!r}: kind must be one of {QUESTION_KINDS}.')
        need(question['skill'] in skills, f'question {question["id"]!r}: unknown skill {question["skill"]!r}.')
        need(isinstance(question['answers'], list) and question['answers'],
             f'question {question["id"]!r} needs answers.')
        options = question.get('options', [])
        if question['kind'] == 'choice':
            need(len(options) >= 2 and all(isinstance(o, str) and o.strip() for o in options),
                 f'question {question["id"]!r}: choice needs 2+ options.')
            need(all(a in options for a in question['answers']),
                 f'question {question["id"]!r}: choice answers must be listed options.')
        else:
            need(not options, f'question {question["id"]!r}: {question["kind"]} questions carry no options.')
            if question['kind'] == 'gap':
                need('___' in question['prompt'], f'question {question["id"]!r}: gap needs a ___ blank.')
            if question['kind'] == 'order':
                need(len(question['answers']) == 1 and len(question['answers'][0].split()) >= 3,
                     f'question {question["id"]!r}: order needs one 3+ word sentence.')


def question_literal_swift(question):
    return (f'CheckpointQuestion(exercise: Exercise(id: {q(question["id"])}, kind: {q(question["kind"])}, '
            f'prompt: {q(question["prompt"])}, options: [{", ".join(q(o) for o in question.get("options", []))}], '
            f'answers: [{", ".join(q(a) for a in question["answers"])}], explain: {q(question["explain"])}), '
            f'skill: {q(question["skill"])}),')


def question_literal_kotlin(question):
    return (f'CheckpointQuestion(exercise = Exercise(id = {q(question["id"])}, kind = {q(question["kind"])}, '
            f'prompt = {q(question["prompt"])}, options = listOf({", ".join(q(o) for o in question.get("options", []))}), '
            f'answers = listOf({", ".join(q(a) for a in question["answers"])}), explain = {q(question["explain"])}), '
            f'skill = {q(question["skill"])})')


def threshold_literal(checkpoint, swift):
    pairs = ', '.join(f'{q(k)} to {v}' for k, v in sorted(checkpoint['thresholds'].items()))
    return f'mapOf({pairs})' if not swift else f'[{pairs}]'


def emit_swift(checkpoints):
    blocks = []
    for checkpoint in checkpoints:
        questions = '\n                            '.join(question_literal_swift(qq) for qq in checkpoint['questions'])
        blocks.append(
            '    Checkpoint(id: ' + q_swift(checkpoint['id']) + ', languageID: ' + q_swift(checkpoint['languageID'])
            + ', level: ' + q_swift(checkpoint['level']) + ', title: ' + q_swift(checkpoint['title'])
            + ', thresholds: ' + threshold_literal(checkpoint, swift=True) + ',\n'
            '        questions: [\n                            ' + questions + '\n        ])')
    return '\n'.join(['// Generated by scripts/export_assessments.py; edit shared/assessments/*.json instead.', '',
                      'public enum CheckpointsData {', '    public static let all: [Checkpoint] = [',
                      ',\n'.join(blocks), '    ]', '}', ''])


def emit_kotlin(checkpoints, package_line):
    # Per-checkpoint objects keep <clinit> under the JVM 64KB method limit.
    objects = []
    refs = []
    for i, checkpoint in enumerate(checkpoints, 1):
        questions = ',\n            '.join(question_literal_kotlin(qq) for qq in checkpoint['questions'])
        name = 'CheckpointData%02d' % i
        objects.append('object ' + name + ' { val checkpoint = Checkpoint(id = ' + q(checkpoint['id'])
                       + ', languageID = ' + q(checkpoint['languageID'])
                       + ', level = ' + q(checkpoint['level']) + ', title = ' + q(checkpoint['title'])
                       + ', thresholds = ' + threshold_literal(checkpoint, swift=False) + ', questions = listOf(\n            '
                       + questions + ')) }')
        refs.append(name + '.checkpoint')
    return '\n'.join(['// Generated by scripts/export_assessments.py; edit shared/assessments/*.json instead.', '',
                      package_line, '', '\n\n'.join(objects), '',
                      'object CheckpointsData { val all get() = listOf(', ',\n'.join(refs), ') }', ''])


def kotlin_dest_and_package():
    matches = glob.glob(str(ROOT / 'apps/android/app/src/main/java/*/*/*/core/Languages.kt'))
    if not matches:
        raise SystemExit('Generated Languages.kt not found under apps/android/app/src/main/java.')
    path = pathlib.Path(matches[0])
    package_line = next((line for line in path.read_text().splitlines() if line.startswith('package ')), None)
    if not package_line:
        raise SystemExit(f'No package declaration found in {path}.')
    return path.parent / 'CheckpointsData.kt', package_line


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='fail if the generated files are stale')
    args = parser.parse_args()

    files = sorted(CHECKPOINTS_DIR.glob('*.json'))
    if not files:
        raise SystemExit(f'No checkpoint JSON files found in {CHECKPOINTS_DIR}.')
    known = language_ids()
    skills = skill_ids()
    seen = set()
    checkpoints = []
    for path in files:
        root = json.loads(path.read_text())
        if root.get('schemaVersion') != SCHEMA_VERSION:
            raise SystemExit(f'{path.name}: schemaVersion must be {SCHEMA_VERSION}, got {root.get("schemaVersion")!r}.')
        entries = root.get('checkpoints', root if isinstance(root, list) else [])
        if not entries:
            raise SystemExit(f'{path.name}: no checkpoints.')
        for entry in entries:
            validate(entry, known, seen, skills)
            checkpoints.append(entry)

    swift_path = SWIFT_DEST
    kotlin_path, package_line = kotlin_dest_and_package()
    generated = {swift_path: emit_swift(checkpoints), kotlin_path: emit_kotlin(checkpoints, package_line)}

    if args.check:
        stale = [str(p.relative_to(ROOT)) for p, text in generated.items()
                 if not p.exists() or p.read_text() != text]
        if stale:
            print('Checkpoint content differs. Run python3 scripts/export_assessments.py')
            print('Stale: ' + ', '.join(stale))
            return 1
        print('Checkpoint content in sync.')
        return 0
    for path, text in generated.items():
        path.write_text(text)
        print(f'Wrote {path.relative_to(ROOT)}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
