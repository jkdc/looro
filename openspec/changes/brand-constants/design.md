# Design: brand-constants

## Canonical constants — `branding.properties` (repo root)

```properties
APP_NAME=Palique
REVERSE_DOMAIN_BASE=com.jkdc
IOS_BUNDLE_ID=com.jkdc.palique
ANDROID_APPLICATION_ID=com.jkdc.palique.android
ANDROID_NAMESPACE=com.jkdc.app
SUPPORT_EMAIL=support@palique.example
SUPPORT_URL=https://palique.example/support
```

Consumers read from it instead of hardcoding: `app/build.gradle.kts`
(namespace + applicationId), `core/engine-local/build.gradle.kts`
(namespace suffix), `apps/android/Makefile` (adb applicationId),
`scripts/generate_project.py` (iOS bundle id + uitest bundle). Values marked
`.example` are placeholders (`TODO(rebrand)`) until the publisher's real
domain/email exist.

## Rename decision table

Everything below the line is **generic forever**; only the constants above
carry the brand.

| Kind | Before (Looro era) | After (generic) |
| --- | --- | --- |
| Android namespace (+ dirs, imports, JNI `com_jkdc_*`) | `com.jkdc.looro` | `com.jkdc.app` |
| Gradle root project name | `Looro` | `app` |
| Android classes | `LooroViewModel`, `LooroColors`, `LooroSymbol`, `LooroTextButton`, `LooroIcon(s)`, `LooroOrb`, `LooroTextField`, `LooroSearchField`, `LooroStartup`, `LooroLocalEngine`, `LooroTypography`, `LooroTheme`, `LooroSplash`, `LooroApp` (root composable) | `AppViewModel`, `AppColors`, `AppSymbol`, `AppTextButton`, `AppIcon(s)`, `AppOrb`, `AppTextField`, `AppSearchField`, `AppStartup`, `AppLocalEngine`, `AppTypography`, `AppTheme`, `AppSplash`, `AppRoot` |
| Android files | `LooroViewModel.kt`, `LooroIcons.kt`, `MuralApp.kt`, `MuralSplash.kt`, `MuralViewModelTest.kt`, `LooroOnboardingTest.kt`, `LooroSplashTest.kt` | `AppViewModel.kt`, `AppIcons.kt`, `AppRoot.kt`, `AppSplash.kt`, `AppViewModelTest.kt`, `OnboardingTest.kt`, `SplashTest.kt` |
| Android resources | `@mipmap/ic_looro`, `ic_looro_foreground`, `looro_icon`, `looro_launch_background`, `Theme.Looro` | `ic_launcher`, `ic_launcher_foreground`, `app_icon`, `launch_background`, `Theme.App` |
| iOS project/target/scheme | `Looro.xcodeproj`, target `Looro`, `Looro.app`, `LooroUITests`, `Looro.xcscheme` | `App.xcodeproj`, target `App`, `App.app`, `AppUITests`, `App.xcscheme` |
| iOS files | `LooroApp.swift`, `LooroUITests.swift`, `LooroIcon.png` | `App.swift` (struct stays brand-named via content), `AppUITests.swift`, `Icon-1024.png` |
| iOS package | `LooroCore`, `LooroCoreTests` | `LearningCore`, `LearningCoreTests` |
| Swift types | `LooroColor`, `LooroOrb` | `AppColor`, `AppOrb` |
| Config keys | `LOORO_*` env/xcconfig, plist keys `LooroManaged*/LooroGoogle*/LooroApple*` | `APP_*`, `App*` |
| Backend/CI identifiers | `looro-server`, `looro`/`looro_test`/`looro_billing_test` DBs, `looro_runtime`, `looro-*` locks/HMAC/containers, `x-looro-*` headers, `looro_result`, `looro.example` placeholder, artifacts `looro-android-*` | `app*` / `x-app-*` / `app.example` generics |
| Checker/tooling paths | `com/jkdc/looro/**` | `com/jkdc/app/**` |

## Content pass (the only brand-aware step)

`scripts/apply_branding.py` reads `branding.properties`, takes `--from-brand`,
and rewrites live content (apps, docs, release metadata, README, SECURITY)
with ordered, word-boundary rules + the exclusion list (historicals, LICENSE,
third-party, vendored, the properties file itself). For Looro→Palique this
catches: display strings both locales, `struct PaliqueApp`, bundle/applicationId
literals in docs, `palique.example` placeholders, mailto subjects,
`CFBundleDisplayName`.

## Verification

Android `testDebugUnitTest` + `lintDebug` + `compileDebugAndroidTestKotlin`;
backend full docker suite; `check_cross_platform.py`; string key parity;
sweeps: live tree has zero `mural`, zero `looro`, and `palique` only where
the brand belongs. macOS gate stays pending (policy).
