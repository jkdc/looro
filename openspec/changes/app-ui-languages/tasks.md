# Tasks: app-ui-languages

## 0. Infra — selector and gates for the full language set

- [x] 0.1 `core/AppLanguage.kt`: options `system|en|es|fr|de|it|pt|nb|zh`, tag
  mapping, JVM test updated.
- [x] 0.2 Android selector options and `res/xml/locales_config.xml` extended;
  iOS `UiLanguage` cases with locales; `LocalizationCatalogTests` asserts
  every supported language.
- [x] 0.3 `scripts/check_ui_strings.py`: Android key parity across
  `values/` and every `values-XX/`, iOS catalog coverage for every language,
  placeholder integrity; wired into `make check`.

## 1. Phase: French (fr)

- [x] 1.1 `values-fr/` full translation (all translatable value files).
- [x] 1.2 Catalog `fr` localization for all 414 keys; `fr.lproj/InfoPlist.strings`;
  generator emits it.
- [x] 1.3 Gates green (parity, catalog coverage, placeholders); commit.

## 2. Phase: German (de)

- [x] 2.1 `values-de/` full translation.
- [x] 2.2 Catalog `de`; `de.lproj/InfoPlist.strings`; generator.
- [x] 2.3 Gates green; commit.

## 3. Phase: Italian (it)

- [x] 3.1 `values-it/` full translation.
- [x] 3.2 Catalog `it`; `it.lproj/InfoPlist.strings`; generator.
- [x] 3.3 Gates green; commit.

## 4. Phase: Portuguese (pt)

- [x] 4.1 `values-pt/` full translation.
- [x] 4.2 Catalog `pt`; `pt.lproj/InfoPlist.strings`; generator.
- [x] 4.3 Gates green; commit.

## 5. Phase: Norwegian Bokmål (nb)

- [x] 5.1 `values-nb/` full translation.
- [x] 5.2 Catalog `nb`; `nb.lproj/InfoPlist.strings`; generator.
- [x] 5.3 Gates green; commit.

## 6. Phase: Chinese Simplified (zh)

- [x] 6.1 `values-zh/` full translation.
- [x] 6.2 Catalog `zh`; `zh.lproj/InfoPlist.strings`; generator.
- [x] 6.3 Gates green; commit.

## 7. Verification

- [x] 7.1 `make check` green with `check_ui_strings.py` included.
- [x] 7.2 Instrumented smoke: selector offers all languages and switching to
  one of the new ones renders localized Settings (emulator, API 36).
- [x] 7.3 verify-report.md: what ran, what is macOS-only, native-review
  status per language.
