# Verify Report: scenario-practice

Guided situation practice (Android wave) + iOS parity follow-up, branch
`feature/learning-platform`. The Android+API phase shipped before the iOS
parity change started (Phase 0 baseline, see `informe-situaciones-2026-10-02.md`).

## Android + API (pre-wave baseline, recorded in `informe-situaciones-2026-10-02.md`)

- Android unit suite `:app:testDebugUnitTest` **green**, including the new
  `ScenarioPracticeTest` (16 cases); `:app:assembleDebug` green.
- Server: `tsc --noEmit` clean; full `npm test` against a disposable
  PostgreSQL — **386/391** (5 remaining failures are pre-existing Node 18
  webcrypto environmental failures — auth, apple-revocation, accounts,
  minute-providers — reproduced on a clean tree; the Android-wave run recorded
  in `informe-situaciones-2026-10-02.md` showed the same environmental set plus
  a date-window test).
- Emulator smoke: scenario card → session with phases → review sheet →
  practise again (screenshots in `e2e-evidence/`).

## iOS parity (Tasks 1–3 of the follow-up wave)

- **Task 1 — Core** (`83246c3`): `ScenarioReview`/`ReviewedError`/
  `ReviewedPhrase`, `ScenarioReviewing.parse/validate/previousErrors`,
  `Scenarios` catalog, TeachingPolicy scenario prompts,
  `ScenarioReviewingTests` (mirrors the JVM suite). XCTest cases committed;
  see the macOS gate note below.
- **Task 2 — Session wiring** (`eaef9c7`, fix `b83db79`): coordinator scenario
  branch, `chooseScenario`, review request with 120 s timeout, hosted
  post-close window (`awaitHostedClose` before the helper POST),
  `ScenarioReviewState` lifecycle with `practiseAgain` / `dismissScenarioReview`.
- **Task 3 — Review UI + picker** (this commit): `ScenarioReviewSheet.swift`
  (Preparing / Failed / Ready with retry-focus banner, strengths, per-error
  "You said" / "Better" cards + note, key phrases saved through the existing
  spaced-recall notebook path `LearningStore.saveLookupExpression` →
  `RecallEngine.saveLookup`), "Practise a situation" picker section in
  `ThemesView` (8 cards + custom-situation sheet sanitized by
  `Scenarios.custom`), sheet mounted in `RootView` above the error dialog,
  new file registered in `project.pbxproj` (explicit file references).
  `openspec/changes/scenario-practice/tasks.md` 6.1–6.3 marked done.

## Gates

- `scripts/check_cross_platform.py`: **green** after each task (final run on
  the Task 3 tree).
- **iOS compile/test gate: macOS-gate-pending.** No Swift toolchain exists on
  the Linux authoring machine, so the app target and the XCTest cases in
  `apps/ios/Tests/ScenarioReviewingTests.swift` have not been compiled or run
  here. They are queued for the macOS gate. Mitigations applied instead:
  conservative SwiftUI following existing view patterns, manual re-reads of
  every new/edited Swift file, grep audit that every Core/coordinator symbol
  referenced from the views exists, bracket/paren balance checks, and
  `project.pbxproj` ID-uniqueness verification.

## Deferred minors (from the wave ledger, `.zuperpowers/sdd/2026-10-02-scenario-practice-ios-parity/progress.md`)

- Task 1: legacy-decode test key-stripping is vestigial (injecting keys first
  would be stronger) — `ScenarioReviewingTests.swift:282`.
- Task 1: Swift `Response` accepts an explicit JSON `null` where Kotlin rejects
  it — unreachable under the enforced helper schema.
- Task 1: `check_cross_platform.py` `ARCHIVE_STRUCTS` lacks
  `ScenarioReview`/`ReviewedError`/`ReviewedPhrase`, so drift in them is not
  machine-caught.
- Task 2: review task captures `self` strongly for its bounded lifetime
  (bounded by the 120 s timeout; house style).
- Task 2: the validated review store-write can land after the learner moved on
  (Android-identical, intentional).
- Task 2: duplicated close-poll logic between `closeHostedSession()` and
  `awaitHostedClose` (extract a helper if a third call site appears).
- Task 2: `Date()` wall-clock 8 s close deadline (pre-existing style; the 120 s
  race is the hard bound).
- Task 2 observation: hosted final assessments do not ride the post-close
  window (pre-existing iOS behavior, out of scope here).
