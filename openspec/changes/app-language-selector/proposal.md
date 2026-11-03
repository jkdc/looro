# Proposal: app-language-selector

## Intent

Let the user pick the app UI language (System / English / Español) inside the
app, on both mobile platforms, instead of silently following the device
language. Also close the last Android i18n gap (two hardcoded button labels)
and give iOS its first real localization: today every iOS UI string is a
hardcoded English literal, so iOS users see English regardless of their device
language.

## Problem

- Android localizes through `values/` + `values-es/` (580 keys, parity
  verified), but `SettingsScreen.kt` renders two provider button labels as
  raw literals (`"Your own key"`, `"Hosted minutes"`), and there is no way to
  decouple the app language from the device language.
- iOS has no localization infrastructure at all: no `Localizable.strings`,
  no string catalog, no `NSLocalizedString`. Roughly 280 user-facing literals
  across 14 view files render in English unconditionally; only
  `InfoPlist.strings` is localized (en/es).
- `scripts/generate_project.py` is out of sync with the committed
  `project.pbxproj`: regenerating today would silently drop the `AppTests`
  target and the `InfoPlist.strings` variant group. Adding a string catalog
  requires the generator to be trustworthy again.

## Approach

- **Android (F1 — strings).** New keys `settings_provider_personal_key` /
  `settings_provider_hosted_minutes` in both string files; the two literals
  become `stringResource(...)`.
- **Android (F2 — selector).** Add `androidx.appcompat:appcompat`, switch
  `MainActivity` to `AppCompatActivity`, repoint `Theme.App.Base` to
  `Theme.AppCompat.DayNight.NoActionBar`, declare
  `android:localeConfig` + `res/xml/locales_config.xml` (en, es) and the
  appcompat auto-store service. The selector is a `SettingsChoiceRow`
  (existing component, tag `settings-app-language`) reading/writing
  `AppCompatDelegate` application locales. Pure mapping lives in a new
  `core/AppLanguage.kt`, covered by a JVM test; behavior is covered by an
  instrumented test (selection → recreation → Spanish UI → back to system).
- **iOS (F3 — localization).** New `App/Localizable.xcstrings` string catalog
  (source language `en`, full `es` coverage). SwiftUI localizes literal
  `Text("…")`/`Button("…")` automatically once the catalog exists, so view
  code changes are limited to call sites that pass `String` variables or
  interpolated strings (they move to `String(localized:)`).
- **iOS (F4 — selector).** New `App/UiLanguage.swift`
  (`system|en|es`, `@AppStorage`), applied as `.environment(\.locale, …)` at
  the app root; a Picker row inside the first Settings section
  (`accessibilityIdentifier("settings-app-language")`).
- **iOS (F5 — tests + generator).** `AppTests/LocalizationCatalogTests.swift`
  gates the catalog (parses, every key has non-empty `es`, no placeholder
  states). `scripts/generate_project.py` is updated to emit the `AppTests`
  target, the `InfoPlist.strings` variant group and
  `App/Localizable.xcstrings`, and `knownRegions` gains `es`.

## Non-goals

- Migrating `ReportConversationSheet.kt`'s self-contained EN/ES copy object
  to `strings.xml` (it is already bilingual; unifying the mechanism is a
  separate cleanup).
- Localizing test-only literals (`AppUITests`, `AppTests`,
  `ScreenshotPreview`) or the learning content itself (`shared/` JSON is
  per-learning-language by design).
- Languages beyond en/es in either app.
- Translating the server/admin panel.

## Risks

- ~280 iOS Spanish translations are authored in one automated pass and need
  a human review pass before release (tracked in tasks.md).
- `AppUITests` asserts literal English labels; a language switch can break
  them. The suite must pin the launch locale or rely on accessibility
  identifiers (macOS-only verification, tracked in tasks.md).
- `AppCompatActivity` theme requirements: `Theme.App.Base` must descend from
  an AppCompat theme or the activity crashes at inflate time — covered by the
  instrumented smoke test that boots `MainActivity`.
- Regenerating `App.xcodeproj` must not drop existing manual entries; the
  generator update is verified by diffing the regenerated project.
