# Mobile Audit Remediation Specification

## Purpose

Normative S1–S16 acceptance criteria for M01–M16 in `informe-revision-app-movil-2026-10-25.md`, with the decisions and non-goals in `../../design.md`. Task identifiers are separate from requirement identifiers. All scenarios are planned, not executed evidence.

## ADDED Requirements

### Requirement: S1 — Owner-scoped exam history (M01)

History queries MUST use the requesting active identity, including a stable guest identity; NULL ownership is legacy-only and MUST NOT act as a wildcard or active guest. Identity changes MUST immediately clear prior visible summaries and reject late results. Existing foreign-owner detail protections MUST remain intact.

#### Scenario: Account isolation

- **GIVEN** stored attempts owned by A, B and guest identity G for the same language/level
- **WHEN** each identity requests history
- **THEN** only its rows are returned and guest G's rows are not exposed to A or B.

#### Scenario: Late history response

- **GIVEN** an A-owned load is suspended and the UI changes to B or guest
- **WHEN** the A response completes
- **THEN** old summaries remain absent and the current identity can load its own history.

### Requirement: S2 — Time-safe progress and recoverable legacy data (M02)

Wire timestamps MUST be safe-integer Unix milliseconds; both native progress domains MUST use Apple-reference seconds, converting exactly once with offset 978307200 seconds and explicit millisecond rounding. V2 MUST preserve distinct firstAt and lastAt. Recovery MUST require persisted per-record source/version evidence identifying the writer and unit for the exact timestamp field. Pre-v6 completion rows without such evidence MUST remain unchanged and MUST NOT be repaired from timestamp magnitude, field, kind, or database version. New writes that may participate in recovery MUST persist explicit source/version and unit evidence. Missing or conflicting evidence MUST leave the record unchanged; exclude it from sync only if its timestamp is invalid in the supported internal domain. Recovery MUST be transactional and idempotent.

#### Scenario: Distinct instants survive persistence

- **GIVEN** firstAt=1792929599000 and lastAt=1792929600000, plus an iOS local completion at the latter instant
- **WHEN** each client decodes, persists, reopens and uploads to a fresh v2 ledger
- **THEN** both instants remain unchanged to millisecond precision and the local completion uses Apple-reference seconds.

#### Scenario: Evidence-backed recovery preserves ambiguous legacy data

- **GIVEN** one valid record, one contaminated record with persisted source/version and unit evidence, and one pre-v6 completion record without provenance
- **WHEN** recovery runs twice or fails before commit
- **THEN** only the evidence-backed value is repaired; the pre-v6 record remains byte-for-byte unchanged, valid sync continues, and replay/rollback produces no extra changes.

#### Scenario: Explicit legacy lesson-writer evidence is required

- **GIVEN** a `lesson_state.last_seen` row already persisted with the exact tuple `ios.recordLessonAttempt`, version `1`, unit `unix_seconds`, alongside an otherwise identical pre-v6 row with no provenance
- **WHEN** recovery runs
- **THEN** only the row carrying that complete tuple is converted; migrations never add that tuple to historical rows, and a repaired marker prevents a second conversion.

### Requirement: S3 — Account and generation fencing (M03)

Sync MUST own one operation per coordinator and bind it to account, credential and data generation. It MUST compare live identity after suspension and under the same write authority as a transaction or upload snapshot. Account change/import MUST invalidate stale work, including cursor/ack writes. Cancellation MUST NOT become an ordinary retryable error.

#### Scenario: Replacement during fetch

- **GIVEN** a fetch under A is suspended
- **WHEN** account changes to B or the database is imported, then A completes
- **THEN** replacement memory, database and cursor state stay unchanged and no mixed-identity POST occurs.

#### Scenario: Replacement during acknowledgement

- **GIVEN** a POST is awaiting acknowledgement
- **WHEN** generation changes before acknowledgement and another sync is requested
- **THEN** the old acknowledgement does not advance new resume state and only a current-generation writer proceeds.

### Requirement: S4 — Bounded compatible resumable protocol (M04)

