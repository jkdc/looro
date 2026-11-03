# Tasks: app-ui-languages

## 0. Infra — selector and gates for the full language set

- [ ] 0.1 `core/AppLanguage.kt`: options `system|en|es|fr|de|it|pt|nb|zh`, tag
  mapping, JVM test updated.
- [ ] 0.2 Android selector options and `res/xml/locales_config.xml` extended;
  iOS `UiLanguage` cases with locales; `LocalizationCatalogTests` asserts
  every supported language.
- [ ] 0.3 `scripts/check_ui_strings.py`: Android key parity across
  `values/` and every `values-XX/`, iOS catalog coverage for every language,
  placeholder integrity; wired into `make check`.

## 1. Phase: French (fr)

- [ ] 1.1 `values-fr/` full translation (all translatable value files).
- [ ] 1.2 Catalog `fr` localization for all 414 keys; `fr.lproj/InfoPlist.strings`;
  generator emits it.
- [ ] 1.3 Gates green (parity, catalog coverage, placeholders); commit.

## 2. Phase: German (de)

- [ ] 2.1 `values-de/` full translation.
- [ ] 2.2 Catalog `de`; `de.lproj/InfoPlist.strings`; generator.
- [ ] 2.3 Gates green; commit.

## 3. Phase: Italian (it)

- [ ] 3.1 `values-it/` full translation.
- [ ] 3.2 Catalog `it`; `it.lproj/InfoPlist.strings`; generator.
- [ ] 3.3 Gates green; commit.

## 4. Phase: Portuguese (pt)

- [ ] 4.1 `values-pt/` full translation.
- [ ] 4.2 Catalog `pt`; `pt.lproj/InfoPlist.strings`; generator.
- [ ] 4.3 Gates green; commit.

## 5. Phase: Norwegian Bokmål (nb)

- [ ] 5.1 `values-nb/` full translation.
- [ ] 5.2 Catalog `nb`; `nb.lproj/InfoPlist.strings`; generator.
- [ ] 5.3 Gates green; commit.

## 6. Phase: Chinese Simplified (zh)

- [ ] 6.1 `values-zh/` full translation.
- [ ] 6.2 Catalog `zh`; `zh.lproj/InfoPlist.strings`; generator.
- [ ] 6.3 Gates green; commit.

## 7. Verification

- [ ] 7.1 `make check` green with `check_ui_strings.py` included.
- [ ] 7.2 Instrumented smoke: selector offers all languages and switching to
  one of the new ones renders localized Settings (emulator, API 36).
- [ ] 7.3 verify-report.md: what ran, what is macOS-only, native-review
  status per language.
