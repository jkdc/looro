# Level-test attempt synchronization

## Requirements

### Requirement: Initial attempt creation replay remains stable

The service MUST bind the initial creation receipt to the authenticated owner,
attempt identity, and canonical initial payload. The receipt MUST preserve the
original creation acknowledgement independently from later mutable attempt state.

#### Scenario: Retry creation after another device advances the attempt

- **GIVEN** device A created revision 1 but did not receive its ACK
- **AND** device B advanced the same owner-scoped attempt to revision 2
- **WHEN** device A retries the byte-equivalent initial creation
- **THEN** the service MUST replay the original ACK for revision 1
- **AND** a subsequent stale event from A MUST receive the normal CAS conflict
- **AND** revision 2 MUST remain unchanged

#### Scenario: Attempt identity is reused with different creation data

- **GIVEN** an owner-scoped creation receipt already exists for an attempt ID
- **WHEN** a creation request for that ID has a different canonical payload
- **THEN** the service MUST reject it without changing the attempt or receipt

### Requirement: Effective attempt timestamps obey lifecycle ordering

Clients MUST persist a retry-stable effective timestamp that is not earlier than
the attempt start or the previously persisted effective timestamp. The service
MUST enforce the same ordering when storing a valid event, without changing the
original event bytes used for idempotent replay.

#### Scenario: Device clock moves before attempt start

- **GIVEN** an attempt started at timestamp 100
- **WHEN** the device clock reports timestamp 99 while saving an answer or result
- **THEN** the persisted event timestamp MUST be at least 100
- **AND** retries MUST send the same event payload
- **AND** the database MUST accept the valid transition

### Requirement: A conflict blocks only its attempt

The Android drain MUST preserve per-attempt event order. An unresolved conflict
MUST pause only that attempt; independent attempts for the same owner MUST continue
to drain. The client MUST retain all local events until explicit takeover or fork.

#### Scenario: One attempt conflicts while another is pending

- **GIVEN** attempt A has a stale event and attempt B has a valid pending event
- **WHEN** A receives a confirmed CAS conflict during the drain
- **THEN** A's later events MUST remain pending and unsent
- **AND** B's event MUST still be sent during that drain
- **AND** a later drain MUST not retry A until its conflict is resolved

### Requirement: Fork resolution captures the latest local branch

Fork resolution MUST atomically copy the latest local attempt state and its
immutable snapshot into a new attempt identity. It MUST preserve the source
history, supersede only events represented by the committed fork, and reject
later mutations to the forked source identity.

#### Scenario: Local answer changes after conflict capture

- **GIVEN** a conflict captured a local branch and the learner saved another answer
- **WHEN** the learner explicitly forks that local branch
- **THEN** the new attempt MUST contain the latest answer, position, and result
- **AND** the new attempt MUST reference the original immutable snapshot
- **AND** the source pending events MUST be retired only after fork commit
- **AND** a failed fork MUST leave the source state and events unchanged
