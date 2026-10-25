#!/usr/bin/env python3
"""Validate `shared/leveltests/<lang>-leveltests.json` (composed by
`build_leveltests.py`) and generate `LevelTestsData.kt` for Android.
`--check` fails when the generated file would drift. Run in CI
(contracts.yml). The weekly composition itself is the client engine's job
(`LevelTestEngine`); this file only validates the static pool."""
import argparse
import glob
import json
import pathlib

ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE_DIR = ROOT / 'shared/leveltests'
SECTIONS = ('recognition', 'reading', 'listening', 'writing')
BANDS = ('A1', 'A2', 'B1', 'B2', 'C1')
SCHEMA_VERSION = 1
MINIMUM = {'recognition': 36, 'writing': 9, 'passages': 6, 'reading_ready': 2, 'listening_ready': 2}
READING_PER_PASSAGE = 3
LISTENING_PER_PASSAGE = 2


def q(text):
    return json.dumps(text, ensure_ascii=False).replace('$', r'\$')


def validate(bank, skills=None):
    need = lambda cond, message: (_ for _ in ()).throw(SystemExit(f"{bank.get('languageID', '?')}: {message}")) if not cond else None
    need(bank.get('schemaVersion') == SCHEMA_VERSION, 'schemaVersion must be 1.')
    levels = bank.get('levels')
    need(isinstance(levels, list) and levels, 'levels must be a non-empty list.')
    seen_levels = set()
    for level in levels:
        lid = level.get('level')
        need(lid in BANDS, f'unknown level {lid!r}.')
        need(lid not in seen_levels, f'duplicate level {lid!r}.')
        seen_levels.add(lid)
        need(isinstance(level.get('version'), int) and level['version'] >= 1, f'{lid}: bad version.')
        thresholds = level.get('thresholds')
        need(isinstance(thresholds, dict) and isinstance(thresholds.get('overall'), int),
             f'{lid}: thresholds.overall required.')
        passages = {p['id']: p for p in level.get('passages', [])}
        need(len(passages) >= MINIMUM['passages'], f'{lid}: needs >= {MINIMUM["passages"]} passages.')
        pool = level.get('pool')
        need(isinstance(pool, list) and pool, f'{lid}: empty pool.')
        seen = set()
        counts = {}
        reading_ready = listening_ready = 0
        by_passage_reading, by_passage_listening = {}, {}
        for item in pool:
            item_id = item.get('id')
            need(item_id and item_id not in seen, f'{lid}: missing or duplicate item id {item_id!r}.')
            seen.add(item_id)
            section = item.get('section')
            need(section in SECTIONS, f'{item_id}: unknown section {section!r}.')
            counts[section] = counts.get(section, 0) + 1
            need(isinstance(item.get('answers'), list) and item['answers'], f'{item_id}: answers required.')
            if item.get('kind') == 'choice':
                need(item.get('options') and len(item['options']) >= 3, f'{item_id}: choice needs >=3 options.')
                need(all(a in item['options'] for a in item['answers']), f'{item_id}: answers must be options.')
            if section == 'listening':
                need(item.get('speech'), f'{item_id}: listening item needs speech.')
            if section in ('reading', 'listening'):
                need(item.get('passageId') in passages, f'{item_id}: unknown passageId.')
            if section == 'reading':
                by_passage_reading.setdefault(item['passageId'], 0)
                by_passage_reading[item['passageId']] += 1
            if section == 'listening':
                by_passage_listening.setdefault(item['passageId'], 0)
                by_passage_listening[item['passageId']] += 1
        reading_ready = sum(1 for n in by_passage_reading.values() if n >= READING_PER_PASSAGE)
        listening_ready = sum(1 for n in by_passage_listening.values() if n >= LISTENING_PER_PASSAGE)
        need(reading_ready >= MINIMUM['reading_ready'], f'{lid}: needs >= {MINIMUM["reading_ready"]} reading passages.')
        need(listening_ready >= MINIMUM['listening_ready'], f'{lid}: needs >= {MINIMUM["listening_ready"]} listening passages.')
        need(counts.get('recognition', 0) >= MINIMUM['recognition'], f'{lid}: needs >= {MINIMUM["recognition"]} recognition items.')
        need(counts.get('writing', 0) >= MINIMUM['writing'], f'{lid}: needs >= {MINIMUM["writing"]} writing items.')
    return bank


