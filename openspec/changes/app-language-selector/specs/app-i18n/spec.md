# Delta Spec: app-i18n

## ADDED Requirements

### Requirement: In-app UI language selection

Both mobile apps SHALL offer a UI language selector with the options
`system` (default), `en` and `es`. The choice SHALL persist across launches
and SHALL take effect without reinstalling the app.

#### Scenario: selection overrides the device language

- **WHEN** the user selects `es` in Settings while the device language is
  English
- **THEN** after the UI refreshes, settings and navigation strings render in
  Spanish.

#### Scenario: system follows the device

- **WHEN** the selector is on `system`
- **THEN** the UI language follows the device language, resolving Spanish
  through the `es` resources and English otherwise.

#### Scenario: choice persists

- **WHEN** the app is force-stopped and relaunched after selecting a
  concrete language
- **THEN** the selector still shows that language and the UI renders with
  it.

### Requirement: Full string localization coverage

Every user-facing UI string in both mobile apps SHALL resolve through the
platform localization mechanism: Android `stringResource` entries mirrored in
`values/` and `values-es/`, iOS a `Localizable.xcstrings` catalog with
complete `es` coverage. No user-facing UI literal MAY bypass localization,
except brand names (e.g. "palique").

#### Scenario: catalog coverage gate

- **WHEN** the iOS localization catalog is loaded
- **THEN** every key has a non-empty Spanish translation and no key is in a
  placeholder (new/review) state.

#### Scenario: Android string parity

- **WHEN** the Android string files are compared
- **THEN** `values/` and `values-es/` expose the same key set.

### Requirement: Lossless iOS project regeneration

`scripts/generate_project.py` SHALL reproduce the committed
`project.pbxproj` content: the `App` and `AppUITests` targets, the `AppTests`
target, the `InfoPlist.strings` variant group, `Localizable.xcstrings`, and
the supported regions, so regeneration never drops existing wiring.

#### Scenario: regeneration keeps test targets

- **WHEN** the generator runs against a clean checkout
- **THEN** the produced project contains the `AppTests` target, the
  `InfoPlist.strings` en/es variant group and the `Localizable.xcstrings`
  resource.