V2 MUST use existing /v1/progress with GET version=2 and POST {version:2,entries:[...]}. GET MUST return {version:2,progress,nextCursor,complete}, with at most 1000 rows and complete iff nextCursor is null. POST MUST accept at most 200 entries with kind,itemID,language,firstAt,lastAt,attempts,bestScore, validate both timestamps and acknowledge the whole batch with {merged:N}. The limits and strict existing v1 shape MUST remain separate. PROGRESS_SYNC_V2_ENABLED defaults false; authenticated v2 requests while disabled MUST return HTTP 503 and {error:{code:"progress_sync_version_unavailable"}}. Only that exact result permits one corrected v1 compatibility pass. Stable ASCII/codepoint identity order, validated opaque cursors and atomic rollup/page cursor commits MUST permit replay and later full-pass convergence. Invalid pages MUST leave cursor and stored data unchanged.

#### Scenario: Complete stable dataset

- **GIVEN** 201 upload and 1001 download identities including mixed languages and equal timestamps
- **WHEN** clients traverse and acknowledge all pages
- **THEN** final identity sets match exactly; page bounds hold; replay and restart do not duplicate effects.

#### Scenario: Interrupted or concurrent pass

- **GIVEN** a page apply or ack boundary fails, or a row changes behind the cursor
- **WHEN** the client restarts and later completes another full pass
- **THEN** rollback is atomic, unacknowledged ranges replay, and the later pass converges without an infinite immediate loop.

#### Scenario: Downgrade guard

- **GIVEN** an authenticated explicit v2 request is made
- **WHEN** the server returns the exact disabled status/code, versus auth/rate-limit/timeout/cancel/other-5xx, malformed or legacy-shaped 200, or invalid cursor
- **THEN** only the disabled result permits one compatibility pass without v2 cursor changes; every other failure retains data and is surfaced.

### Requirement: S5 — Remote aggregate projection without invented history (M05)

Account/language scoped rollups MUST merge firstAt by minimum and lastAt/attempts/bestScore by maximum, not sum. Score, mastery, completion reads and future uploads MUST use the same effective aggregate without creating local events, sessions or streaks. Existing unknown-origin legacy completion marks MUST remain unchanged. Unresolved legacy-language rollups MUST NOT be assigned to an arbitrary active language.

#### Scenario: Monotonic score and upload

- **GIVEN** local score 40 and remote score 90 for one identity
- **WHEN** merge occurs in either order or repeatedly and the result is displayed/uploaded
- **THEN** effective score is 90; all supported kinds use the same rule, and event/session/activity counts do not increase.

#### Scenario: Owner and language boundary

- **GIVEN** other-owner rollups and same item IDs in another language or unresolved legacy language exist
- **WHEN** the active read model is evaluated
- **THEN** only permitted owner/language evidence contributes and old completion marks remain byte-for-byte unchanged.

### Requirement: S6 — Confirmed Android topic transition (M06)

Selection MUST remain pending until confirmation. Cancel/dismiss MUST preserve title, prompt, history and active session. Confirmation MUST await one terminal close/settlement and start exactly one chosen conversation. Failure/timeout or stale intent MUST NOT trigger an unguarded start or fixed-delay workaround.

#### Scenario: Cancel is a no-op

- **GIVEN** a conversation is active and another topic is selected
- **WHEN** the confirmation is dismissed
- **THEN** session identity, topic, history and instructions remain unchanged.

#### Scenario: Delayed close and duplicate confirmation

- **GIVEN** close/settlement is delayed, fails or times out
- **WHEN** confirm is repeated or account/navigation changes
- **THEN** at most one valid restart occurs after successful close; invalidated transitions never restart.

### Requirement: S7 — Bounded Android voice capture (M07)

Pre-speech, active speech, manual capture and frame handoff MUST have explicit sample/time bounds specified in the implementation brief. Overflow MUST have deterministic tested behavior, preserve the first utterance syllable/preroll and not create silence/noise turns.

#### Scenario: Long silence then speech

- **GIVEN** several minutes of deterministic silent frames precede an utterance
- **WHEN** the utterance is captured
- **THEN** retained samples never exceed the bound and the complete utterance beginning reaches STT.

#### Scenario: Manual overflow and cancellation

- **GIVEN** capture approaches the chosen bound or is muted/cancelled
- **WHEN** more frames arrive or capture mode changes
- **THEN** memory remains bounded, jobs close and no old buffered audio becomes a later turn.

### Requirement: S8 — Live safe barge-in (M08)

Capture/detection MUST remain independent of serial STT/LLM/TTS processing. With supported echo handling, sustained user speech MUST stop ordinary voice-originated reply playback before playback is externally released. Silence, playback echo and discarded interruption frames MUST NOT become phantom turns. Devices without usable echo cancellation MUST retain their safe playback policy.

