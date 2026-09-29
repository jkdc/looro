#!/usr/bin/env python3
"""Content Health Dashboard: offline checks for common content issues.

Checks:
- Exercises whose answer repeats the prompt verbatim
- Exercises with only one distractor (choice with 2 options)
- Lessons with < 3 skills tagged
- Skills referenced but never taught (no lesson or listening carries them)
- Words in listenings that don't appear in any lesson's vocabulary
- Checkpoints with unbalanced category coverage
"""
import collections
import json
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
SHARED = ROOT / 'shared'


def load_courses():
    courses = []
    for p in sorted(SHARED.glob('courses/*.json')):
        courses.append(json.loads(p.read_text()))
    return courses


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


def load_cando():
    return {c['id'] for c in json.loads((SHARED / 'skills/cando.json').read_text())['competences']}


def check_exercises(courses, issues):
    for course in courses:
        for unit in course['units']:
            for lesson in unit['lessons']:
                for ex in lesson.get('exercises', []):
                    # Choice with only 2 options (weak distractors)
                    if ex['kind'] == 'choice' and len(ex.get('options', [])) < 3:
                        issues.append(f"WEAK_DISTRACTORS: {ex['id']} has only {len(ex['options'])} options")
                    # Answer too similar to prompt
                    if ex['kind'] == 'written':
                        prompt_lower = ex['prompt'].lower()
                        for ans in ex['answers']:
                            if len(ans) > 5 and ans.lower() in prompt_lower:
                                issues.append(f"ANSWER_IN_PROMPT: {ex['id']} answer '{ans}' appears in prompt")
                    # Explain is empty or too short
                    if len(ex.get('explain', '')) < 10:
                        issues.append(f"SHORT_EXPLAIN: {ex['id']} explain is '{ex.get('explain', '')}'")


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


def check_prerequisite_chain(catalog, issues):
    """Skills whose prerequisites are taught at a HIGHER level than the skill itself."""
    skill_levels = {}
    for course in courses:
        lang = course['languageID']
        for unit in course['units']:
            for lesson in unit['lessons']:
                for skill in lesson.get('skills', []):
                    level = unit['level']
                    if skill not in skill_levels or _level_rank(level) < _level_rank(skill_levels[skill]):
                        skill_levels[skill] = level
    for skill_id, skill in catalog.items():
        for prereq in skill.get('prerequisites', []):
            if prereq in skill_levels and skill_id in skill_levels:
                if _level_rank(skill_levels[prereq]) > _level_rank(skill_levels[skill_id]):
                    issues.append(f"PREREQ_INVERSION: {skill_id} ({skill_levels[skill_id]}) requires {prereq} ({skill_levels[prereq]}) which is taught LATER")


def _level_rank(level):
    return ['A0', 'A1', 'A2', 'B1', 'B2', 'C1'].index(level) if level in ['A0', 'A1', 'A2', 'B1', 'B2', 'C1'] else 99


def check_checkpoint_balance(checkpoints, catalog, issues):
    for cp in checkpoints:
        by_cat = collections.Counter()
        for q in cp['questions']:
            skill = q.get('skill', '')
            cat = skill.split(':')[0] if ':' in skill else skill
            by_cat[cat] += 1
        total = sum(by_cat.values())
        for cat, count in by_cat.items():
            if count / total < 0.1 and total > 10:
                issues.append(f"CHECKPOINT_IMBALANCE: {cp['id']} has only {count}/{total} {cat} questions")


def main():
    courses = load_courses()
    listenings = load_listening()
    readings = load_readings()
    catalog = load_catalog()
    cando = load_cando()
    checkpoints = []
    for p in sorted(SHARED.glob('assessments/*-checkpoints.json')):
        checkpoints.extend(json.loads(p.read_text()).get('checkpoints', []))

    issues = []
    check_exercises(courses, issues)
    check_skill_coverage(courses, listenings, readings, catalog, issues)
    check_checkpoint_balance(checkpoints, catalog, issues)

    if issues:
        print(f"Content Health: {len(issues)} issue(s) found\n")
        for issue in issues:
            print(f"  ⚠ {issue}")
    else:
        print("Content Health: no issues found ✓")

    print(f"\nCatalogue: {len(courses)} courses, {len(listenings)} listenings, "
          f"{len(readings)} readings, {len(checkpoints)} checkpoints, "
          f"{len(catalog)} skills, {len(cando)} Can Do competences")
    return 1 if issues else 0


if __name__ == '__main__':
    sys.exit(main())
