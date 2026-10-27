# Tasks: Mobile review follow-up

Authority: `informe-revision-implementacion-app-movil-2026-10-27.md` and `design.md`; detailed execution plan: `docs/superpowers/plans/2026-10-27-mobile-review-followup.md`. Check a task only after its implementation and applicable evidence are observed. Swift, device and external-provider limitations remain explicit.

- [ ] F01 / R01 — Quarantine unattributed fallback on Android and iOS; preserve backups and provide non-destructive recovery.
- [ ] F02 / R02 — Decode and apply actual Android v1 response; retain first/last timestamps and strict fallback. Implementation commit `5d2be36` and JVM integration regressions present; focused/full Android suites passed. ViewModel-mounted account-switch/fencing proof remains pending and the task is not closed.
- [ ] F03 / R03 — Exclude invalid Android timestamp contributions before outbound aggregation. Filter and DB-to-HTTP regression are implemented; owner-scoped visible sync status remains part of F07.
- [ ] F04 / R04,R07,R18 — Sequence account adoption/bootstrap and persist per-owner eligibility.
- [ ] F05 / R05 — Make Android and iOS upload cursor comparisons match tuple ordering.
- [ ] F06 / R06 — Persist Android download pages and cursor before fetching the next page.
- [ ] F07 / R08 — Surface owner-scoped synchronization status and retry in both apps.
- [ ] F08 / R09,R10 — Correct iOS source/test compilation and fixture defects; execute Swift/Xcode gates when available.
- [ ] F09 / R11,R12 — Preserve bounded manual audio and utterance onset, with a recoverable notice.
- [ ] F10 / R13 — Sequence greeting instruction, greeting and first voice turn; fix the controlled T10 race.
- [ ] F11 / R14 — Keep manual microphone mute available during pending work.
- [ ] F12 / R15 — Preserve subsequent typed edits and communicate in-flight retry state in iOS.
- [ ] F13 / R16 — Fail closed on rejected iOS credentials even if Keychain removal fails.
- [ ] F14 / R17 — Fence pronunciation results by captured owner and data generation.
- [ ] F15 / R19 — Prove and correct shared iOS audio-session ownership/lifecycle as needed.
- [ ] F16 / R01–R19 — Whole-feature review and final shared/API/Android/iOS/UI/device verification; report unresolved proof honestly.

## Evidence log

- 2026-10-27: Review baseline `7528e44` plus existing T10 and T16 worktree changes. Prior review: `make check` 148 tests, API 756/756 and Android 804/804; iOS and connected UI unavailable. This follow-up has not yet implemented its tasks.
- 2026-10-27: Android baseline `:app:compileDebugKotlin` exit 0 (up-to-date); not a substitute for RED/GREEN.
- 2026-10-27: F02 is independent of F01 and is being implemented first while the cross-platform quarantine path is prepared. `ProgressClientTest.legacyBootstrapAcceptsActualBackendFirstAndLastTimestamps` failed as expected because the real v1 response lacks `at`; implementation and complete production-path verification remain in progress.
- 2026-10-27: F02 focused review found malformed identity acceptance, inconsistent numeric decoding and missing DB/upload integration proof. Two new focused regressions failed for those first two defects before correction; after correction `ProgressClientTest` passed. A production decoder plus SQLite/HTTP integration regression is in progress. F02 remains open pending that proof, full suite and scoped re-review.
- 2026-10-27: F02 SQLite/HTTP integration test failed before the production decoder correction, then passed. It covers real fetch, decoder, SQLite persistence, `ProgressSync.entries`, upload batching and v1 fallback. Android focused tests and full app suite passed (799 app tests, no failures); engine suite was up-to-date from the prior 11-test run. Scoped re-review confirmed the earlier malformed-identity/numeric issues and split-field regression gaps fixed. ViewModel-mounted owner-switch/fencing remains unverified; keep F02 open. Unrelated T10 hunks remain unstaged.
- 2026-10-27: A clean scratch copy of `HEAD` plus the exact staged F02 candidate passed the focused Android tests (all 38 Gradle tasks executed), independently of the pre-existing T10 worktree edits. Parent committed the candidate as `5d2be36`; the mounted ViewModel/fencing gate remains pending.
- 2026-10-27: F01 Android active-identity quarantine test observed RED: corrupt shared JSON returned fail-safe mode instead of quarantining. Runtime entry now requires an identity and both open/migration failure paths quarantine instead of serving the shared fallback. Focused and full Android suites passed, but independent verification stopped at provider usage limit; backup behavior, non-destructive retry UI and iOS remain pending. Do not close or commit F01 yet.
- 2026-10-27: F03 regression observed RED (invalid contribution appeared in outbound aggregate); implementation filters each invalid contribution before aggregation and uses the same validated millisecond rounding for wire serialization. DB-to-HTTP test confirms valid same-identity progress uploads and invalid DB values remain unchanged. Fixed-clock and fractional-boundary regressions pass; independent scratch verification passed 6/6 tests with matching diff hash `eb8e5cda833639d9bf507632733809aaedeb8aeb1ac2226b6cd4c64fd3990a61`. Full Android suite executed 804 tests with 1 failure in pre-existing T10 voice test `an unknown stt engine selection silently uses the local pass`; do not attribute it to F03. Visible owner-scoped sync status remains pending in F07.
