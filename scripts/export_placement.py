#!/usr/bin/env python3
"""Generate the per-platform placement content from the canonical JSON in
`shared/placement/`.

The placement test is the free level test: an adaptive band ladder over
authored items (core grammar/vocab/reading), plus fixed listening and
writing blocks at the resulting band. Items reuse the authored Exercise
shapes, tagged with a CEFR `level` and a `mode` (core/listening/writing).
The ledger keeps results under the existing `assessment` kind with the
placement id as itemID — no server catalog change.

This change emits ANDROID only (`PlacementData.kt`); the Swift mirror is a
follow-up. The exporter is the validation gate: a new JSON means the
existing cross-platform checkers know nothing about it, so every structural
rule is enforced here (and `--check` detects drift).
Run `--check` in CI or before committing.
"""
import argparse
import glob
import json
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
PLACEMENT_DIR = ROOT / 'shared' / 'placement'
SKILLS_PATH = ROOT / 'shared' / 'skills' / 'skill-catalog.json'

REQUIRED_ITEM = ('id', 'level', 'mode', 'kind', 'skill', 'prompt', 'answers', 'explain')
ITEM_KINDS = ('choice', 'written', 'gap', 'order')
ITEM_MODES = ('core', 'listening', 'writing')
LISTEN_KINDS = ('choice',)
WRITE_KINDS = ('written', 'gap', 'order')
BANDS = ('A1', 'A2', 'B1', 'B2', 'C1')
MINIMUM_CORE = 6
MINIMUM_BLOCK = 3
SCHEMA_VERSION = 1


def q(text):
    return json.dumps(text, ensure_ascii=False).replace('$', r'\$')


def skill_ids():
    return {s['id'] for s in json.loads(SKILLS_PATH.read_text())['skills']}


def validate(bank, known_languages, skills):
    def need(cond, message):
        if not cond:
            raise SystemExit(f"{bank.get('languageID', '?')}: {message}")

    need(bank.get('schemaVersion') == SCHEMA_VERSION, 'schemaVersion must be 1.')
    need(bank.get('languageID') in known_languages, f"unknown languageID {bank.get('languageID')!r}.")
    version = bank.get('version')
    need(isinstance(version, int) and version >= 1, 'version must be a positive integer.')
    items = bank.get('items')
    need(isinstance(items, list) and items, 'items must be a non-empty list.')
    seen = set()
    counts = {}
    for item in items:
        lid = item.get('id')
        need(isinstance(lid, str) and lid.startswith(f"pl-{bank['languageID']}-"), f"id must start pl-{bank['languageID']}-: {lid!r}")
        need(lid not in seen, f"duplicate id {lid!r}.")
        seen.add(lid)
        for field in REQUIRED_ITEM:
            need(field in item, f"{lid}: missing field {field!r}.")
        level, mode, kind = item.get('level'), item.get('mode'), item.get('kind')
        need(level in BANDS, f"{lid}: level must be one of {BANDS}.")
        need(mode in ITEM_MODES, f"{lid}: mode must be one of {ITEM_MODES}.")
        need(kind in ITEM_KINDS, f"{lid}: kind must be one of {ITEM_KINDS}.")
        need(item.get('skill') in skills, f"{lid}: unknown skill {item.get('skill')!r}.")
        answers = item.get('answers')
        need(isinstance(answers, list) and answers and all(isinstance(a, str) and a.strip() for a in answers),
             f"{lid}: answers must be a non-empty list of non-empty strings.")
        if kind == 'choice':
            options = item.get('options')
            need(isinstance(options, list) and len(options) >= 3, f"{lid}: choice needs >=3 options.")
            need(len(set(options)) == len(options), f"{lid}: duplicated options.")
            need(all(a in options for a in answers), f"{lid}: every answer must be an option.")
        else:
            need('options' not in item, f"{lid}: non-choice items carry no options.")
        if mode == 'listening':
            need(kind in LISTEN_KINDS, f"{lid}: listening items are choice.")
            need(isinstance(item.get('speech'), str) and item['speech'].strip(), f"{lid}: listening needs a speech stimulus.")
        if mode == 'writing':
            need(kind in WRITE_KINDS, f"{lid}: writing items are written/gap/order.")
            need(len(answers) <= 4, f"{lid}: writing accepts at most 4 variants.")
        if mode == 'core':
            need(kind != 'written' or len(answers) <= 4, f"{lid}: core written accepts at most 4 variants.")
        counts[(level, mode)] = counts.get((level, mode), 0) + 1
    for band in BANDS:
        need(counts.get((band, 'core'), 0) >= MINIMUM_CORE,
             f"band {band}: needs >= {MINIMUM_CORE} core items.")
        for mode in ('listening', 'writing'):
            need(counts.get((band, mode), 0) >= MINIMUM_BLOCK,
                 f"band {band}: needs >= {MINIMUM_BLOCK} {mode} items.")
    return {'languageID': bank['languageID'], 'version': version, 'items': items}


