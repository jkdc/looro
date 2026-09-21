# Verify Report: brand-constants

Date: 2026-09-21 · Branch: `feature/rebranding-looro` (local, unpushed)

## Result

**Brand is now single-file.** The product is "Palique"; every file, class,
package, resource, build target and backend identifier is generic
(`App*`, `LearningCore`, `app*`), and the brand lives only in
`branding.properties` plus content the constants file drives. A future
rename is: edit `branding.properties` → run
`python3 scripts/apply_branding.py --from-brand Palique` → regenerate the
Xcode project → run the suites. No file moves, no class renames.

## What changed

- **T1** `branding.properties` (APP_NAME, REVERSE_DOMAIN_BASE,
  IOS_BUNDLE_ID, ANDROID_APPLICATION_ID, ANDROID_NAMESPACE, SUPPORT_EMAIL,
  SUPPORT_URL). `app/build.gradle.kts` + `core/engine-local` read
  namespace/applicationId from it; `generate_project.py` reads the iOS
  bundle id; the deploy Makefile + `deploy-local.sh` derive the on-device
  applicationId. Gradle property flags `looro.*` → `app.*`.
- **T2** Android namespace `com.jkdc.looro` → `com.jkdc.app` (dirs, imports,
  JNI `com_jkdc_app`), root project `app`; functional renames
  (`AppViewModel`, `AppColors`, `AppSymbol`, `AppRoot`, `AppSplash`,
  `AppTheme`, `AppIcons`, `AppCaptionCheck`, `AppRoundedFont`,
  `splashPhase`…); files `AppRoot.kt`, `AppSplash.kt`, `AppIcons.kt`,
  `OnboardingTest.kt`, `SplashTest.kt`, `App-LICENSE.txt`; resources
  `ic_launcher`, `app_icon`, `launch_background`, `Theme.App`; native lib
  `libapp_whisper.so` (CMake + `loadLibrary` + release-check fixture).
- **T3** `App.xcodeproj` / target `App` / `AppUITests` / scheme `App`;
  `AppRoot.swift`; package `LearningCore` (all imports); plist keys
  `AppManaged*/AppGoogle*/AppApple*` + `APP_*` xcconfig; deep-link scheme
  `app-word`; backend/CI `app*` (npm `app-server`, DBs `app`/`app_test`/
  `app_billing_test`, role `app_runtime`, `x-app-*` headers, `app-*`
  locks/HMAC, `app.example` placeholder, artifacts).
- **T4** `scripts/apply_branding.py` applied Looro → Palique to live
  content (strings both locales, hosted tutor persona, operator texts,
  store metadata, docs). API surface stays generic (`X-App-Error-Reference`)
  — the one brand-cased header was returned to generic and the suite
  re-verified.

## Verification evidence

| Check | Result |
| --- | --- |
| Android `:app:testDebugUnitTest` + `:core:engine-local:testDebugUnitTest` | **406 tests, 0 failures, 0 errors** (includes the concurrent courses-and-lessons tests) |
| Android `:app:lintDebug` + `:app:compileDebugAndroidTestKotlin` | green |
| Backend full docker suite (fresh `postgres:17` on 55434, `node:22`) | **358 tests, 358 pass, 0 fail** (after re-aligning 4 fixtures + 1 header) |
| `scripts/check_cross_platform.py` | green |
| String key parity `values/` vs `values-es/` | 255/255 |
| Brand sweep | live tree: 0 `Looro`/`looro` (any case); `mural` only in exempted historical paths; `Palique` present where the brand belongs |
| Xcode generation | `App.xcodeproj`: target `App`, `App.app`, `AppUITests`, bundle `com.jkdc.palique` (+`.uitests`) read from `branding.properties` |

## Not verified here (gates)

- **macOS (REQUIRED before merge, unchanged policy)**: `swift test` for
  `LearningCore` + `xcodebuild` for `App.xcodeproj`. The `LearningCore`
  rename touched every Swift import; until the macOS gate runs, iOS
  compilation is reasoning-verified only.
- **Device/simulator**: re-deploy (`make deploy ENGINE=glm` — the Makefile
  now derives the applicationId from `branding.properties`), instrumented
  tests, screenshot re-capture, icon artwork.
- **Publisher data**: `TODO(rebrand)` / `[REQUIRED]` placeholders (support
  email/domain, OAuth client, legal entity) and store registration of
  `com.jkdc.palique` / `com.jkdc.palique.android`.

## Commits

- `c6eda11` docs(openspec): propose brand-constants (Looro taken; Palique)
- `e1a52e4` feat(build): canonical branding.properties wired into gradle, generator and deploy (T1)
- `b391e8b` refactor(android): brand-free structure (T2)
- `89d6e55` refactor(ios,backend,ci): brand-free structure (T3)
- `08cb1ad` feat(brand): apply Palique content pass via scripts/apply_branding.py (T4)
- (this commit) docs(openspec): verify brand-constants (T5)
