# Tasks: scenario-practice

## Task 1 — Core (Android)

- [x] 1.1 `core/Scenarios.kt`: `Scenario` model, 8 curated situations,
      `Scenarios.custom` sanitizer (one line, control/format chars stripped,
      120-char cap, blank fallback).
- [x] 1.2 `TeachingPolicy.scenarioPractice` (three-phase arc, role/stages,
      skillFocus + previousErrors lines), `scenarioOpening`,
      `scenarioReview` helper instructions, `reviewContext` (byte-budgeted
      full transcript), `previousErrorsLine`, `typedReply` scenario anchor.
- [x] 1.3 `core/Models.kt`: `ReviewedError`, `ReviewedPhrase`,
      `ScenarioReview`; `SessionRecord.scenarioID/scenarioReview/isScenario`.
- [x] 1.4 `core/ScenarioReviewing`: JSON parse + quote-verified validation +
      previousErrors formatting.
- [x] 1.5 `ProgressEngine.SCENARIO_PRACTICE` kind;
      `UsageEvents.SCENARIO_STARTED/COMPLETED`.

## Task 2 — Session wiring (Android)

- [x] 2.1 `AppViewModel.chooseScenario`, `newSession` mode/title/scenarioID,
      `start()` instructions branch, opening command branch, SCENARIO events.
- [x] 2.2 `finish()`: ledger mark + telemetry + `maybeGenerateScenarioReview`
      (post-close window, ≥ 2 user passages, verified storage).
- [x] 2.3 `ScenarioReviewState` lifecycle, `dismissScenarioReview`,
      `practiseAgain()` with error re-injection.
- [x] 2.4 `sendTyped` anchors typed replies to the running scenario.

## Task 3 — UI (Android)

- [x] 3.1 `TopicsScreen` "Practise a situation" section + custom-situation
      dialog (search-aware), `ScenarioCard`.
- [x] 3.2 `ui/ScenarioReviewSheet.kt` (Preparing/Failed/Ready, error cards,
      savable key phrases, Practise again).
- [x] 3.3 `AppRoot` wiring; strings EN + ES.

## Task 4 — Network & server

- [x] 4.1 `HelperPurpose.SCENARIO_REVIEW`; `HostedAPIClient` schema guard;
      `POST_END_PURPOSES`.
- [x] 4.2 `hosted-helpers.ts` purpose + schema guard + post-close window;
      migration 037 (SQL purpose CHECK).
- [x] 4.3 `progress.ts` kind + migration 036; `usage.ts` catalog + migration
      035.

## Task 5 — Verification

- [x] 5.1 JVM `ScenarioPracticeTest` (16 cases: sanitization, prompt shapes,
      review validation/caps/parse, reviewContext budget, archive roundtrip,
      legacy decode).
- [x] 5.2 `UsageMetricsTest` catalog size updated; `:app:testDebugUnitTest`
      full suite green; `:app:assembleDebug` green.
- [x] 5.3 Server: `tsc --noEmit` green; new hosted-helpers unit case;
      post-close integration case extended; full `npm test` green against a
      disposable PostgreSQL (only pre-existing environmental failures remain:
      Node 18 webcrypto in auth/apple-revocation/accounts/minute-providers and
      a date-window test — all reproduced on a clean tree).
- [x] 5.4 `scripts/check_cross_platform.py` green (Swift prompt mirror).
- [x] 5.5 Emulator smoke: scenario card → session with phases → review sheet →
      practise again (screenshots in `e2e-evidence/`).

## Task 6 — iOS parity follow-up (separate change)

- [x] 6.1 `ConversationCoordinator` scenario session + review request.
- [x] 6.2 `Storage.swift` mirror of the new `SessionRecord` fields.
- [x] 6.3 iOS review UI; macOS `swift test` gate — macOS-gate-pending: no Swift
      toolchain on the Linux authoring machine, so the XCTest cases in
      `apps/ios/Tests/ScenarioReviewingTests.swift` are written and committed but
      execute at the macOS gate (see `verify-report.md`).