def item_literal(item):
    options = f"listOf({', '.join(q(o) for o in item['options'])})" if 'options' in item else 'listOf()'
    speech = f", speech = {q(item['speech'])}" if 'speech' in item else ''
    return (f"        PlacementItem(id = {q(item['id'])}, level = {q(item['level'])}, mode = {q(item['mode'])},\n"
            f"            exercise = Exercise(id = {q(item['id'])}, kind = {q(item['kind'])}, prompt = {q(item['prompt'])},\n"
            f"                options = {options}, answers = listOf({', '.join(q(a) for a in item['answers'])}), explain = {q(item['explain'])}),\n"
            f"            skill = {q(item['skill'])}{speech})")


def emit_kotlin(banks, package_line):
    lines = [f"{package_line}\n",
             "/** Generated by scripts/export_placement.py from the shared placement",
             "    JSON — never edit by hand. One bank per language: the free CEFR",
             "    placement test (adaptive core ladder + fixed listening/writing",
             "    blocks; placement-test). */",
             "object PlacementData {",
             "    val all: List<PlacementBank> = listOf("]
    for bank in banks:
        lines.append(f"        PlacementBank(languageID = {q(bank['languageID'])}, version = {bank['version']}, items = listOf(")
        for item in bank['items']:
            lines.append(item_literal(item) + ',')
        lines.append('        )),')
    lines.append('    )')
    lines.append('}')
    return '\n'.join(lines) + '\n'


def kotlin_dest_and_package():
    matches = glob.glob(str(ROOT / 'apps/android/app/src/main/java/*/*/*/core/Languages.kt'))
    if not matches:
        raise SystemExit('Generated Languages.kt not found under apps/android/app/src/main/java.')
    text = pathlib.Path(matches[0]).read_text()
    package = next(line for line in text.split('\n') if line.startswith('package ')).replace('package ', '')
    return pathlib.Path(matches[0]).parent / 'PlacementData.kt', f'package {package}'


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--check', action='store_true', help='fail if the generated file would change')
    arguments = parser.parse_args()
    known_languages = {'en', 'es', 'fr', 'de', 'it', 'pt', 'nb', 'zh'}
    skills = skill_ids()
    paths = sorted(PLACEMENT_DIR.glob('*-placement.json'))
    if not paths:
        raise SystemExit(f"No *-placement.json files under {PLACEMENT_DIR}.")
    banks = [validate(json.loads(path.read_text()), known_languages, skills) for path in paths]
    destination, package_line = kotlin_dest_and_package()
    generated = emit_kotlin(banks, package_line)
    if arguments.check:
        if destination.exists() and destination.read_text() == generated:
            print('placement data up to date')
        else:
            raise SystemExit(f'{destination} is out of date; run scripts/export_placement.py.')
    else:
        destination.write_text(generated)
        print(f"wrote {destination} ({sum(len(b['items']) for b in banks)} items, {len(banks)} banks)")


if __name__ == '__main__':
    main()
