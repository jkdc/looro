# Design

## Architecture

`LearningStore` remains the app-facing owner of local persistence. Add narrow methods that delegate snapshot/attempt transactions to `ExamHistoryDao`; do not expose `SqlDatabase` to SwiftUI views. A level-test session is initialized from the exact bundled or remote snapshot before the first question is shown. The snapshot associated with an existing attempt is always loaded by attempt identity.

`RootView`/`CoursesViews` provide entry and navigation. Dedicated SwiftUI screens render placement, an in-progress exam, result, history, and review from typed state supplied by the store. Grading continues to use the Core engine and the persisted original per-question verdict/result.

The account-owned sync coordinator captures one valid `ManagedAccountSession` and drains only rows whose `owner_account_id` matches that session. It uses the durable creation/event APIs and compare-and-set acknowledgements already represented in Core. A conflict exposes explicit takeover or fork actions and retains both local and server-confirmed branches until the learner chooses.

Restore is paginated and account-scoped. Every returned record is validated and persisted transactionally before appearing in history. Restore never replaces newer local attempts, imports anonymous rows as account-owned, or changes an attempt's snapshot. The implementation must use an existing server retrieval route if present; any route mismatch is resolved against the API contract before adding a new endpoint.

## Data and lifecycle invariants

- Snapshot JSON, content hash, content/schema/grading versions, source, language, level, and week remain immutable once an attempt starts.
- Start, answer/position save, completion, and outbox enqueue remain atomic through DAO transactions.
- Resume loads the stored attempt and snapshot; the current exam bank/week is irrelevant.
- Unsigned attempts are not sent until explicit ownership attachment; account A data is never sent under account B.
- Sync state distinguishes local/pending, synced, and conflict. Network failure preserves pending rows.
- Restore does not mark data synced without successful server confirmation.

## Verification timing

Write Core/App/UI regression coverage with the implementation. Do not execute Swift tests, xcodebuild, or UI tests before the final post-T14 verification batch (L19). Android/API/script checks that exercise shared contracts may run during implementation. Report each deferred Swift gate separately in the final batch.
