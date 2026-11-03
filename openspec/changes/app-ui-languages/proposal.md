# Proposal: app-ui-languages

## Intent

Ship the app UI in every language the app teaches. `app-language-selector`
delivered System/English/Spanish; this change adds the remaining learning
languages from `LanguageRegistry` — French, German, Italian, Portuguese,
Norwegian (Bokmål) and Mandarin (Simplified) — as full UI languages, one
phase per language.

## Problem

A learner studying French, German, Italian, Portuguese, Norwegian or Chinese
sees the interface in English or Spanish only. Both platforms already have
the selector and the localization plumbing; only the translations and the
locale wiring for the remaining six languages are missing.

## Approach

- **Infra phase (F0).** Extend the selector option lists (`AppLanguage`,
  `UiLanguage`), `locales_config.xml`, the iOS `UiLanguage` enum and the
  coverage gates (Android key-parity checker, iOS catalog test) to the full
  language set, plus a `scripts/check_ui_strings.py` gate wired into
  `make check`.
- **One phase per language (F1–F6: fr, de, it, pt, nb, zh).** Each phase adds
  `values-XX/` translations for every Android translatable value file (727
  entries), the `XX` localization for every key in the iOS string catalog
  (414 keys), `XX.lproj/InfoPlist.strings` (with the generator updated), the
  selector option, and passes the parity gates. Translations are authored by
  an automated pass per language and must be reviewed by a native speaker
  before store release.

## Non-goals

- Learning content itself (`shared/` JSON) — already per-learning-language.
- Placement/leveltest/exam content in the new languages.
- Server or admin-panel localization.
- Audio voices or pronunciation models for the new UI languages.

## Risks

- ~4,400 Android + ~2,500 iOS strings authored per automated pass; native
  review is mandatory before store release (tracked per phase).
- Norwegian and Mandarin are learning languages with special orthography
  (Bokmål; Simplified Chinese) — translations must respect them; `zh` uses
  `values-zh` and the `zh` catalog code consistently.
- The parity gate must keep placeholder integrity (`%1$s`, `%lld`, `%@`) —
  a mismatch fails the build gate, not a user screen.
