# T09 — iOS exam attempt and recovery lifecycle

## Why

iOS now has the Core exam engines and the shared snapshot/outbox persistence primitives, but the learner cannot start, resume, review, synchronize, or restore an exam through the app. That leaves the cross-device recovery contract incomplete on one platform.

## What changes

Integrate immutable bundled/generated snapshots and durable attempts into the iOS learning store and navigation. Add learner-facing placement and weekly exam flows, history/review, owner-bound sync and conflict handling, and account-scoped restoration from server-confirmed attempts.

## Boundaries

- Reuse `LearningStore`, `ExamHistoryDao`, and the existing authenticated attempt endpoints; do not duplicate grading or silently recompose a stored remote snapshot.
- An offline attempt remains local/pending. Only the same authenticated account can restore its server-confirmed history.
- Never drain one account's outbox under another account's session. Conflicts remain explicit; no last-write-wins.
- Preserve existing backup, restore, account deletion, and per-language data-removal behavior.
- Implement Swift code and tests now; per `odd/tasks/weekly-exam-recovery.md` L19, defer all Swift test execution, App builds, and UI tests to the final batch after T01–T14 implementation.

## Success criteria

- Learners can enter placement and weekly exams from Learn, autosave/resume, and inspect the original snapshot and grading in history.
- Server-confirmed attempts can be restored for the same account after reinstall/device change; pending offline-only work is not represented as recoverable.
- Owner switching and sync conflicts cannot leak or overwrite attempt data.
