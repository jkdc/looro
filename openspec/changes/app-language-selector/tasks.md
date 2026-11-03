# Tasks: app-language-selector

## 1. SDD

- [x] 1.1 proposal.md / design.md / tasks.md / spec deltas created.

## 2. Android — hardcoded strings (F1)

- [x] 2.1 Test-first: instrumented test asserting `provider-personal-key` and
  `provider-hosted-minutes` render `R.string` values (fails before migration).
- [x] 2.2 Add `settings_provider_personal_key` / `settings_provider_hosted_minutes`
  to `values/strings.xml` and `values-es/strings.xml`; replace the two
  literals in `SettingsScreen.kt`.
- [x] 2.3 `make android-build` green (unit tests + lint + APK).

## 3. Android — per-app language selector (F2)

- [x] 3.1 JVM test-first: `AppLanguageTest` for option ids, tags and mapping
  (fails: class does not exist).
- [x] 3.2 Add `core/AppLanguage.kt`; add `androidx.appcompat:appcompat` to
  `app/build.gradle.kts`.
- [x] 3.3 `MainActivity` extends `AppCompatActivity`; `Theme.App.Base`
  reparented to `Theme.AppCompat.DayNight.NoActionBar`; manifest gains
  `android:localeConfig`, `locales_config.xml` and the auto-store service.
- [x] 3.4 Selector row (`settings-app-language`) in `SettingsScreen.kt` first
  group, backed by `AppCompatDelegate` application locales; new strings
  (`settings_app_language*`) in EN+ES.
- [x] 3.5 Instrumented test: select Español → settings title in Spanish after
  recreation → back to System.
- [x] 3.6 `make android-build` green; connectedUiTest if an emulator is
  available (otherwise recorded as unverified).

## 4. iOS — localization (F3)

- [x] 4.1 Test-first: `AppTests/LocalizationCatalogTests.swift` (catalog
  parses, full `es` coverage, no placeholder states). Fails without the
  catalog; macOS-only to run, mirrored by a Python check on Linux.
- [x] 4.2 Extract all user-facing literals from the 14 App view files into
  `App/Localizable.xcstrings` (key = English literal) with Spanish
  translations.
- [x] 4.3 Migrate `String`-typed call sites (dynamic copy, variable labels)
  to `String(localized:)` / `LocalizedStringKey`.
- [x] 4.4 Python gate: catalog JSON valid, `es` coverage = 100%.

## 5. iOS — selector (F4)

- [x] 5.1 `App/UiLanguage.swift` (`system|en|es`, `@AppStorage`) + locale
  injection at `AppRoot`.
- [x] 5.2 Picker row in `SettingsView` first section with
  `accessibilityIdentifier("settings-app-language")`; selector strings added
  to the catalog.

## 6. iOS — generator alignment (F5)

- [x] 6.1 `generate_project.py` emits AppTests target, InfoPlist.strings
  variant group, `Localizable.xcstrings`, `knownRegions` += es.
- [x] 6.2 Regenerate and diff `project.pbxproj`: only expected changes.
- [ ] 6.3 `AppUITests` pinned to launch locale / identifier-based assertions
  (task recorded; executable only on macOS).

## 7. Verification

- [x] 7.1 `make check` (exporters, cross-platform, db schema) unaffected.
- [ ] 7.2 `swift test --package-path apps/ios` (Core) green.
- [x] 7.3 Record what ran and what could not run on this Linux host in
  `verify-report.md`: iOS App compile + AppTests + AppUITests require macOS
  (Xcode 26); human review of the ~280 Spanish translations pending.
