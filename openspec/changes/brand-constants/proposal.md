# Proposal: brand-constants

## Intent

Make the product brand a single-file constant so future renames are cheap,
and apply the second (final) rename Looro → **Palique** ("Looro" is already
taken on both stores). All structural names — files, classes, packages,
resources, targets, backend identifiers — become generic and functional, so
a future brand change touches only `branding.properties` plus a script run.

## Problem

Two rebrands in two days exposed the cost of brand baked into structure: the
first rename moved 174 files and rewrote ~2,850 occurrences by hand-rolled
rules. "Looro" then turned out to be taken on the stores. Without a
canonical constants file and generic structural names, every rename repeats
that work and risks drift between platforms.

## Approach

1. **`branding.properties`** (repo root) becomes the single source of truth:
   `APP_NAME`, `IOS_BUNDLE_ID`, `ANDROID_APPLICATION_ID`,
   `ANDROID_NAMESPACE`, `SUPPORT_EMAIL`, `SUPPORT_URL`. The Gradle build and
   `scripts/generate_project.py` read their identities from it; the Android
   deploy Makefile derives the on-device applicationId from it.
2. **Structural de-branding** (one-time, then stable): Android namespace
   `com.jkdc.looro` → `com.jkdc.app` (JNI symbols follow), branded class
   names → functional generics (`LooroColors`→`AppColors`, `LooroViewModel`
   →`AppViewModel`, `LooroApp` root composable→`AppRoot`, Swift
   `LooroColor`→`AppColor`, package `LooroCore`→`LearningCore`), branded
   filenames → functional names (`MuralApp.kt`→`AppRoot.kt`,
   `MuralSplash.kt`→`AppSplash.kt`, `LooroIcons.kt`→`AppIcons.kt`,
   `ic_looro`→`ic_launcher`, `Looro.xcodeproj`→`App.xcodeproj`, iOS target
   `App`), `LOORO_*` config keys → `APP_*`, backend/CI identifiers
   (`looro-server`, DBs, locks, HMAC domains, headers) → `app*` generics.
   The brand never appears in file, class, package, target or backend
   identifier names again.
3. **Content rebrand via `scripts/apply_branding.py`**: a permanent,
   parameterized rename engine (old brand → new brand, exclusions and
   special cases built in) applies Looro → Palique to user-visible strings,
   IDs and docs from `branding.properties`. Future renames: edit the
   properties file, run the script, regenerate the Xcode project, run the
   suites.

## Scope

- **In scope:** `branding.properties`, build wiring (Gradle, project
  generator, Makefile), structural renames above, `scripts/apply_branding.py`,
  the Palique content pass over live code/strings/docs, verification.
- **Out of scope (unchanged):** historical records and the MIT license
  notices (per `rebranding-looro`), vendored third-party trees, binary
  screenshots/icons artwork, placeholder `[REQUIRED]`/`TODO(rebrand)`
  publisher data.

## Risks

- The namespace change rewrites package/import/JNI names across ~200 Android
  files; mitigated by the full JVM suite + lint on this machine.
- iOS is reasoning-verified only on Linux; macOS `swift test` + xcodebuild
  remains a REQUIRED pre-merge gate. The `LearningCore` package rename
  touches every Swift import — compile risk is real until the macOS gate
  runs.
- The concurrent `courses-and-lessons` session commits on this branch;
  commits here stage explicit paths only and never `git add -A`.

## Delivery strategy

`single-pr` — direct conventional commits on `feature/rebranding-looro`:
record → constants+wiring → Android structure → iOS+backend structure →
Palique content pass → verified close. Android suite, lint, backend docker
suite and `check_cross_platform.py` green before the close.
