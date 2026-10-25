# Tasks: Mobile Audit Remediation

All tasks are pending. Sequential Superpowers implementation/review uses `docs/superpowers/plans/2026-10-25-mobile-audit-remediation.md`. S# denote audit requirements, T# denote implementation tasks. Do not tick a task or requirement merely because its pure unit test passes; persisted/HTTP/App/UI proof is required where specified.

## Implementation

- [ ] T1 / S1 — Isolate exam history. Route: delegated-direct (user-selected sequential implementer/reviewer); trigger: explicit method choice. Status: implementation/review committed; Android UI execution pending (no device); commit `f4673e2`.
- [ ] T2 / S2,S4 — Lock the progress contract and time primitives. Depends on T1 implementation/review commit `f4673e2`; its device proof remains pending. Status: committed; Swift execution unavailable; commit `d58a2c9`.
- [ ] T3 / S3 — Fence active sync and enable real iOS App tests. Depends on T2. Evidence/commit: pending.
- [ ] T4 / S4 — Implement negotiated API v2 on the existing route. Depends on T2,T3. Evidence/commit: pending.
- [ ] T5 / S2,S4,S5 — Add durable rollups, recovery and atomic continuation. Depends on T2,T3,T4. Evidence/commit: pending.
- [ ] T6 / S2,S3,S4,S5 — Wire iOS time-safe complete transport. Depends on T3,T4,T5. Evidence/commit: pending.
- [ ] T7 / S2,S3,S4,S5 — Wire Android time-safe complete transport. Depends on T3,T4,T5,T6. Evidence/commit: pending.
- [ ] T8 / S5 — Integrate effective progress reads and upload feedback. Depends on T5,T6,T7. Evidence/commit: pending.
- [ ] T9 / S6 — Make Android topic selection transactional. Depends on T3. Evidence/commit: pending.
- [ ] T10 / S7,S8,S9 — Bound voice capture and unify live turn context. Depends on T9. Evidence/commit: pending.
- [ ] T11 / S10 — Expose accessible manual microphone mute. Depends on T10. Evidence/commit: pending.
- [ ] T12 / S11,S12,S13 — Make iOS pronunciation private and recoverable. Depends on T3. Evidence/commit: pending.
- [ ] T13 / S14 — Preserve typed replies across failure and retry. Depends on T3. Evidence/commit: pending.
- [ ] T14 / S15 — Gate topic search by real engine capability. Depends on T13. Evidence/commit: pending.
- [ ] T15 / S16 — Notify session adoption before profile loading. Depends on T3,T14. Evidence/commit: pending.
- [ ] T16 / S1–S16 — Verify the complete remediation and report gaps. Depends on T1–T15. Evidence/commit: pending.

## Completion rules

- T2 locks contract/primitives; T5 performs persisted recovery and creates rollup storage **before** client page writes in T6/T7. T8 integrates effective score reads/uploads without new local events.
- T13 typed reply, T14 topic capability and T15 session adoption are separate reviewable deliverables; T16 is final evidence.
- Full S2 depends on T2,T5,T6,T7; full S4 on T2–T7 plus v2-enabled local integration; full S5 on T5–T8. All other S# use the plan's linked scenarios.
- Required proof unavailable means implemented/verification pending, not complete. `make test-api` must execute PostgreSQL cases; SwiftPM does not compile App; `make ios-test` must include the newly added AppTests target.
- Parent commits only frozen reviewed bytes. No worker git mutations, push, PR, worktree or real provider/device operation outside the authorized local test scope.

## Review log

- 2026-10-25: User requested review and correction of design, specification and tasks. Corrected protocol negotiation/timestamp loss, persistence ordering, real exporter/test targets, retry wording and task independence. All implementation tasks remain pending; see verify-report.md for document-only checks.
- 2026-10-25: T1 code passes focused and full Android unit suites; independent review approved after one correction batch. Android UI source compiles, but connected execution is pending because `adb devices -l` lists no devices and `:app:connectedUiTestAndroidTest` reports “No connected devices!”. Keep S1 unverified until device execution.
- 2026-10-25: T2 S2/S4 contract/time primitives implemented and independently approved after one correction batch. `make check` (146 tests), exporter tests, Android focused/full unit suites passed; the first full Android run had one non-reproducible `LocalVoiceTransportTest` assertion (`cloud turn processed`), which passed in isolation and on a second full run (731 tests, 0 failures). Swift is unavailable, so `swift test --package-path apps/ios` remains unverified.
