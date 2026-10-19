# iOS exam recovery requirements

## Requirements

### Requirement: Frozen attempt start

The app MUST persist the exact bundled or generated exam snapshot and attempt atomically before showing the first question. A resumed or historical attempt MUST load its own stored snapshot and MUST NOT be recomposed from the current content bank, week, or server response.

#### Scenario: Start from a fetched exam

- GIVEN a valid exam snapshot returned by the API
- WHEN the learner starts it
- THEN the app stores the complete snapshot, immutable identity/version metadata, attempt, and initial outbox event before presenting question one

#### Scenario: Resume after process termination

- GIVEN an in-progress local attempt
- WHEN the app reopens it by attempt ID
- THEN it restores the original snapshot, answers, position, and pending/sync state even if the current exam content has changed

### Requirement: Historical review

The app MUST display the stored original answers, per-question verdicts, explanations, and result for a completed attempt. It MUST NOT re-grade old attempts using a changed engine or silently update their original result.

#### Scenario: Review a completed attempt

- GIVEN a completed attempt in local or restored history
- WHEN the learner opens review
- THEN the app displays the original immutable questions and recorded grading/result

### Requirement: Account-bound synchronization and restoration

The app MUST send an attempt only with its captured owning account session and MUST restore server-confirmed attempts only for the same signed-in account. A failed or interrupted sync MUST preserve pending outbox state. A divergent server state MUST be shown as an explicit conflict and MUST NOT be merged automatically.

#### Scenario: Account changes during pending sync

- GIVEN an outbox row owned by account A and a current session for account B
- WHEN synchronization runs
- THEN the app sends no account A attempt under account B credentials

#### Scenario: Restore on a clean installation

- GIVEN server-confirmed attempts owned by the authenticated account
- WHEN the learner requests history restoration on a clean installation
- THEN the app imports bounded pages transactionally and restores each stored snapshot and attempt by stable identity

#### Scenario: Offline-only changes

- GIVEN an attempt with unuploaded local edits
- WHEN the device is lost before successful server acknowledgement
- THEN those edits are not presented as recoverable from another device
