# Progress Sync Protocol Specification

## Purpose

Define a versioned, bounded, replay-safe progress synchronization contract that can transfer every progress identity across the API and native clients without treating a single pass as a transaction snapshot.

## ADDED Requirements

### Requirement: Progress timestamps use one wire unit

The progress API MUST represent every timestamp in the versioned wire contract as a safe-integer Unix timestamp in milliseconds. The contract MUST state the epoch and unit explicitly, and clients MUST preserve the represented instant when converting between the wire domain and their Apple-reference-seconds internal time domain. Conversion MUST use the 978307200-second epoch offset exactly once and round to the nearest millisecond explicitly when encoding.

#### Scenario: A shared instant round-trips through the wire contract

- GIVEN an aggregate whose first and last instants are known exactly
- WHEN the API serializes it and a native client decodes, stores, and later uploads it
- THEN both wire timestamps represent the original instants in Unix milliseconds
- AND the client MUST NOT multiply, divide, or offset the wire value more than once

#### Scenario: Boundary and zero timestamps remain unambiguous

- GIVEN timestamps at the supported lower boundary and at the Unix epoch
- WHEN pure native conversion round-trips the Unix epoch and progress validation checks the epoch-zero record
- THEN the conversion preserves Unix epoch zero as Apple-reference second -978307200
- AND the progress API rejects zero because it is earlier than the supported history boundary
- AND the contract distinguishes milliseconds from seconds without relying on magnitude heuristics

#### Scenario: Distinct progress instants retain millisecond precision

- GIVEN firstAt=1792929599000 and lastAt=1792929600000
- WHEN each native time primitive converts the values to Apple-reference seconds and back
- THEN both wire values remain unchanged and the local values remain one second apart

### Requirement: Downloads expose a versioned paged envelope

The progress download endpoint MUST return a versioned envelope containing progress entries, an opaque continuation cursor, and an explicit completion signal. A completed response MUST be distinguishable from a response that has more pages, and the contract MUST define the maximum number of entries in one response.

#### Scenario: A small complete collection finishes in one response

- GIVEN an account with no more entries than the page limit
- WHEN a client requests the first page
- THEN the response contains every matching identity
- AND the completion signal indicates that no continuation request is required

#### Scenario: A large collection advertises continuation

- GIVEN an account with more than 1,000 progress identities, including multiple languages
- WHEN a client requests pages until completion
- THEN every identity is returned exactly as part of the paged collection
- AND each non-final response provides an opaque cursor and indicates that more data remains

#### Scenario: Cursor values are treated as opaque

- GIVEN a cursor returned by the API
- WHEN a client requests the next page
- THEN the API interprets the cursor according to the active protocol version
- AND clients MUST NOT need to parse, construct, or modify cursor contents

### Requirement: Download ordering is deterministic by immutable identity

The API MUST order each download pass by the stable progress identity `(kind, itemID, language)` using a deterministic tie-breaker. Ordering MUST NOT depend solely on mutable timestamps, and the same dataset and cursor MUST produce a repeatable page boundary.

#### Scenario: Timestamp ties do not duplicate or skip identities

- GIVEN adjacent identities with equal timestamps and distinct kinds, item IDs, or languages
- WHEN a client traverses the complete pass
- THEN every identity appears once in the union of pages
- AND no identity is skipped because of the timestamp tie

#### Scenario: Replaying a page is harmless

- GIVEN a client repeats a request with the same cursor after receiving a page
- WHEN the API returns that page again and the client merges it
- THEN the resulting progress state is unchanged after the second merge
- AND the client can continue from the previously acknowledged cursor

### Requirement: Uploads are bounded, exhaustive, and acknowledgement-driven

Native clients MUST upload all eligible progress identities in deterministic identity order using batches of no more than 200 entries. A client MUST advance durable upload resume state only after the corresponding server acknowledgement succeeds, and MUST restart a completed pass so changes made during an earlier pass are eligible for a later pass.

#### Scenario: More than one batch reaches the server

- GIVEN at least 201 eligible local identities
- WHEN the client completes an upload pass
- THEN every eligible identity has been acknowledged in one or more batches
- AND no request contains more than 200 entries

#### Scenario: An interrupted batch resumes without starvation

- GIVEN a pass interrupted before acknowledgement of a batch
- WHEN the client restarts synchronization
- THEN it retries the unacknowledged batch or an equivalent identity range
- AND later identities are eventually uploaded after successful acknowledgements

#### Scenario: A completed pass allows later changes

- GIVEN a pass reaches its completion marker and a new or changed identity appears afterward
- WHEN the next pass begins
- THEN the new or changed identity is eligible for upload
- AND the client MUST NOT treat the previous completion marker as a permanent global cutoff

### Requirement: Progress synchronization is monotonic and eventually convergent

The API and clients MUST merge progress aggregates monotonically: first-at values use the earliest known instant, while last-at, attempts, and best-score values use the greatest known value. A synchronization pass MUST promise eventual recovery across repeated completed passes, but MUST NOT promise transaction-wide snapshot isolation.

#### Scenario: A mutation behind a stateless cursor converges later

- GIVEN an insert or update occurs behind the current cursor while a download pass is in progress
- WHEN the current pass completes and a subsequent full pass completes
- THEN the later pass can return the missed identity or updated aggregate
- AND the contract makes no claim that the first pass was a snapshot of one instant

#### Scenario: Replay and reversed merge order remain monotonic

- GIVEN local and remote aggregates for the same identity
- WHEN either aggregate is merged repeatedly or the merge order is reversed
- THEN the resulting aggregate is the same monotonic maximum/minimum combination
- AND no previously known progress value is lost

### Requirement: Protocol rollout remains compatibility-contained

V2 MUST use the existing `/v1/progress` route: GET negotiates with `version=2`, and POST uses `{version:2,entries:[...]}`. V2 MUST be independently disabled by `PROGRESS_SYNC_V2_ENABLED`, which defaults to false; an authenticated v2 request while disabled returns HTTP 503 and `{error:{code:"progress_sync_version_unavailable"}}`. Only this exact status and code permit one corrected-v1 compatibility pass; authentication, rate-limit, timeout, cancellation, other server errors, malformed responses, and cursor errors MUST NOT trigger fallback. Requests that do not negotiate v2 retain the strict existing v1 request and response shapes. Rollback MUST NOT require destructive schema changes or reverse repaired data.

#### Scenario: An older client receives a supported legacy response

- GIVEN a client that only understands the legacy progress response
- WHEN the service is configured for the compatibility transition
- THEN it receives the unchanged legacy shape
- AND the service continues rejecting implausible future timestamps

#### Scenario: The new protocol is disabled after a regression

- GIVEN an authenticated v2 request is sent while `PROGRESS_SYNC_V2_ENABLED` is false
- WHEN legacy clients resume synchronization
- THEN the response is HTTP 503 with `{error:{code:"progress_sync_version_unavailable"}}`
- AND only the new client may perform one corrected-v1 compatibility pass
- AND synchronized-rollup and recovery data remain intact but are not required by the legacy path
- AND no repaired timestamp or remote aggregate is automatically deleted or reversed
