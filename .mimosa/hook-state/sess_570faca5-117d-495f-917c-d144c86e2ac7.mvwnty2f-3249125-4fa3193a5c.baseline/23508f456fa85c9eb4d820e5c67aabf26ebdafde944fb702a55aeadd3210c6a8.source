#!/usr/bin/env python3
"""Compose per-level level-verification test pools (level-tests change) from
already-authored content: lesson exercises, checkpoint questions and
listening texts. Emits `shared/leveltests/<lang>-leveltests.json`; the
exporter (`export_leveltests.py`) re-validates and generates `LevelTestsData.kt`.

Sections (the weekly test takes recognition 12, reading 2 passages × 3
questions, listening 2 passages × 2 questions, writing 3 ≈ 25 items):
- recognition: lesson exercises (choice/gap/order; they drill the unit's
  structures) + checkpoint recognition questions. No skill field exists on
  lesson exercises, so grammar/vocab are not split — the result reports the
  single recognition score.
- reading and listening draw on the same harvested texts, in two roles:
  reading shows the text VISUALLY with 3 questions; listening speaks it with
  2 questions. Within one composed test the two roles always use disjoint
  texts (the composer enforces it), so the listening is never pre-read.
- writing: written exercises (exact-match production).
Lesson exercises carry their unit provenance for the reinforce list; levels
missing per-section minimums are dropped (the app never shows an entry it
cannot serve). Reading/listening/writing floors default to 50: exact-match
writing and device TTS are harsher than recognition.
"""
import glob
import json
import pathlib

ROOT = pathlib.Path(__file__).resolve().parents[1]
LANGUAGES = ('en', 'es')
BANDS = ('A1', 'A2', 'B1', 'B2', 'C1')
SCHEMA_VERSION = 1

MINIMUMS = {'recognition': 36, 'writing': 9, 'passages': 6}
READING_PER_PASSAGE = 3
LISTENING_PER_PASSAGE = 2
READING_PER_PASSAGE = 3
LISTENING_PER_PASSAGE = 2
DEFAULT_THRESHOLDS = {'overall': 70, 'recognition': 60, 'reading': 50, 'listening': 50, 'writing': 50}


def harvest(lang):
    exercises = {}   # level → list of lesson exercises (with unit provenance)
    for path in sorted(glob.glob(str(ROOT / f'shared/courses/{lang}-*.json'))):
        course = json.loads(pathlib.Path(path).read_text())
        for unit in course.get('units', []):
            for lesson in unit.get('lessons', []):
                for exercise in lesson.get('exercises', []):
                    exercises.setdefault(unit['level'], []).append({
                        'id': exercise['id'], 'kind': exercise['kind'],
                        'prompt': exercise['prompt'], 'options': exercise.get('options'),
                        'answers': exercise['answers'], 'explain': exercise.get('explain', ''),
                        'unitID': unit['id'],
                    })
    texts = {}       # level → listening texts
    for path in sorted(glob.glob(str(ROOT / f'shared/listening/{lang}-*.json'))):
        for text in json.loads(pathlib.Path(path).read_text()).get('texts', []):
            if len(text.get('questions', [])) >= LISTENING_PER_PASSAGE:
                texts.setdefault(text['level'], []).append(text)
    checkpoints = {}  # level → checkpoint recognition questions (written has its own harvest)
    thresholds = {}   # (lang, level) → thresholds map
    paths = sorted(glob.glob(str(ROOT / f'shared/assessments/{lang}-checkpoints.json'))) + \
        sorted(glob.glob(str(ROOT / 'shared/assessments/multilang-checkpoints.json')))
    for path in paths:
        for checkpoint in json.loads(pathlib.Path(path).read_text())['checkpoints']:
            if checkpoint.get('languageID') != lang:
                continue
            checkpoints.setdefault(checkpoint['level'], []).extend(
                q for q in checkpoint['questions'] if q['kind'] != 'written')
            if isinstance(checkpoint.get('thresholds'), dict):
                thresholds[f"{lang}-{checkpoint['level']}"] = checkpoint['thresholds']
    return exercises, texts, checkpoints, thresholds