#### Scenario: Voice-originated reply can be interrupted

- **GIVEN** a spoken turn produces a fake reply whose TTS is blocked
- **WHEN** sustained interrupting speech arrives
- **THEN** tts.stop occurs before releasing TTS and the interruption is not later transcribed as a new turn.

#### Scenario: No false interruption

- **GIVEN** only silence/echo arrives, or echo cancellation is unavailable
- **WHEN** TTS plays
- **THEN** no false user turn occurs and the documented safe device behavior remains.

### Requirement: S9 — Exactly-once bounded tutor context (M09)

The exact opening assistant text and accepted voice/typed turns MUST enter one bounded role-aware history in order exactly once. Cancelled or stale operations MUST NOT append successful assistant replies.

#### Scenario: Opening antecedent

- **GIVEN** the tutor opens with a unique question
- **WHEN** the learner replies and later switches between typed and voice turns
- **THEN** the next request contains the opening once and preserves message roles/order.

#### Scenario: Cancellation and truncation

- **GIVEN** history reaches its cap and a reply is cancelled
- **WHEN** another valid request is made
- **THEN** history stays bounded and excludes stale/duplicate assistant replies.

### Requirement: S10 — Accessible manual microphone mute (M10)

Manual mode MUST expose a separate mute control with English/Spanish strings, existing Compose tags, state description and a minimum 48 dp touch target. Mute MUST discard microphone input without ending the session, stopping tutor output or replaying buffered muted audio on resume.

#### Scenario: Manual control

- **GIVEN** manual mode is active
- **WHEN** the control is focused and activated
- **THEN** its accessible label/state changes and the session and tutor output continue.

#### Scenario: Mute and resume

- **GIVEN** frames arrive while muted
- **WHEN** the user unmutes
- **THEN** those frames never reach STT/LLM then or later.

### Requirement: S11 — Localized iOS speech permission (M11)

Every built target requesting Speech authorization MUST contain accurate localized NSSpeechRecognitionUsageDescription and microphone usage descriptions. Source-file checks MUST NOT be represented as built-bundle/UI proof.

#### Scenario: Built permission declaration

- **GIVEN** an app bundle is built for every supported localization
- **WHEN** permission keys are inspected and Check is opened on a clean test install
- **THEN** Speech and microphone explanations are present and authorization does not terminate the app.

### Requirement: S12 — Recoverable iOS pronunciation lifecycle (M12)

Explicit request-scoped states MUST gate permission, recording, recognizing, result, failure and unavailability. One idempotent terminal path MUST cancel task/timer/recorder, invalidate callbacks, delete a unique temporary file and release only owned audio resources. Denial/error/timeout/navigation/background MUST settle busy state without false scoring and permit a later retry.

#### Scenario: Every error settles

- **GIVEN** permission is denied/restricted, recording fails, recognition yields (nil,error), or timeout occurs
- **WHEN** the operation terminates
- **THEN** busy clears, failure stays visible, cleanup happens once and no successful score is recorded.

#### Scenario: Navigation and late callback

- **GIVEN** an attempt is active
- **WHEN** navigation/background cancels it and a late callback arrives before a subsequent attempt
- **THEN** the old callback cannot mutate lesson results and shared conversation/TTS audio is not deactivated unilaterally.

### Requirement: S13 — On-device-only pronunciation (M13)

The adapter MUST check recognizer availability and supportsOnDeviceRecognition and set requiresOnDeviceRecognition on every request. Unsupported locale/model or local error MUST return a non-scored listen-and-repeat fallback, never a silent cloud request.

#### Scenario: Unsupported local model

- **GIVEN** local recognition is unsupported or unavailable
- **WHEN** Check is invoked
- **THEN** no recognition request/audio upload occurs and the learner can use non-scored practice.

#### Scenario: Supported local model

- **GIVEN** local recognition is supported
- **WHEN** a request is created and tested offline on a suitable device
- **THEN** local-only mode is required and no network fallback is introduced.

### Requirement: S14 — Recoverable typed reply (M14)

Successful generation MUST close the editor with one local user message. Network/HTTP failure MUST keep editor, draft, focus and persistent error. Manual retries MUST reuse the same pending message identity with one concurrent generation, not one lifetime retry. Uncertain provider calls MUST NOT auto-retry; no external provider exactly-once/cost guarantee is claimed. Explicit dismissal MUST invalidate local continuation.