def item_literal(item):
    options = f"listOf({', '.join(q(o) for o in item['options'])})" if item.get('options') else 'listOf()'
    extras = []
    if item.get('unitID'):
        extras.append(f"unitID = {q(item['unitID'])}")
    if item.get('passageId'):
        extras.append(f"passageId = {q(item['passageId'])}")
    if item.get('speech'):
        extras.append(f"speech = {q(item['speech'])}")
    extras_code = (', ' + ', '.join(extras)) if extras else ''
    enum_name = item['section'].upper()
    return (f"            LevelTestItem(id = {q(item['id'])}, section = LevelTestSection.{enum_name},\n"
            f"                exercise = Exercise(id = {q(item['id'])}, kind = {q(item['kind'])}, prompt = {q(item['prompt'])},\n"
            f"                    options = {options}, answers = listOf({', '.join(q(a) for a in item['answers'])}), explain = {q(item.get('explain', ''))}){extras_code})")


CHUNK = 40  # JVM methods cap at 64KB: one listOf per chunk keeps <clinit> small.

def emit_kotlin(banks, package_line):
    lines = [f"{package_line}\n",
             "/** Generated by scripts/export_leveltests.py from the shared level-tests",
             "    JSON — never edit by hand. One bank per language: per-level pools the",
             "    weekly verifier composes from (level-tests). Pools are chunked so no",
             "    single method exceeds the JVM size limit. */",
             "object LevelTestsData {"]
    all_entries = []
    for bank in banks:
        bank_tag = bank['languageID'].capitalize()
        for level in bank['levels']:
            tag = f"{bank_tag}{level['level'].replace('-', '')}"
            chunks = [level['pool'][start:start + CHUNK] for start in range(0, len(level['pool']), CHUNK)]
            for index, chunk in enumerate(chunks):
                lines.append(f"    private fun {tag}Pool{index}() = listOf(")
                for item in chunk:
                    lines.append(item_literal(item) + ',')
                lines.append('    )')
            pool_expression = ' + '.join(f"{tag}Pool{index}()" for index in range(len(chunks)))
            thresholds = ', '.join(f"{q(k)} to {v}" for k, v in level['thresholds'].items())
            entry_lines = [f"            LevelTestLevel(languageID = {q(bank['languageID'])}, level = {q(level['level'])}, version = {level['version']},\n"
                f"                thresholds = mapOf({thresholds}),\n"
                f"                passages = listOf("]
            for passage in level['passages']:
                entry_lines.append(f"                    LevelTestPassage(id = {q(passage['id'])}, title = {q(passage.get('title', ''))}, text = {q(passage['text'])}),")
            entry_lines.append(f"                ),\n                pool = {pool_expression}),")
            all_entries.append('\n'.join(entry_lines))
    lines.append('    val all: List<LevelTestBank> = listOf(')
    for bank_index, bank in enumerate(banks):
        bank_levels = [entry for entry in all_entries if entry.startswith(f"            LevelTestLevel(languageID = {q(bank['languageID'])}")]
        lines.append(f"        LevelTestBank(languageID = {q(bank['languageID'])}, levels = listOf(")
        lines.extend(bank_levels)
        lines.append('        ))' + (',' if bank_index < len(banks) - 1 else ''))
    lines.append('    )')
    lines.append('}')
    return '\n'.join(lines) + '\n'


def exam_models():
    from exam_assessment import embed_assessment
    path = ROOT / 'shared/leveltests/exam-models.json'
    return embed_assessment(ROOT, json.loads(path.read_text()))


