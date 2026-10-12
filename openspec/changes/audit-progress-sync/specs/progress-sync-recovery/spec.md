# Progress Sync Recovery Specification

## Purpose

Define safe timestamp recovery and a separate synchronized-rollup projection so contaminated data can be repaired or quarantined idempotently while cross-device scores converge without inventing local learning events.

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

Each native client MUST inspect previously persisted timestamps that may contain Unix milliseconds in an internal-seconds field. A value MAY be repaired only when interpreting it as Unix milliseconds yields an instant within the supported history window and no later than the accepted future-skew boundary. Recovery MUST record a version or equivalent marker so the same value is not repaired repeatedly.

#### Scenario: A safely identifiable contaminated value is repaired once

- GIVEN a persisted timestamp whose Unix-millisecond interpretation is plausible and within the recovery window
- WHEN the recovery pass runs
- THEN the value is converted to the internal domain
- AND the recovery marker records the applied recovery version
- AND running recovery again leaves the value and marker unchanged

#### Scenario: An ambiguous value is quarantined

- GIVEN a persisted timestamp whose possible interpretations are outside the supported history or future-skew bounds
- WHEN the recovery pass runs
- THEN the value is excluded from upload
- AND a bounded diagnostic identifies the quarantine without inventing a replacement timestamp
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

### Requirement: Read models combine local and synchronized projections monotonically

Progress and score reads MUST combine local event-derived rollups with synchronized rollups for the same identity. The combined first-at MUST be the earliest known value, and last-at, attempts, and best-score MUST be the greatest known values. The combined projection MUST be independent of merge order and repeated application.

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

The recovery pass and synchronized-rollup projection MUST NOT silently change unrelated local history/archive retention caps or the local-erasure contract. Any rollup data included in an explicitly defined erasure operation MUST follow that operation's documented scope.

#### Scenario: Recovery does not bypass history limits

- GIVEN a client with an existing local archive retention limit
- WHEN timestamp recovery and rollup merge run
- THEN the retention limit remains unchanged
- AND no additional local completion history is fabricated to preserve a remote aggregate

#### Scenario: Explicit erasure removes synchronized data according to its contract

- GIVEN an erasure operation whose documented scope includes synchronized progress
- WHEN the operation completes
- THEN synchronized rollups in that scope are removed or reset as documented
- AND subsequent synchronization follows the documented post-erasure behavior rather than restoring hidden local events
