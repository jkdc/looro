# Local Learning-Data Erasure Specification

## Purpose

Define the cross-platform local-device erasure contract for Android and iOS. A confirmed erase removes covered learning data from active stores and historical artifacts, preserves account and application settings that are outside the contract, and reports the result honestly across restart and retry boundaries.

## ADDED Requirements

### Requirement: Covered learning data and preservation boundary

The system MUST classify sessions and fragments, lesson state, expressions, completions, skill errors, assessment tickets, hidden words, and mastery cache as local learning data covered by the erase operation. The system MUST NOT erase secure credentials, account state, user preferences, schema flags, migration commit markers, or `usage_outbox` as part of this contract.

#### Scenario: Confirmed erase removes every covered category

- GIVEN the local stores contain at least one record in every covered learning-data category
- WHEN the user confirms local learning-data erasure
- THEN the durable local projection contains no record from any covered category
- AND the durable local projection contains no covered learning data reachable through the working archive

#### Scenario: Excluded state is preserved

- GIVEN the local stores contain credentials, account state, preferences, schema flags, migration commit markers, and usage-outbox entries alongside learning data
- WHEN the user confirms local learning-data erasure
- THEN each excluded value remains available with its prior value
- AND the migration system remains in its previously committed state

### Requirement: Durable and atomic deletion

The system MUST delete all covered learning-data rows as one durable local database operation. The system MUST complete that durable deletion before reporting successful erasure or publishing an empty observable archive.

#### Scenario: Database deletion commits before success is published

- GIVEN covered learning data is present in the local database
- WHEN erasure completes successfully
- THEN all covered rows have been durably committed as deleted before success is exposed to the user
- AND reopening the database does not recover any deleted covered row

#### Scenario: Database failure does not claim erasure

- GIVEN the local database cannot commit the covered-data deletion
- WHEN the user confirms local learning-data erasure
- THEN the operation reports failure
- AND the system does not publish a completed-erasure notice
- AND the system does not represent the working archive as durably empty

### Requirement: Historical artifact cleanup preserves migration safety

After the covered database deletion succeeds, the system MUST idempotently remove or sanitize legacy payloads, migrated backups, restore staging copies, and verification copies that could contain covered learning data. The system MUST preserve committed migration markers and MUST NOT leave a surviving payload plus a reset or removed marker that can re-import erased learning data.

#### Scenario: Legacy and staging copies cannot resurrect data

- GIVEN covered learning data exists in an active legacy payload, a migrated backup, and restore or verification staging artifacts
- WHEN local learning-data erasure succeeds and the application restarts
- THEN those artifacts no longer contain importable covered learning data
- AND startup migration or restore does not reintroduce the erased learning data
- AND the committed migration marker remains committed

#### Scenario: Fail-safe legacy storage preserves allowed settings

- GIVEN the platform is using a fail-safe legacy store containing learning data and allowed preferences
- WHEN local learning-data erasure succeeds
- THEN the active legacy representation is replaced by an empty representation that retains the allowed preferences
- AND its migration gate remains in the prior committed state

#### Scenario: Artifact cleanup can be retried

- GIVEN database deletion has committed but an artifact cleanup attempt could not remove every targeted artifact
- WHEN the operation returns
- THEN it reports a failure or incomplete result rather than success
- AND a later retry can safely repeat cleanup without reintroducing learning data or changing preserved state

### Requirement: Quiescence and stale-work invalidation

Before deleting covered data, the system MUST quiesce deferred local writers and invalidate in-flight progress fetches and other stale work that could commit covered learning data. Work started before the erase boundary MUST NOT repopulate the erased local state after the durable deletion succeeds.

#### Scenario: Deferred writer cannot repopulate erased data

- GIVEN a deferred local write containing covered learning data is queued before erasure
- WHEN erasure establishes its boundary and completes
- THEN the queued write is cancelled or rejected as stale
- AND no covered data from that write appears in the local database or working archive

#### Scenario: In-flight progress response is ignored

- GIVEN a remote progress response containing covered learning data is in flight before erasure
- WHEN the response completes after the erase boundary
- THEN the response is ignored as stale
- AND it does not repopulate the erased local state

### Requirement: Observable state follows durable outcome

The system MUST update the observable archive and derived user-interface state only after the durable database deletion and required artifact cleanup have succeeded. On failure, it MUST retain or reload state that accurately reflects durable storage rather than presenting a falsely empty archive.

#### Scenario: Successful erase publishes an empty projection

- GIVEN all covered local data and historical artifacts are cleaned successfully
- WHEN the erase operation returns success
- THEN the observable archive and derived learning-data state are empty
- AND preserved preferences and account state remain observable

#### Scenario: Partial cleanup does not publish an empty success state

- GIVEN database deletion succeeds but required artifact cleanup fails
- WHEN the erase operation returns
- THEN the user receives a failure or incomplete-erasure result
- AND the system does not publish a completed-erasure notice
- AND the observable state is not treated as proof that all historical data was erased

### Requirement: Erasure is local-only

The system MUST define this operation as erasing local-device learning data only. It MUST NOT delete the account, server-held progress, or remote records, and it MUST NOT claim to suppress future legitimate synchronization beyond rejecting stale work that crossed the local erase boundary.

#### Scenario: Remote data is outside the operation

- GIVEN the account has learning progress that remains on the server
- WHEN local learning-data erasure succeeds
- THEN the account and server-held progress remain unchanged
- AND a later legitimate synchronization MAY restore remote progress to the device

#### Scenario: User-facing result states the local boundary

- GIVEN the user completes local learning-data erasure
- WHEN the operation reports success
- THEN the result describes local-device erasure without implying account deletion or remote deletion

### Requirement: Restart and repeated invocation are safe

The system MUST remain in a consistent erased state after process termination and restart. Repeating a successful erase or retrying a failed artifact cleanup MUST be idempotent and MUST NOT delete preserved state or re-arm migration.

#### Scenario: Restart does not restore erased learning data

- GIVEN local erasure has succeeded
- WHEN the application is terminated and restarted
- THEN no covered learning data is loaded from the database, legacy stores, backups, or staging artifacts
- AND preserved credentials, account state, preferences, schema flags, and migration markers are still available

#### Scenario: Repeated erase is idempotent

- GIVEN local erasure has already succeeded
- WHEN the user invokes the erase operation again
- THEN the operation remains successful or reports an equivalent already-erased result
- AND preserved state is unchanged
- AND no new migration or restore is triggered