def emit_typescript(banks):
    """Server mirror of the pools (services/api): the weekly endpoint composes
    from the same content, and its composer must match the Kotlin engine
    byte-for-byte (locked by golden tests on both sides)."""
    lines = ['// Generated by scripts/export_leveltests.py from the shared level-tests',
             '// JSON — never edit by hand. Server mirror of the per-level pools',
             '// (level-tests weekly endpoint).',
             'export interface LevelTestContentItem {',
             '  id: string; section: LevelTestSection; kind: string; prompt: string;',
             '  options?: string[]; answers: string[]; explain: string;',
             '  unitID?: string; passageId?: string; speech?: string;',
             '}',
             'export interface LevelTestContentPassage { id: string; title: string; text: string }',
             'export type LevelTestSection = \'recognition\' | \'reading\' | \'listening\' | \'writing\';',
             'export interface LevelTestContentLevel {',
             '  level: string; version: number; thresholds: Record<string, number>;',
             '  passages: LevelTestContentPassage[]; pool: LevelTestContentItem[];',
             '}',
             'export interface LevelTestContentBank { languageID: string; levels: LevelTestContentLevel[] }',
             'export const LEVEL_TEST_BANKS: LevelTestContentBank[] = ']
    # TS interfaces treat those fields as optional: explicit nulls are dropped
    # so the generated literal type-checks.
    def clean(item):
        return {k: v for k, v in item.items() if v is not None}
    sanitized = [{**{k: v for k, v in bank.items() if k != 'schemaVersion'},
                  'levels': [{**level, 'pool': [clean(item) for item in level['pool']]}
                             for level in bank['levels']]} for bank in banks]
    lines.append(json.dumps(sanitized, ensure_ascii=False, separators=(',', ':')) + ';\n')
    lines.append('export const EXAM_MODELS = ' + json.dumps(exam_models(), ensure_ascii=False, separators=(',', ':')) + ';\n')
    return '\n'.join(lines) + '\n'


def ts_dest():
    return ROOT / 'services/api/src/level-tests-content.generated.ts'


def kotlin_dest_and_package():
    matches = glob.glob(str(ROOT / 'apps/android/app/src/main/java/*/*/*/core/Languages.kt'))
    if not matches:
        raise SystemExit('Generated Languages.kt not found under apps/android/app/src/main/java.')
    text = pathlib.Path(matches[0]).read_text()
    package = next(line for line in text.split('\n') if line.startswith('package ')).replace('package ', '')
    return pathlib.Path(matches[0]).parent / 'LevelTestsData.kt', f'package {package}'


def swift_dest():
    return ROOT / 'apps' / 'ios' / 'Core' / 'LevelTestsData.swift'


def emit_swift(banks):
    sanitized = []
    for bank in banks:
        levels = []
        for level in bank['levels']:
            pool = []
            for item in level['pool']:
                exercise = {key: item.get(key) for key in ('id', 'kind', 'prompt', 'answers', 'explain')}
                exercise['options'] = item.get('options') or []
                fields = {key: item[key] for key in ('id', 'section', 'unitID', 'passageId', 'speech') if key in item}
                pool.append({**fields, 'exercise': exercise})
            levels.append({'languageID': bank['languageID'], 'level': level['level'], 'version': level['version'],
                           'thresholds': level['thresholds'], 'passages': level['passages'], 'pool': pool})
        sanitized.append({'languageID': bank['languageID'], 'levels': levels})
    payload = json.dumps(sanitized, ensure_ascii=False, separators=(',', ':'))
    return ('// Generated by scripts/export_leveltests.py from shared/leveltests/*.json; do not edit.\n'
            'import Foundation\n\n'
            'public enum LevelTestsData {\n'
            '    private static let payload = #"""\n' + payload + '\n"""#\n'
            '    public static let all: [LevelTestBank] = try! JSONDecoder().decode([LevelTestBank].self, from: Data(payload.utf8))\n'
            '}\n')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--check', action='store_true')
    arguments = parser.parse_args()
    paths = sorted(SOURCE_DIR.glob('*-leveltests.json'))
    if not paths:
        raise SystemExit(f'No *-leveltests.json under {SOURCE_DIR}.')
    banks = [validate(json.loads(path.read_text())) for path in paths]
    destination, package_line = kotlin_dest_and_package()
    generated = emit_kotlin(banks, package_line)
    ts = emit_typescript(banks)
    ts_destination = ts_dest()
    swift_destination = swift_dest()
    swift = emit_swift(banks)
    if arguments.check:
        ok_kotlin = destination.exists() and destination.read_text() == generated
        ok_ts = ts_destination.exists() and ts_destination.read_text() == ts
        ok_swift = swift_destination.exists() and swift_destination.read_text() == swift
        if ok_kotlin and ok_ts and ok_swift:
            print('level test data up to date')
        else:
            raise SystemExit('level test data is out of date; run scripts/export_leveltests.py.')
    else:
        destination.write_text(generated)
        ts_destination.write_text(ts)
        swift_destination.write_text(swift)
        print(f'wrote {destination} ({sum(len(l["pool"]) for b in banks for l in b["levels"])} items, '
              f'{sum(len(b["levels"]) for b in banks)} levels), {ts_destination}, and {swift_destination}')


if __name__ == '__main__':
    main()
