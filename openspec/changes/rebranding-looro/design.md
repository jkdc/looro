# Design: rebranding-looro

## Identity mapping (canonical)

| Concept | Before | After |
| --- | --- | --- |
| Product name | Mural | Looro |
| iOS bundle ID | `no.william.mural` | `com.jkdc.looro` |
| iOS UI-test bundle | `no.william.mural.uitests` | `com.jkdc.looro.uitests` |
| iOS accounts-disabled callback scheme | `no.william.mural.accounts-disabled` | `com.jkdc.looro.accounts-disabled` |
| iOS app target / project / products | `Mural`, `Mural.xcodeproj`, `Mural.app`, `MuralUITests`, `MuralCore` | `Looro`, `Looro.xcodeproj`, `Looro.app`, `LooroUITests`, `LooroCore` |
| iOS build-config variables | `MURAL_*` | `LOORO_*` (Info.plist keys `Mural*Enabled` → `Looro*Enabled`) |
| Android namespace | `chat.mural` (+ `chat.mural.engines.local`) | `com.jkdc.looro` (+ `com.jkdc.looro.engines.local`) |
| Android applicationId | `chat.mural.android` (`.uitest` suffix) | `com.jkdc.looro.android` (`.uitest` suffix) |
| Android source tree | `java/chat/mural/**` | `java/com/jkdc/looro/**` |
| Gradle root project | `Mural` | `Looro` |
| Android resources | `@mipmap/ic_mural`, `Theme.Mural` | `@mipmap/ic_looro`, `Theme.Looro` |
| Backend package / DBs / role | `mural-server`, `mural`, `mural_test`, `mural_billing_test`, `mural_runtime` | `looro-server`, `looro`, `looro_test`, `looro_billing_test`, `looro_runtime` |
| Backend internal domains | `hashtext('mural-*')`, `mural-play-v1`, `mural-minute-receipt-v1`, `mural-ai-report`, `mural_result`, `x-mural-*`, `mural-billing`, `mural-foundation-smoke` | `looro-*` equivalents (no production data exists; no migration) |
| Upstream origin/website | `https://mural.chat` | `https://looro.example` placeholder (RFC 2606) |
| Upstream repo links | `github.com/Chuloo/mural` | `github.com/jkdc/looro` |
| Support email | `hi@hackmamba.io` (hardcoded in 3 UI files) | `support@looro.example` centralized constant + `TODO(rebrand)` before store submission |

## Substitution order

Special cases run before the generic pass; the generic pass never runs first:

1. `Chuloo/mural` → `jkdc/looro`
2. `no.william.mural` → `com.jkdc.looro`
3. `chat.mural` → `com.jkdc.looro`; `chat/mural` → `com/jkdc/looro`; `chat_mural` → `com_jkdc_looro`
4. `mural.chat` → `looro.example`
5. `MURAL_` → `LOORO_`
6. `Mural` → `Looro`; then `mural` → `looro`; then `MURAL` → `LOORO`

## Structural moves (not plain substitution)

- `git mv` the Android source trees (`app/src/{main,test,androidTest}/java/chat/mural`,
  engine-local Kotlin tree) to `java/com/jkdc/looro`.
- `git mv` branded filenames: `MuralApp.swift`, `MuralViewModel.kt`,
  `MuralIcons.kt`, `mipmap-*/ic_mural*` resources, then regenerate
  `Looro.xcodeproj` via `scripts/generate_project.py` and delete
  `Mural.xcodeproj`.
- JNI symbol names in `core/engine-local/src/main/cpp` (outside
  `third_party/`) follow the `chat_mural → com_jkdc_looro` rule.

## Exclusions (never rewritten)

`.git`, vendored third-party trees, `verification/**`,
`release/android/**` (evidence + dated progress), dated release audits,
closed `openspec/changes/**` (except this record), `informe-*.md`,
`LICENSE`, binary assets, generated build dirs, `Mural.xcodeproj` (deleted,
not rewritten).

## Support-contact plumbing

`AccountDeletionSupportDialog.kt` already centralizes
`DELETION_SUPPORT_EMAIL`; the placeholder moves there permanently with a
`TODO(rebrand)` marker, `ReportConversationSheet.kt` is refactored to use it
instead of a second hardcoded mailto, and `AccountSheetTest.kt` asserts the
constant-derived values. `SECURITY.md` and backend support docs get the same
placeholder.

## Verification

- Android: `:app:testDebugUnitTest :core:engine-local:testDebugUnitTest
  :app:lintDebug` green on this machine.
- Parity: `scripts/check_cross_platform.py` green (252-key Android string
  parity intact; `TeachingPolicy` wording identical after rename).
- Backend: docker suite per repo recipe (fresh `postgres:17` on 55434 +
  `node:22 npm test` with repo-root mount) green.
- Sweep: case-insensitive `mural` grep over the live tree returns only
  documented exemptions.
- iOS: macOS `swift test` + xcodebuild remain a REQUIRED pre-merge gate
  (pending, as before).
