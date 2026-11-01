#!/usr/bin/env python3
"""Deep cross-language audit: replicates ExerciseEngine grading, checks ids,
references, checkpoint spreads, generated-data parity and language bleed."""
import collections
import glob
import json
import pathlib
import re
import unicodedata

ROOT = pathlib.Path(__file__).resolve().parents[1]
SHARED = ROOT / 'shared'
issues = collections.defaultdict(list)

def note(cat, msg):
    issues[cat].append(msg)

def norm(text):
    t = unicodedata.normalize('NFC', text).lower()
    t = re.sub(r'[.,!?;:¿¡"“”«»…()]', ' ', t)
    t = re.sub(r'\s+', ' ', t).strip()
    return t

def grades_correct(ex, attempt):
    """Mirror ExerciseEngine.isCorrect for gap/written/order/choice."""
    kind = ex['kind']
    if kind == 'choice':
        return attempt in ex.get('options', [])
    wanted = norm(attempt)
    if not wanted:
        return False
    if kind == 'order':
        return any(norm(a) == wanted for a in ex['answers'])
    # gap / written
    for a in ex['answers']:
        na = norm(a)
        if not na:
            continue
        if '<' in na:
            parts = re.split(r'<[^<>]+>', na)
            if all(p in wanted for p in parts if p):
                return True
        elif na == wanted:
            return True
    return False

def items():
    for f in sorted((SHARED / 'courses').glob('*.json')):
        c = json.loads(f.read_text())
        for u in c['units']:
            for l in u['lessons']:
                yield f.name, l

courses = {}
for f in sorted((SHARED / 'courses').glob('*.json')):
    courses[f.stem] = json.loads(f.read_text())
units = {u['id']: u for c in courses.values() for u in c['units']}
catalog = {s['id'] for s in json.loads((SHARED / 'skills/skill-catalog.json').read_text())['skills']}
cando = {c['id'] for c in json.loads((SHARED / 'skills/cando.json').read_text())['competences']}

# ---- 1. lessons: exercises + practice + references ----
lesson_ids = set()
exercise_ids = set()
for fname, l in items():
    lid = l['id']
    if lid in lesson_ids:
        note('DUP_LESSON', lid)
    lesson_ids.add(lid)
    if any(cd not in cando for cd in l.get('canDo', [])):
        note('BAD_CANDO', f"{lid}: {l['canDo']}")
    for sk in l.get('skills', []):
        if sk not in catalog:
            note('BAD_SKILL', f"{lid}: {sk}")
    for pr in l.get('prerequisites', []):
        if pr not in catalog:
            note('BAD_PREREQ', f"{lid}: {pr}")
    for q in l['practice']['questions']:
        first = q['expected'][0]
        probe = {'kind': 'written', 'answers': q['expected']}
        if not grades_correct(probe, first):
            note('PRACTICE_SELF_GRADE', f"{lid}: '{first}'")
    for e in l.get('exercises', []):
        if e['id'] in exercise_ids:
            note('DUP_EXERCISE', e['id'])
        exercise_ids.add(e['id'])
        if not grades_correct(e, e['answers'][0]):
            note('EXERCISE_SELF_GRADE', f"{e['id']}: '{e['answers'][0]}'")
        if e['kind'] == 'choice':
            for a in e['answers']:
                if a not in e.get('options', []):
                    note('CHOICE_ANSWER_NOT_OPTION', f"{e['id']}: '{a}'")

