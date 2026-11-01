#!/usr/bin/env python3
"""Live content counts for Looro authoring sessions.

Prints the numbers that content tests pin (lesson/listening/reading totals
per level and language, unit/lesson totals, skill-tag coverage) so the
author can update test assertions without re-deriving them.

Usage: python3 content-report.py [--json]
Run from anywhere; paths resolve from this file's location.
"""
import argparse
import collections
import json
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[4]
SHARED = ROOT / 'shared'


def load(pattern, key):
    out = []
    for path in sorted(SHARED.glob(pattern)):
        data = json.loads(path.read_text())
        out.extend(data.get(key, []))
    return out


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--json', action='store_true', help='machine-readable output')
    args = parser.parse_args()

    courses = [json.loads(p.read_text()) for p in sorted(SHARED.glob('courses/*.json'))]
    listenings = load('listening/*.json', 'texts')
    readings = load('readings/*.json', 'readings')
    checkpoints = load('assessments/*-checkpoints.json', 'checkpoints')
    catalog = json.loads((SHARED / 'skills/skill-catalog.json').read_text())['skills']

    lines = []
    lines.append('== Courses ==')
    for course in courses:
        units = course['units']
        by_level = collections.Counter(u['level'] for u in units)
        lines.append(f"  {course['id']}: {len(units)} unidades, "
                     f"{sum(len(u['lessons']) for u in units)} lecciones, niveles {dict(by_level)}")

    lines.append('== Listenings por idioma/nivel ==')
    by_lang = collections.defaultdict(collections.Counter)
    for t in listenings:
        by_lang[t['languageID']][t['level']] += 1
    for lang in sorted(by_lang):
        lines.append(f"  {lang}: {dict(sorted(by_lang[lang].items()))} total {sum(by_lang[lang].values())}")

    lines.append('== Readings por idioma/nivel ==')
    by_lang_r = collections.defaultdict(collections.Counter)
    for r in readings:
        by_lang_r[r['languageID']][r['level']] += 1
    for lang in sorted(by_lang_r):
        lines.append(f"  {lang}: {dict(sorted(by_lang_r[lang].items()))} total {sum(by_lang_r[lang].values())}")

    lines.append('== Checkpoints ==')
    lines.append('  ' + ', '.join(c['id'] for c in checkpoints))

    en_content = {u['id'] for c in courses if c['languageID'] == 'en' for u in c['units']}
    taught = set()
    for course in courses:
        if course['languageID'] != 'en':
            continue
        for unit in course['units']:
            for lesson in unit['lessons']:
                taught.update(lesson.get('skills', []))
    for t in listenings:
        if t['languageID'] == 'en':
            taught.update(t.get('skills', []))
    for r in readings:
        if r['languageID'] == 'en':
            taught.update(r.get('skills', []))
    by_cat = collections.defaultdict(list)
    for skill in catalog:
        by_cat[skill['category']].append(skill['id'])
    lines.append('== Cobertura de skills (contenido en) ==')
    for cat in ['grammar', 'vocab', 'function', 'speaking', 'listening', 'reading', 'writing', 'pron']:
        ids = by_cat.get(cat, [])
        missing = [i for i in ids if i not in taught]
        lines.append(f"  {cat}: {len(ids) - len(missing)}/{len(ids)}"
                     + (f"  SIN contenido: {missing}" if missing else ''))

    if args.json:
        print(json.dumps({'raw': '\n'.join(lines)}, ensure_ascii=False))
    else:
        print('\n'.join(lines))
    return 0


if __name__ == '__main__':
    sys.exit(main())
