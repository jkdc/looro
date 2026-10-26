# Progress Sync Recovery Specification

## Purpose

Define safe timestamp recovery and a separate synchronized-rollup projection so contaminated device-recorded timestamps can be repaired or retained idempotently while progress is uploaded and later restored by a one-time empty-device bootstrap without inventing local learning events.

## ADDED Requirements

### Requirement: Native boundaries normalize progress time

Native clients MUST convert Unix-millisecond wire timestamps to their internal Apple-reference-second domain only at the API/client boundary, and MUST convert internal values back to Unix milliseconds only when constructing a wire request. Persisted internal timestamps MUST NOT contain raw wire milliseconds.

#### Scenario: Downloaded timestamps are stored in the internal domain

- GIVEN a valid progress response containing Unix-millisecond first-at and last-at values
- WHEN iOS or Android persists the response
- THEN stored timestamps represent the same instants in the platform's internal domain
- AND a subsequent upload emits the original Unix-millisecond values

#### Scenario: Invalid future timestamps are not normalized into false history

- GIVEN a wire timestamp beyond the accepted future-skew boundary
- WHEN a client receives or prepares that value
- THEN it is rejected from normal synchronization
- AND the client does not clamp it into a plausible but false instant

### Requirement: Contaminated timestamps receive bounded idempotent recovery

Each native client MUST require persisted per-record mobile-writer/version and timestamp-unit evidence for the exact field before repairing a contaminated value. This evidence identifies how the device encoded its canonical instant; it MUST NOT be used or required to classify whether data originated on-device or was later restored from the backend. Numeric magnitude, field name, current database schema version, or a store-wide recovery marker alone MUST NOT authorize repair. A proven conversion MUST yield the original mobile-recorded instant inside the supported history and future-skew bounds. Missing or conflicting evidence MUST leave the record and evidence unchanged; only timestamps invalid for the supported internal domain are excluded from upload. Recovery MUST record a completion version or equivalent marker for idempotency, but that marker is not repair evidence.

#### Scenario: Persisted source/version evidence authorizes one repair

- GIVEN a persisted timestamp with per-record source/version evidence identifying the historical writer and Unix-millisecond unit
- WHEN the recovery pass runs
- THEN the value is converted to the internal domain
- AND the per-record evidence records the applied recovery version
- AND the recovery marker records completion without authorizing additional conversions
- AND running recovery again leaves the value and marker unchanged

#### Scenario: An ambiguous value remains unchanged

- GIVEN a persisted timestamp with missing or conflicting source/version evidence
- WHEN the recovery pass runs
- THEN the source value and evidence remain unchanged
- AND the value is excluded from upload only if it is invalid in the supported internal domain
- AND a bounded diagnostic identifies unresolved evidence without inventing a replacement timestamp
- AND running recovery again does not create another repair or diagnostic side effect

### Requirement: Synchronized rollups are stored separately from local events

Clients MUST persist remote aggregate progress in a synchronized-rollup projection keyed by `(kind, itemID, language)`, separately from local completion events, sessions, and history. Synchronization MUST NOT create a fictitious local completion event or learning session merely to display a remote aggregate.

#### Scenario: An unknown remote identity is represented without synthetic history

- GIVEN a remote aggregate for an identity with no local completion event
- WHEN the client merges it
- THEN the synchronized-rollup projection contains the remote aggregate
- AND local completion-event and session counts remain unchanged

#### Scenario: Multi-language identities remain independent

- GIVEN two remote aggregates with the same item ID and kind but different languages
- WHEN the client persists both rollups
- THEN each language has an independent rollup
- AND merging one language does not alter the other language's score or timestamps

### Requirement: Read models combine mobile and bootstrapped projections monotonically

Progress and score reads MUST combine local event-derived rollups with synchronized rollups restored by eligible bootstrap for the same identity. The combined first-at MUST preserve the earliest mobile-recorded instant, and last-at, attempts, and best-score MUST be the greatest known values. The combined projection MUST be independent of merge order and repeated application; subsequent routine syncs upload the effective aggregate but do not download newer backend values.

#### Scenario: A remote score improves a known local score

- GIVEN a local score of 40 and a synchronized remote score of 90 for the same identity
- WHEN the read model is evaluated
- THEN the displayed and future-uploaded best score is 90
- AND no duplicate completion event is appended

#### Scenario: A lower remote aggregate cannot regress local progress

- GIVEN a local best score, attempts, or last-at value greater than the corresponding remote value
- WHEN the remote rollup is merged
- THEN the greater local value remains effective
- AND the merge remains unchanged when repeated or applied in reverse order

### Requirement: Recovery and projection diagnostics are bounded and non-sensitive

Recovery and synchronization diagnostics MUST expose enough bounded information to identify rejected timestamps, incomplete passes, or quarantined identities without retaining raw credentials, transcripts, or unnecessary personal data. Diagnostics SHOULD include a stable operation or identity reference and a reason code.

#### Scenario: A quarantined identity is observable without raw payload retention

- GIVEN a value excluded from upload by recovery validation
- WHEN an operator or client diagnostic view is read
- THEN it exposes a reason code and opaque identity reference
- AND it does not expose credentials or the full raw progress payload

### Requirement: Recovery respects existing retention and erasure boundaries

The recovery pass and synchronized-rollup projection MUST NOT silently change unrelated local history/archive retention caps or the local-erasure contract. Account deletion MUST continue to leave learning history on the device. Explicit delete-all and per-language erasure MUST remove synchronized rollups in the same scope as local completion and lesson-state data; recovery MUST NOT restore erased rollups from hidden local events.

#### Scenario: Recovery does not bypass history limits

- GIVEN a client with an existing local archive retention limit
- WHEN timestamp recovery and rollup merge run
- THEN the retention limit remains unchanged
- AND no additional local completion history is fabricated to preserve a remote aggregate

#### Scenario: Explicit local erasure removes synchronized data in scope

- GIVEN a delete-all or per-language local erasure operation
- WHEN the operation completes
- THEN synchronized rollups for the erased scope are removed in the same transaction as corresponding local completion and lesson-state data
- AND subsequent synchronization does not recreate erased local history from synchronized rollups

#### Scenario: Account deletion preserves local learning data

- GIVEN a user deletes a disposable account
- WHEN server-side account deletion completes
- THEN learning history remains on the device, unchanged by progress-sync recovery or projection
