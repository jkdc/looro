#!/usr/bin/env python3
"""Content Health Dashboard: offline checks for common content issues.

Checks:
- Exercises whose answer repeats the prompt verbatim
- Exercises with only one distractor (choice with 2 options)
- Skills referenced but never taught
- Prerequisite chain inversions
- Checkpoint category imbalance
- Answers missing from exercises
"""
import collections
import json
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
SHARED = ROOT / 'shared'


def load_courses():
    return [json.loads(p.read_text()) for p in sorted(SHARED.glob('courses/*.json'))]


def load_listening():
    out = []
    for p in sorted(SHARED.glob('listening/*.json')):
        out.extend(json.loads(p.read_text()).get('texts', []))
    return out


def load_readings():
    out = []
    for p in sorted(SHARED.glob('readings/*.json')):
        out.extend(json.loads(p.read_text()).get('readings', []))
    return out


def load_catalog():
    return {s['id']: s for s in json.loads((SHARED / 'skills/skill-catalog.json').read_text())['skills']}


def check_exercises(courses, issues):
    for course in courses:
        for unit in course['units']:
            for lesson in unit['lessons']:
                for ex in lesson.get('exercises', []):
                    ex_id = ex.get('id', '?')
                    if ex.get('kind') == 'choice':
                        opts = ex.get('options', [])
                        if 0 < len(opts) < 3:
                            issues.append(f"WEAK_DISTRACTORS: {ex_id} has only {len(opts)} options")
                    for ans in ex.get('answers', []):
                        if ex.get('kind') == 'written' and len(str(ans)) > 5 and str(ans).lower() in ex.get('prompt', '').lower():
                            issues.append(f"ANSWER_IN_PROMPT: {ex_id} answer '{ans}' appears in prompt")
                    if len(ex.get('explain', '')) < 10:
                        issues.append(f"SHORT_EXPLAIN: {ex_id} explain is '{ex.get('explain', '')}'")
                    if 'answers' not in ex or not ex.get('answers'):
                        issues.append(f"NO_ANSWERS: {ex_id} has no answers")


def check_skill_coverage(courses, listenings, readings, catalog, issues):
    taught = set()
    for course in courses:
        for unit in course['units']:
            for lesson in unit['lessons']:
                taught.update(lesson.get('skills', []))
    for text in listenings:
        taught.update(text.get('skills', []))
    for text in readings:
        taught.update(text.get('skills', []))
    for skill_id in catalog:
        if skill_id not in taught:
            issues.append(f"UNTAUGHT_SKILL: {skill_id} is in the catalog but no content teaches it")


def check_prerequisite_chain(courses, catalog, issues):
    skill_levels = {}
    for course in courses:
        for unit in course['units']:
            for lesson in unit['lessons']:
                for skill in lesson.get('skills', []):
                    level = unit['level']
                    rank = _level_rank(level)
                    if skill not in skill_levels or rank < _level_rank(skill_levels[skill]):
                        skill_levels[skill] = level
    for skill_id, skill in catalog.items():
        for prereq in skill.get('prerequisites', []):
            if prereq in skill_levels and skill_id in skill_levels:
                if _level_rank(skill_levels[prereq]) > _level_rank(skill_levels[skill_id]):
                    issues.append(
                        f"PREREQ_INVERSION: {skill_id} ({skill_levels[skill_id]}) "
                        f"requires {prereq} ({skill_levels[prereq]}) which is taught LATER")


def _level_rank(level):
    return ['A0', 'A1', 'A2', 'B1', 'B2', 'C1'].index(level) if level in ['A0', 'A1', 'A2', 'B1', 'B2', 'C1'] else 99


def check_checkpoint_balance(checkpoints, issues):
    for cp in checkpoints:
        by_cat = collections.Counter()
        for q in cp['questions']:
            skill = q.get('skill', '')
            cat = skill.split(':')[0] if ':' in skill else skill
            by_cat[cat] += 1
        total = sum(by_cat.values())
        for cat, count in by_cat.items():
            if total > 10 and count / total < 0.1:
                issues.append(f"CHECKPOINT_IMBALANCE: {cp['id']} has only {count}/{total} {cat} questions")


