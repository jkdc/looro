# Design: app-language-selector

## Android — per-app language mechanics

`androidx.appcompat` 1.7.x is the standard backport path for per-app locales
below API 33 and delegates to the system implementation on API 33+. Choices:

- **AppCompatActivity base class.** `AppCompatDelegate.setApplicationLocales`
  only applies from `AppCompatActivity`. The app is Compose-only; the base
  class change is transparent to Compose. Requirement: the activity theme
  must be an AppCompat theme, hence the `Theme.App.Base` parent change
  (`android:style/Theme.Material.Light.NoActionBar` →
  `Theme.AppCompat.DayNight.NoActionBar`). All styling items use `android:*`
  attributes and keep working.
- **Auto-persistence.** Declaring
  `androidx.appcompat.app.AppLocalesMetadataHolderService` with
  `autoStoreLocales=true` makes appcompat persist the selection itself; no
  custom SharedPreferences store is needed. On API 33+ the system per-app
  locale store is the source of truth and the manifest
  `android:localeConfig` makes the choice visible in system Settings.
- **UI state.** The selector does not go through `AppViewModel` or the
  SQLite preferences row: appcompat already owns read/write/persist/recreate.
  `SettingsScreen` reads `AppCompatDelegate.getApplicationLocales()` and
  writes `setApplicationLocales(LocaleListCompat.forLanguageTags(tag))`.
  Only the option model is app code.
- **`core/AppLanguage.kt`** holds the pure model: option ids (`system`, `en`,
  `es`), the stable language tags, and `fromTags()`/`tag()` mapping. JVM
  unit-testable without Android framework classes (test module has no
  Robolectric and `isReturnDefaultValues = true`).

### Recreate flow

Selecting a language recreates the activity (below API 33 appcompat does it
automatically). Activity-scoped ViewModels survive recreation;
`rememberSaveable` state survives. The instrumented test drives this exact
flow and asserts a known settings title re-renders in Spanish.

## iOS — localization mechanics

- **String catalog over `.strings`.** Xcode 15+ catalogs
  (`Localizable.xcstrings`) are JSON, single-resource, and translate literal
  SwiftUI strings without touching view code: `Text("literal")` takes a
  `LocalizedStringKey` whose key is the literal. With `es` present in the
  catalog, SwiftUI resolves the running locale against it. Interpolated
  literals become format keys (`"Hi, %@"` style).
- **In-app selector.** `UiLanguage` enum persisted via `@AppStorage`. When a
  concrete language is chosen, `AppRoot` injects
  `.environment(\.locale, Locale(identifier: tag))` on the root; `system`
  injects nothing. `\.locale` drives SwiftUI's `Text` localization lookup —
  the same mechanism previews use. With the catalog shipped, iOS also gains
  the native per-app language switch in system Settings; the in-app selector
  simply overrides it.
- **`String`-typed call sites.** `Text(stringVariable)` and
  `accessibilityLabel(stringVariable)` do not localize. Those move to
  `Text(String(localized:))`-style code or literal `LocalizedStringKey`s;
  dynamic copy builders (`Reports.swift`) keep building strings but from
  localized literals.

## iOS — generator alignment

`generate_project.py` must reproduce everything the committed project
contains plus the new catalog, so regeneration stays lossless:

1. Emit the `AppTests` native target (sources from `AppTests/*.swift`,
   LearningCore dependency, `TEST_HOST`/`BUNDLE_LOADER`, target dependency on
   `App`).
2. Emit the `InfoPlist.strings` `PBXVariantGroup` (en/es) in the resources
   phase.
3. Emit `App/Localizable.xcstrings` as a `text.json.xcstrings` file reference
   in the resources phase.
4. `knownRegions` becomes `en`, `es`, `Base` (the stray `nb` entry was never
   backed by resources and is dropped, matching the generator's output).

Verification: regenerate, then diff `project.pbxproj` — only the expected
additions (catalog entry, es region) and determinism fixes should appear.

## Test strategy

- Android JVM: `AppLanguageTest` (pure mapping) — fails before the class
  exists.
- Android instrumented: provider buttons render localized strings;
  language selection flow (select es → title in Spanish → back to system)
  via `createAndroidComposeRule<MainActivity>` and existing testTags.
- iOS unit (App target, macOS-only): `LocalizationCatalogTests` loads the
  catalog from the app bundle and enforces full `es` coverage and no
  placeholder states — the offline Python check mirrors this gate so the
  rule can be enforced on Linux too.
- iOS Core (`swift test`): untouched; no Core-side behavior changes.

## Constraints honored

- Test-first per repo policy; every behavior change lands with a test that
  fails without it.
- No edits to generated `*Data.kt`/`*Data.swift`.
- Both `values/` and `values-es/` receive every new Android string.
- No LLM attribution in commits; conventional commit format on the active
  branch.