#### Scenario: Failure then repeated manual retry

- **GIVEN** a send fails twice before succeeding
- **WHEN** the learner retries explicitly and also double taps
- **THEN** draft/focus persist until success, one local message exists, and only one generation runs concurrently.

#### Scenario: Late response after close

- **GIVEN** generation is active
- **WHEN** the sheet is dismissed or the conversation/account changes
- **THEN** late response cannot dismiss a newer sheet or claim success for the old conversation.

### Requirement: S15 — Truthful topic-search capability (M15)

Fresh source-backed topic search MUST check the current engine capability before a provider call. Unsupported engines MUST explain the limitation and preserve valid cached topics. Capable engines MUST retain successful search and genuine network retry behavior; proxy, billing and credentials MUST remain unchanged.

#### Scenario: Unsupported engine with or without cache

- **GIVEN** the engine cannot return required sources
- **WHEN** the learner opens/searches the library
- **THEN** zero provider calls occur, limitation is explained and any valid cached topics remain usable.

#### Scenario: Capable engine failure

- **GIVEN** the selected engine supports source-backed search
- **WHEN** search succeeds or a network failure is retried
- **THEN** existing results and genuine retry remain functional without capability misclassification.

### Requirement: S16 — Session adoption independent of profile (M16)

Secure persistence and in-memory adoption MUST complete before one adoption notification, independent of profile lookup. A profile 500 MUST retain the adopted session with recoverable error. Actual credential rejection MUST follow existing symmetric invalidation. Notifications MUST NOT be the only S3 stale-operation check.

#### Scenario: Profile unavailable after exchange

- **GIVEN** exchange and secure persistence succeed but profile returns 500
- **WHEN** a bearer-dependent helper is used before conversation start
- **THEN** the adopted bearer is already visible, adoption is notified once and the session remains persisted.

#### Scenario: Refresh and invalid credential

- **GIVEN** adoption has been notified
- **WHEN** profile refresh succeeds or credentials are genuinely rejected
- **THEN** refresh does not duplicate adoption and rejection emits the appropriate invalidation transition.

### Requirement: S17 — Account-owned local learner data (user-approved scope extension)

Every learner-data read and write MUST be scoped to the currently active identity, which is always either a signed-in member or a local guest. Switching identities MUST make the prior identity's learning data unavailable to the new identity. Sign-out MUST transition to a guest identity, not leave an ownerless active state. Existing ownerless development data MUST be assigned once to the identity active when the ownership migration runs; fresh installs and newly created accounts MUST not inherit it. Guest data MUST remain isolated when that guest later signs into or registers a member account: no automatic merge, copy, or visibility is allowed. Only member-owned progress is eligible for backend sync; the backend download remains a one-time bootstrap only when a member account has no local data on that device. Archive export/import, reset/delete, history and all learner-data stores MUST preserve these ownership boundaries; device-global cache/install metadata is not learner-owned data.

The storage identity MUST be a namespaced key: `member:<accountID>` or `guest:<installationUUID>`. Every mutable learner-owned row MUST persist that key, including exam attempts; NULL is allowed only transiently for ownerless rows being backfilled by the one-time migration.

#### Scenario: Account switch isolation

- **GIVEN** accounts A and B have local exercises, sessions, preferences and assessment data
- **WHEN** the active identity changes from A to B
- **THEN** B can read and mutate only B-owned data and A's visible state is cleared immediately.

#### Scenario: Existing ownerless development data

- **GIVEN** a pre-migration database contains ownerless learner data and the active identity is A
- **WHEN** the ownership migration runs
- **THEN** the existing rows are assigned to A exactly once, while fresh databases and later accounts receive no copied rows.

#### Scenario: Guest-to-member transition

- **GIVEN** guest G has locally generated progress and the user signs into member A
- **WHEN** the active identity changes
- **THEN** A sees only A-owned data; G's progress remains isolated under G and is not merged, uploaded as A, or exposed to A.

#### Scenario: Active-owner archive projection and whole-install backup

- **GIVEN** local records exist for A, B and guest G
- **WHEN** A reads the app-facing archive projection, exports/restores the whole-install database file, or performs progress sync
- **THEN** the app-facing archive projection contains only A-owned learner data; the whole-install database backup preserves every partition's owner keys; after restore normal reads remain scoped to A; guest progress is local-only and an already-bootstrapped member device does not download backend state again.