# ---- 2. listenings + readings ----
text_ids = set()
for folder, key, minq in (('listening', 'texts', 5), ('readings', 'readings', 4)):
    for f in sorted((SHARED / folder).glob('*.json')):
        c = json.loads(f.read_text())
        for t in c[key]:
            if t['id'] in text_ids:
                note('DUP_TEXT', t['id'])
            text_ids.add(t['id'])
            if t.get('unitID'):
                u = units.get(t['unitID'])
                if u is None:
                    note('TEXT_UNIT_MISSING', f"{t['id']}: {t['unitID']}")
                elif u['level'] != t['level']:
                    note('TEXT_UNIT_LEVEL', f"{t['id']}: {t['level']} vs {u['level']}")
            if t['languageID'] not in f.stem:
                note('TEXT_LANG_FILE', f"{t['id']} in {f.name}")
            for sk in t.get('skills', []):
                if sk not in catalog:
                    note('BAD_SKILL', f"{t['id']}: {sk}")
            for q in t['questions']:
                probe = dict(q)
                if not grades_correct(probe, q['answers'][0]):
                    note('TEXT_Q_SELF_GRADE', f"{q['id']}: '{q['answers'][0]}'")
                if q['kind'] == 'choice':
                    for a in q['answers']:
                        if a not in q.get('options', []):
                            note('CHOICE_ANSWER_NOT_OPTION', f"{q['id']}: '{a}'")

# ---- 3. checkpoints ----
cp_qids = set()
for f in sorted((SHARED / 'assessments').glob('*.json')):
    c = json.loads(f.read_text())
    for cp in c['checkpoints']:
        by_cat = collections.Counter()
        for q in cp['questions']:
            if q['id'] in cp_qids:
                note('DUP_CP_QUESTION', q['id'])
            cp_qids.add(q['id'])
            if q['skill'] not in catalog:
                note('BAD_SKILL', f"{q['id']}: {q['skill']}")
            by_cat[q['skill'].split(':')[0]] += 1
            probe = dict(q)
            if not grades_correct(probe, q['answers'][0]):
                note('CP_Q_SELF_GRADE', f"{q['id']}: '{q['answers'][0]}'")
        for cat, n in by_cat.items():
            if n < 2:
                note('CP_THIN_CATEGORY', f"{cp['id']}: {cat}={n}")

# ---- 4. generated Kotlin/Swift parity (counts per id) ----
for gen_kt, gen_sw, pat in (
        ('apps/android/app/src/main/java/com/jkdc/app/core/ListeningData.kt', 'apps/ios/Core/ListeningData.swift', r'id = "([^"]+)"'),
        ('apps/android/app/src/main/java/com/jkdc/app/core/ReadingData.kt', 'apps/ios/Core/ReadingData.swift', r'id = "([^"]+)"')):
    kt = pathlib.Path(ROOT / gen_kt).read_text()
    sw = pathlib.Path(ROOT / gen_sw).read_text()
    kt_ids = set(re.findall(pat, kt))
    sw_ids = set(re.findall(r'id: "([^"]+)"', sw))
    if kt_ids != sw_ids:
        note('GEN_PARITY', f"{gen_kt}: kt-swift diff {len(kt_ids ^ sw_ids)} ids")

# ---- 5. language bleed heuristic ----
STOP = {
    'es': {'que', 'de', 'la', 'el', 'en', 'para', 'con', 'una', 'por'},
    'fr': {'le', 'la', 'les', 'des', 'est', 'et', 'une', 'dans', 'pour'},
    'it': {'che', 'di', 'il', 'non', 'per', 'con', 'una', 'sono', 'della'},
    'de': {'der', 'die', 'das', 'und', 'ist', 'nicht', 'ein', 'eine', 'mit'},
    'pt': {'que', 'de', 'não', 'com', 'para', 'uma', 'uma', 'mais', 'como'},
}
for f in sorted((SHARED / 'listening').glob('*.json')):
    lang = f.stem.split('-')[0]
    for t in json.loads(f.read_text())['texts']:
        words = set(norm(t['text']).split())
        scores = {l: len(words & s) for l, s in STOP.items()}
        best = max(scores, key=scores.get)
        if scores[best] >= 3 and best != lang and scores[best] > scores[lang] * 2:
            note('LANG_BLEED', f"{t['id']} ({lang}): parece {best}")

total = sum(len(v) for v in issues.values())
for cat, msgs in sorted(issues.items()):
    print(f"== {cat}: {len(msgs)}")
    for m in msgs[:12]:
        print("  ", m)
    if len(msgs) > 12:
        print(f"   … y {len(msgs)-12} más")
print(f"\nTOTAL: {total} hallazgos")
print(f"Lecciones: {len(lesson_ids)} · Textos: {len(text_ids)} · Preguntas checkpoint: {len(cp_qids)}")
