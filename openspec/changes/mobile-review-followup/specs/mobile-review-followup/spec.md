# Mobile review follow-up specification

## ADDED Requirements

### Requirement: Quarantine unattributed fallback (R01)
After database-open or migration failure, clients MUST NOT load, expose, rewrite, delete or assign the shared legacy fallback to the active identity. The previously authorized verified one-time development-data migration may inspect the source during its attempt. Clients MUST offer a non-destructive recovery or retry path.

#### Scenario: Failed migration followed by account change
- **GIVEN** an ownerless fallback archive and a failed database open or migration
- **WHEN** accounts A and B become active and the app restarts
- **THEN** neither account sees or overwrites that archive, and original backup bytes remain intact.

### Requirement: Complete safe progress transport (R02–R08, R18)
Clients MUST honor actual v1 responses and strict negotiated downgrade, exclude invalid individual timestamp contributions before aggregation, sequence active-owner adoption and durable bootstrap eligibility, compare cursor components by tuple ordering, commit each downloaded page with its cursor before requesting the next, and expose recoverable owner-scoped sync status. Existing safe cursor replays MUST remain idempotent.

#### Scenario: Interrupted multi-page and multi-batch sync
- **GIVEN** 1,001 remote identities and 201 local identities including prefix IDs, plus one invalid-date contribution
- **WHEN** a download fails after page one and an upload is interrupted after an acknowledgement
- **THEN** restart resumes from durable progress, all valid identities converge without crossing owners, and the invalid record is retained without blocking valid records.

#### Scenario: Adoption and stale operations
- **GIVEN** a member with no local data and a previous active guest or member
- **WHEN** that member signs in while an older operation is suspended
- **THEN** its identity is activated and loaded before exactly one eligible bootstrap; stale effects and errors cannot affect the new owner.

### Requirement: Bounded Android voice continuity (R11–R14)
Manual voice capture MUST bound memory without silently discarding a valid utterance or submitting it without the user's end-turn action. The greeting instruction and response MUST precede the first tutor turn, and manual microphone mute MUST remain actionable during unrelated pending work.

#### Scenario: Silence then speech near the cap
- **GIVEN** prolonged silence and a subsequent spoken phrase in manual mode
- **WHEN** the capture reaches its limit or the learner ends the turn
- **THEN** the preserved prefix reaches STT or a recoverable accessible notice explains the limit; no phantom or automatic manual turn is generated.

### Requirement: Safe iOS interaction and resources (R09–R10, R15–R17, R19)
The App and AppTests targets MUST compile. Pending typed replies MUST preserve later edits and expose in-flight state. A rejected credential MUST be invalidated even if persistent deletion fails. A pronunciation attempt MUST NOT persist or callback after owner/data generation changes. Audio-session activation MUST have explicit ownership and safe release.

#### Scenario: Pronunciation result arrives after account change
- **GIVEN** an active pronunciation attempt for account A
- **WHEN** the active account or data generation changes before recognition completes
- **THEN** the stale result changes neither B's data nor the current UI callbacks.

#### Scenario: Credential deletion fails
- **GIVEN** a rejected managed-account session and a failing Keychain removal
- **WHEN** the session is invalidated and the app restarts
- **THEN** no consumer readopts the rejected credential; the user receives a recoverable error without secrets.