def build_pool(level, exercises, texts, checkpoints):
    recognition, writing, seen = [], [], set()
    for source in (exercises.get(level, []), checkpoints.get(level, [])):
        for item in source:
            key = (item.get('prompt'), tuple(item.get('answers', [])))
            if key in seen:
                continue
            seen.add(key)
            entry = {'id': item['id'], 'kind': item['kind'], 'prompt': item['prompt'],
                     'answers': item['answers'], 'explain': item.get('explain', '')}
            if item.get('options') is not None:
                entry['options'] = item['options']
            if 'unitID' in item:
                entry['unitID'] = item['unitID']
            if item['kind'] == 'written':
                writing.append(entry)
            else:
                recognition.append(entry)
    # Every text carries both roles; the weekly composer picks disjoint texts
    # for reading (visual) and listening (spoken).
    passages = []
    for text in texts.get(level, []):
        reading_items = [{'id': q['id'] + '#r', 'section': 'reading', 'kind': q['kind'], 'passageId': text['id'],
                          'prompt': q['prompt'], 'options': q.get('options'), 'answers': q['answers'],
                          'explain': q.get('explain', ''), 'unitID': text.get('unitID')}
                         for q in text['questions'][:READING_PER_PASSAGE]]
        listening_items = [{'id': q['id'] + '#l', 'section': 'listening', 'kind': q['kind'], 'passageId': text['id'],
                            'speech': text['text'], 'prompt': q['prompt'], 'options': q.get('options'),
                            'answers': q['answers'], 'explain': q.get('explain', ''), 'unitID': text.get('unitID')}
                           for q in text['questions'][:LISTENING_PER_PASSAGE]]
        if reading_items or listening_items:
            passages.append({'id': text['id'], 'title': text.get('title', ''), 'text': text['text'],
                             'reading': reading_items, 'listening': listening_items})
    return {'recognition': recognition, 'writing': writing, 'passages': passages}


def main():
    report = {}
    for lang in LANGUAGES:
        exercises, texts, checkpoints, thresholds = harvest(lang)
        levels_out = []
        for level in BANDS:
            pool = build_pool(level, exercises, texts, checkpoints)
            reading_ready = sum(1 for p in pool['passages'] if len(p['reading']) >= READING_PER_PASSAGE)
            listening_ready = sum(1 for p in pool['passages'] if len(p['listening']) >= LISTENING_PER_PASSAGE)
            ok = (len(pool['recognition']) >= MINIMUMS['recognition'] and
                  len(pool['writing']) >= MINIMUMS['writing'] and
                  len(pool['passages']) >= MINIMUMS['passages'] and
                  reading_ready >= 2 and listening_ready >= 2)
            stats = {k: len(v) for k, v in pool.items()}
            report[f'{lang}-{level}'] = ('OK   ' if ok else 'drop ') + str(stats)
            if not ok:
                continue
            level_thresholds = dict(DEFAULT_THRESHOLDS)
            level_thresholds.update(thresholds.get(f'{lang}-{level}', {}))
            items = [dict(entry, section='recognition') for entry in pool['recognition']]
            items += [dict(entry, section='writing') for entry in pool['writing']]
            for passage in pool['passages']:
                items += passage['reading'] + passage['listening']
            levels_out.append({
                'level': level, 'version': 1, 'thresholds': level_thresholds,
                'passages': [{'id': p['id'], 'title': p.get('title', ''), 'text': p['text']}
                             for p in pool['passages']],
                'pool': items,
            })
        out = ROOT / 'shared/leveltests' / f'{lang}-leveltests.json'
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps({'schemaVersion': SCHEMA_VERSION, 'languageID': lang, 'levels': levels_out},
                                  ensure_ascii=False, indent=1) + '\n')
        print(f'{lang}: {len(levels_out)} levels qualify → {out.name}')
    for key in sorted(report):
        print(f'  {key}: {report[key]}')


if __name__ == '__main__':
    main()
