# Proposal: Complete Mobile Audit Remediation

**Status:** Baseline design approved on 2026-10-25; document corrections requested by the user are recorded in `verify-report.md`. T1–T4 are committed; remaining tasks are pending.
**Source of findings:** [`informe-revision-app-movil-2026-10-25.md`](../../../informe-revision-app-movil-2026-10-25.md)

## Intent

Remediate all 16 findings M01–M16 from the mobile audit. Protect each learner's exam history and existing progress; make progress sync bounded, resumable and convergent; make voice and speech flows cancellable and recoverable; and stop presenting operations that the selected engine cannot perform.

## Scope

### In scope

- Android exam-history isolation by the requesting account, including anonymous ownership and stale UI results.
- API/iOS/Android progress timestamp conversion, safe recovery of already contaminated data, complete paged transfer, account-bound resume state, and separate remote-rollup projection.
- Android topic-switch confirmation and local voice buffering, barge-in, context, and manual microphone control.
- iOS pronunciation permission, explicit on-device-only capability, error/cancellation/audio-resource lifecycle, typed-reply retry, topic-search capability feedback, and session-adoption notification.
- Focused deterministic regression tests, required API/schema exporters, existing SDD updates, and platform verification available in the environment.

### Out of scope

- New conversation providers, cloud transcription, search-with-sources service, paid-motor fallback, or broader provider-proxy/billing changes.
- New product features unrelated to M01–M16, broad UI redesign, course/content edits, new API consumers, or a security/observability review outside the mobile paths.
- Deleting learning data, silently reassigning guest records to an account, backdating bad timestamps, inventing sessions/streaks from remote aggregates, or weakening identity checks.
- Push, pull requests, merge, release, main-branch work, or unrequested UI for erasure/export.

## Design decisions

1. Implement the complete report scope in the active feature branch, split into the 16 ordered phases in `design.md`.
2. Use sequential Superpowers subagents: one scoped implementer and one fresh reviewer per unit; finish review before starting the next. Parent owns git, shared change docs, evidence and integration; no worktrees or parallel writers.
3. Keep explicit source/domain time types and convert at API boundaries. Version the existing `/v1/progress` route using explicit negotiation, distinct `firstAt`/`lastAt`, deterministic identity pagination and durable per-account acknowledgements; preserve legacy behavior until v2 rollout is deliberately enabled.
4. Keep remote progress aggregates separate from local learning events. Project aggregate scores monotonically without fabricating new local completions, sessions, streaks or activity. Preserve existing completion marks of unknown provenance.
5. Keep pronunciation strictly on-device as the existing proposal promises. If the OS/language cannot honor local recognition, report that capability and fall back to non-scored listen-and-repeat; never fall back to network transcription.
6. Gate optional operations by real engine capabilities. Do not modify provider credentials, billing or proxy source guarantees to make an incompatible operation appear available.
7. Finish every code unit test-first and commit it conventionally once its tests and its task review are clean. Do not push or create a PR.

## Acceptance outcome

Every M01–M16 behavior has a regression demonstrating its original failure before the fix; passive document/configuration checks use explicit structural evidence; data/identity/concurrency changes pass applicable Android, API and database checks; SDD and generated artifacts match the current schema; platform-unavailable checks are identified rather than called green; and each implementation unit has a reviewed commit on the existing feature branch.

## Implementation constraints

- Reconcile `openspec/changes/audit-progress-sync/` with the checked-out schema (`user_version 5`) before taking over its migrations or protocol tasks. Never reuse its stale schema-2-to-3 instructions verbatim.
- Respect current API and persistence compatibility. Make new wire behavior additive, opt-in and safe for rollback; never assume `user_version` must equal a wire-protocol version.
- Preserve Android EN/ES copy and Compose `testTag` contracts. Never edit generated Kotlin/Swift schema/content outputs by hand.
- Keep new state scoped by the account that owns it; discard stale callbacks after account or imported-database generation changes.
- Run all work on the current `feature/learning-platform` branch. Preserve the pre-existing untracked `.playwright-mcp/`; do not clean, overwrite or absorb it.
- This repo requires Superpowers and explicitly excludes Gentle AI workflows; use no Gentle AI command or lifecycle.
- The last audit ran on Linux without a connected Android device or Swift/Xcode. Recheck availability during execution; do not claim native iOS compilation or device/UI proof unless observed.

## Approaches

### A. Repair the legacy sync flow in place

Convert timestamps and update readers while keeping one-shot transfers and event-only local progress. Least implementation cost, but permanently truncated 200/1,000-row sync and fabricated event history would remain. **Rejected** because it does not meet M02–M05.

### B. Add an opt-in, resumable protocol and separate aggregate projection — selected

Preserve v1 until the server explicitly enables v2; add deterministic keyset download pages, acknowledged uploads, durable account-bound cursors, safe timestamp recovery, and remote aggregate tables/projections. More work and compatibility/migration tests, but supports bounded eventual convergence without damaging local histories. This follows the prior progress-sync design's core intent, revised to the current schema.

### C. Reset or replace existing progress data

Simpler wire design, but discards or reinterprets persisted learner progress and is not recoverable for ambiguous timestamps. **Rejected** because it is destructive and outside the user's intent.

## Approval boundary

This proposal and its design companion are planning artifacts; the user approved the design on 2026-10-25. Application changes, new source/schema migrations, dependency installs and task commits remain gated on the user's review of the implementation plan.
