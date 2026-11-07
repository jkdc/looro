#!/usr/bin/env python3
"""Gate the app UI string localizations (run by `make check`).

1. Android: every values-XX/ folder exposes the same string/plurals key set
   as values/, with the same format specifiers as the English source.
2. iOS: App/Localizable.xcstrings carries a non-empty, "translated"
   localization for every supported language on every key, with specifiers
   matching the English value.

Supported languages must stay in sync with apps/ios/AppTests/
LocalizationCatalogTests.swift and apps/android/.../core/AppLanguage.kt.
"""
import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ANDROID_RES = ROOT / 'apps/android/app/src/main/res'
CATALOG = ROOT / 'apps/ios/App/Localizable.xcstrings'
LANGUAGES = ['en', 'es', 'fr', 'de', 'it', 'pt', 'nb', 'zh']
TRANSLATABLE = ['strings.xml', 'accounts.xml', 'feedback.xml', 'guest_minutes.xml',
                'hosted_conversations.xml', 'minute_purchases.xml', 'paid_value.xml',
                'settings_parity.xml']

SPEC = re.compile(r'%(?:\d+\$)?(?:s|d|f|lld|@|lf)')


def android_keys(folder: Path):
    keys = {}
    for name in TRANSLATABLE:
        file = folder / name
        if not file.exists():
            continue
        for elem in ET.parse(file).getroot():
            if elem.tag in ('string', 'plurals'):
                keys[(name, elem.get('name'))] = ET.tostring(elem, encoding='unicode')
    return keys


def specs(text: str):
    """Type multiset ignoring positional numbering, plus a check that any
    positional usage forms the complete 1..n permutation (no dropped args)."""
    found = SPEC.findall(text.replace('%%', ''))
    types = sorted(re.sub(r'\d+\$', '', s) for s in found)
    positions = sorted(int(re.match(r'%(\d+)\$', s).group(1)) for s in found if '$' in s)
    if positions and positions != list(range(1, len(found) + 1)):
        return None  # broken positional numbering
    return types


def check_android(errors):
    base = android_keys(ANDROID_RES / 'values')
    for lang in LANGUAGES[1:]:
        folder = ANDROID_RES / f'values-{lang}'
        if not folder.exists():
            errors.append(f'Android: missing values-{lang}/ folder')
            continue
        keys = android_keys(folder)
        missing = set(base) - set(keys)
        extra = set(keys) - set(base)
        if missing:
            errors.append(f'Android values-{lang}: {len(missing)} missing keys, e.g. {sorted(missing)[:5]}')
        if extra:
            errors.append(f'Android values-{lang}: {len(extra)} extra keys, e.g. {sorted(extra)[:5]}')
        for key in set(base) & set(keys):
            want, got = specs(base[key]), specs(keys[key])
            if want != got:
                errors.append(f'Android values-{lang} {key[0]}#{key[1]}: specifiers {got} != {want}')


def check_ios(errors):
    catalog = json.loads(CATALOG.read_text())
    strings = catalog.get('strings', {})
    if not strings:
        errors.append('iOS: Localizable.xcstrings has no strings table')
        return
    for key, entry in strings.items():
        localizations = entry.get('localizations', {})
        for lang in LANGUAGES:
            unit = (localizations.get(lang) or {}).get('stringUnit', {})
            value = unit.get('value', '')
            if unit.get('state') != 'translated' or not value.strip():
                errors.append(f'iOS {lang}: key {key[:60]!r} missing or not translated')
            elif lang != 'en' and specs(value) != specs(key):
                errors.append(f'iOS {lang}: key {key[:60]!r} specifiers {specs(value)} != {specs(key)}')


def main():
    errors = []
    check_android(errors)
    check_ios(errors)
    if errors:
        print(f'UI string localization gate FAILED ({len(errors)} problem(s)):')
        for e in errors[:30]:
            print(' -', e)
        return 1
    print(f'UI string localization gate OK ({len(LANGUAGES)} languages, Android value files + iOS catalog)')
    return 0


if __name__ == '__main__':
    sys.exit(main())
