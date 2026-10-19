# T09 Verification Report

## Status

Implementation in progress. Swift test execution and all native App/UI gates are deferred to the final verification batch after T01–T14 per user decision L19. This is a verification schedule, not a waiver or pass.

## Current implementation evidence (2026-10-17)

- Added a frozen initial attempt body to the account-owned created-outbox record; later offline edits have separate revisions. A DAO regression test is authored, not executed.
- Added owner-bound outbox drain using the captured ManagedAccountStore session; verifies creation receipts/event ACK identity before marking each exact event acknowledged.
- Progress/completion event payloads now include the required ISO timestamps.
- Event 409 responses persist both branches; sync stops for that attempt. Learn history exposes explicit fork and server-takeover actions.
- Added transactional insert-only remote restore for validated snapshot/attempt data and paginated same-account restore UI. Existing attempt identity collisions (including anonymous attempts) are skipped without overwrite.
- Added DAO regressions for successful restore and anonymous identity collision; authored but intentionally not executed.
- Historical review renders the frozen passage text for passage-linked items and tags it per question for UI verification.
- Remote restore now validates each immutable snapshot through `ExamSnapshotFactory.prepare` and imports the bounded page in one SQLite transaction; malformed cursor types are rejected. Added a mid-page trigger regression for full rollback; authored but intentionally not executed.
- Added an iOS UI regression for starting an English A1 attempt, returning to history, and reopening the same prompt; preview uses an in-memory database. Accessibility identifiers were added for the attempt back action and active prompt. Authored but intentionally not executed.
- Preview mode bypasses the public exam API and uses the bundled snapshot, keeping UI tests isolated from network configuration.
- Docker git diff --check passed after these edits. No Swift build/test/parser/typecheck/Xcode command was run.

These are source-level changes only; acceptance remains pending final native execution and the checks listed below.

## Planned proof

- Core DAO and app-store behavior: immutable start, autosave/resume, original grading, and transactional rollback.
- Account isolation: captured-owner-only sync, no cross-account upload, explicit CAS conflict resolution.
- Restore: same-account, paginated, server-confirmed attempts preserve exact snapshot identity and content.
- Native: `swift test --package-path apps/ios`, App simulator build, and iOS UI tests, executed only in the final supported macOS/Xcode environment.
- Shared API/scripts: run focused Docker checks during implementation when changed behavior affects those contracts.


## Remaining UI proof gaps

- The new preview UI test covers starting and reopening an in-progress attempt. It is authored but not executed.
- Completed historical review, authenticated restore UI, and conflict-resolution UI still need native UI coverage/verification in the deferred final batch.