# Parity targets per non-English language (docs/plan_completar_idiomas_2026-10-01.md):
# lessons per level, listenings per level, readings per level, checkpoint levels.
PARITY_LANGUAGES = ('es', 'fr', 'it', 'de', 'pt')
PARITY_LESSONS = {'A1': 40, 'A2': 18, 'B1': 14, 'B2': 5, 'C1': 5}
PARITY_LISTENINGS = {'A1': 15, 'A2': 12, 'B1': 7, 'B2': 7, 'C1': 3}
PARITY_READINGS = {'A1': 7, 'A2': 4, 'B1': 5, 'B2': 3, 'C1': 3}
PARITY_READINGS_FLOOR = {'A1': 6, 'A2': 4, 'B1': 4, 'B2': 3, 'C1': 2}
PARITY_CHECKPOINTS = ('A1', 'A2', 'B1', 'B2', 'C1')


def check_teaching_order(courses, issues):
    ranks = {'A0': 0, 'A1': 1, 'A2': 2, 'B1': 3, 'B2': 4, 'C1': 5}
    for course in courses:
        idx = [ranks.get(u['level'], 9) for u in course['units']]
        if idx != sorted(idx):
            issues.append(f"TEACHING_ORDER: {course['id']} units are not in level order")


def check_parity(courses, listenings, readings, checkpoints, issues):
    by_lang_units = collections.defaultdict(lambda: collections.defaultdict(int))
    for course in courses:
        base = course['id'].split('-')[0]
        if base not in PARITY_LANGUAGES:
            continue
        for unit in course['units']:
            by_lang_units[base][unit['level']] += len(unit['lessons'])
    by_lang_listen = collections.defaultdict(lambda: collections.defaultdict(int))
    for text in listenings:
        if text['languageID'] in PARITY_LANGUAGES:
            by_lang_listen[text['languageID']][text['level']] += 1
    by_lang_read = collections.defaultdict(lambda: collections.defaultdict(int))
    for text in readings:
        if text['languageID'] in PARITY_LANGUAGES:
            by_lang_read[text['languageID']][text['level']] += 1
    by_lang_cp = collections.defaultdict(set)
    for cp in checkpoints:
        if cp['languageID'] in PARITY_LANGUAGES:
            by_lang_cp[cp['languageID']].add(cp['level'])
    for lang in PARITY_LANGUAGES:
        for level, target in PARITY_LESSONS.items():
            have = by_lang_units[lang][level]
            if have < target:
                issues.append(f"PARITY_GAP: {lang} {level} lessons {have}/{target}")
        for level, target in PARITY_LISTENINGS.items():
            have = by_lang_listen[lang][level]
            if have < target:
                issues.append(f"PARITY_GAP: {lang} {level} listenings {have}/{target}")
        for level, target in PARITY_READINGS_FLOOR.items():
            have = by_lang_read[lang][level]
            if have < target:
                issues.append(f"PARITY_GAP: {lang} {level} readings {have}/{target}")
        for level in PARITY_CHECKPOINTS:
            if level not in by_lang_cp[lang]:
                issues.append(f"PARITY_GAP: {lang} checkpoint {level} missing")


def main():
    parity_only = '--parity' in sys.argv
    courses = load_courses()
    listenings = load_listening()
    readings = load_readings()
    catalog = load_catalog()
    checkpoints = []
    for p in sorted(SHARED.glob('assessments/*-checkpoints.json')):
        checkpoints.extend(json.loads(p.read_text()).get('checkpoints', []))

    issues = []
    if not parity_only:
        check_exercises(courses, issues)
        check_skill_coverage(courses, listenings, readings, catalog, issues)
        check_prerequisite_chain(courses, catalog, issues)
        check_checkpoint_balance(checkpoints, issues)
        check_teaching_order(courses, issues)
    check_parity(courses, listenings, readings, checkpoints, issues)

    if issues:
        print(f"Content Health: {len(issues)} issue(s) found\n")
        for issue in issues:
            print(f"  ⚠ {issue}")
    else:
        print("Content Health: no issues found ✓")

    print(f"\nCatalogue: {len(courses)} courses, {len(listenings)} listenings, "
          f"{len(readings)} readings, {len(checkpoints)} checkpoints, "
          f"{len(catalog)} skills, {len(load_cando())} Can Do competences")
    return 0


def load_cando():
    return {c['id'] for c in json.loads((SHARED / 'skills/cando.json').read_text())['competences']}


if __name__ == '__main__':
    sys.exit(main())
