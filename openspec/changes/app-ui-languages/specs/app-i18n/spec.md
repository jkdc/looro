# Delta Spec: app-i18n

## MODIFIED Requirements

### Requirement: Full string localization coverage

Every user-facing UI string in both mobile apps SHALL resolve through the
platform localization mechanism: Android `stringResource` entries mirrored in
`values/` and one `values-XX/` folder per supported UI language, iOS a
`Localizable.xcstrings` catalog with complete coverage for every supported
language. The supported UI languages are the `LanguageRegistry` learning
languages: en, es, fr, de, it, pt, nb, zh. No user-facing UI literal MAY
bypass localization, except brand names (e.g. "palique").

#### Scenario: per-language parity gate

- **WHEN** `scripts/check_ui_strings.py` runs (part of `make check`)
- **THEN** every Android `values-XX/` exposes the same key set as `values/`,
  the iOS catalog carries a non-empty translation for every supported
  language on every key, and format specifiers match the English source.

#### Scenario: selector offers every supported language

- **WHEN** the in-app language selector opens on either platform
- **THEN** it lists System plus all supported languages, and selecting one
  renders the UI in that language.
