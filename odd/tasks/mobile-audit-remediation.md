# Mobile audit remediation — implement all 16 audit findings using SDD/Superpowers.
Branch: `feature/learning-platform`; delivery: sequential implementer/reviewer phases; worktree changes forbidden.
Runner: `make check`, `make test-api`, offline Gradle, SwiftPM/Xcode only where available; no Gentle AI.

## Specs

1. **S1 — Owner-scoped exam history (M01)**

History queries MUST use the requesting owner, including guest-only NULL ownership. Identity changes MUST immediately clear prior visible summaries and reject late results. Existing foreign-owner detail protections MUST remain intact.

#### Scenario: Account isolation

- **GIVEN** stored attempts owned by A, B and a NULL guest for the same language/level
- **WHEN** each identity requests history
- **THEN** only its rows are returned and guest rows are not reassigned.

#### Scenario: Late history response

- **GIVEN** an A-owned load is suspended and the UI changes to B or guest
- **WHEN** the A response completes
- **THEN** old summaries remain absent and the current identity can load its own history.

2. **S2 — Time-safe progress and recoverable legacy data (M02)**

Wire timestamps MUST be safe-integer Unix milliseconds; both native progress domains MUST use Apple-reference seconds, converting exactly once with offset 978307200 seconds and explicit millisecond rounding. V2 MUST preserve distinct firstAt and lastAt. Recovery MUST require persisted source/version evidence, retain unresolved records unchanged, exclude only invalid records from sync and remain transactionally idempotent. A plausible magnitude alone MUST NOT authorize repair.

#### Scenario: Distinct instants survive persistence

- **GIVEN** firstAt=1792929599000 and lastAt=1792929600000, plus an iOS local completion at the latter instant
- **WHEN** each client decodes, persists, reopens and uploads to a fresh v2 ledger
- **THEN** both instants remain unchanged to millisecond precision and the local completion uses Apple-reference seconds.

#### Scenario: Ambiguous legacy record

- **GIVEN** one valid record, one provably contaminated record and one ambiguous record
- **WHEN** recovery runs twice or fails before commit
- **THEN** only the provable value is repaired; ambiguous source data is preserved, valid sync continues, and replay/rollback produces no extra changes.

3. **S3 — Account and generation fencing (M03)**

Sync MUST own one operation per coordinator and bind it to account, credential and data generation. It MUST compare live identity after suspension and under the same write authority as a transaction or upload snapshot. Account change/import MUST invalidate stale work, including cursor/ack writes. Cancellation MUST NOT become an ordinary retryable error.

#### Scenario: Replacement during fetch

- **GIVEN** a fetch under A is suspended
- **WHEN** account changes to B or the database is imported, then A completes
- **THEN** replacement memory, database and cursor state stay unchanged and no mixed-identity POST occurs.

#### Scenario: Replacement during acknowledgement

- **GIVEN** a POST is awaiting acknowledgement
- **WHEN** generation changes before acknowledgement and another sync is requested
- **THEN** the old acknowledgement does not advance new resume state and only a current-generation writer proceeds.

4. **S4 — Bounded compatible resumable protocol (M04)**

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

5. **S5 — Remote aggregate projection without invented history (M05)**

Account/language scoped rollups MUST merge firstAt by minimum and lastAt/attempts/bestScore by maximum, not sum. Score, mastery, completion reads and future uploads MUST use the same effective aggregate without creating local events, sessions or streaks. Existing unknown-origin legacy completion marks MUST remain unchanged. Unresolved legacy-language rollups MUST NOT be assigned to an arbitrary active language.

#### Scenario: Monotonic score and upload

- **GIVEN** local score 40 and remote score 90 for one identity
- **WHEN** merge occurs in either order or repeatedly and the result is displayed/uploaded
- **THEN** effective score is 90; all supported kinds use the same rule, and event/session/activity counts do not increase.

#### Scenario: Owner and language boundary

- **GIVEN** other-owner rollups and same item IDs in another language or unresolved legacy language exist
- **WHEN** the active read model is evaluated
- **THEN** only permitted owner/language evidence contributes and old completion marks remain byte-for-byte unchanged.

6. **S6 — Confirmed Android topic transition (M06)**

Selection MUST remain pending until confirmation. Cancel/dismiss MUST preserve title, prompt, history and active session. Confirmation MUST await one terminal close/settlement and start exactly one chosen conversation. Failure/timeout or stale intent MUST NOT trigger an unguarded start or fixed-delay workaround.

#### Scenario: Cancel is a no-op

- **GIVEN** a conversation is active and another topic is selected
- **WHEN** the confirmation is dismissed
- **THEN** session identity, topic, history and instructions remain unchanged.

#### Scenario: Delayed close and duplicate confirmation

- **GIVEN** close/settlement is delayed, fails or times out
- **WHEN** confirm is repeated or account/navigation changes
- **THEN** at most one valid restart occurs after successful close; invalidated transitions never restart.

7. **S7 — Bounded Android voice capture (M07)**

Pre-speech, active speech, manual capture and frame handoff MUST have explicit sample/time bounds specified in the implementation brief. Overflow MUST have deterministic tested behavior, preserve the first utterance syllable/preroll and not create silence/noise turns.

#### Scenario: Long silence then speech

- **GIVEN** several minutes of deterministic silent frames precede an utterance
- **WHEN** the utterance is captured
- **THEN** retained samples never exceed the bound and the complete utterance beginning reaches STT.

#### Scenario: Manual overflow and cancellation

- **GIVEN** capture approaches the chosen bound or is muted/cancelled
- **WHEN** more frames arrive or capture mode changes
- **THEN** memory remains bounded, jobs close and no old buffered audio becomes a later turn.

8. **S8 — Live safe barge-in (M08)**

Capture/detection MUST remain independent of serial STT/LLM/TTS processing. With supported echo handling, sustained user speech MUST stop ordinary voice-originated reply playback before playback is externally released. Silence, playback echo and discarded interruption frames MUST NOT become phantom turns. Devices without usable echo cancellation MUST retain their safe playback policy.

#### Scenario: Voice-originated reply can be interrupted

- **GIVEN** a spoken turn produces a fake reply whose TTS is blocked
- **WHEN** sustained interrupting speech arrives
- **THEN** tts.stop occurs before releasing TTS and the interruption is not later transcribed as a new turn.

#### Scenario: No false interruption

- **GIVEN** only silence/echo arrives, or echo cancellation is unavailable
- **WHEN** TTS plays
- **THEN** no false user turn occurs and the documented safe device behavior remains.

9. **S9 — Exactly-once bounded tutor context (M09)**

The exact opening assistant text and accepted voice/typed turns MUST enter one bounded role-aware history in order exactly once. Cancelled or stale operations MUST NOT append successful assistant replies.

#### Scenario: Opening antecedent

- **GIVEN** the tutor opens with a unique question
- **WHEN** the learner replies and later switches between typed and voice turns
- **THEN** the next request contains the opening once and preserves message roles/order.

#### Scenario: Cancellation and truncation

- **GIVEN** history reaches its cap and a reply is cancelled
- **WHEN** another valid request is made
- **THEN** history stays bounded and excludes stale/duplicate assistant replies.

10. **S10 — Accessible manual microphone mute (M10)**

Manual mode MUST expose a separate mute control with English/Spanish strings, existing Compose tags, state description and a minimum 48 dp touch target. Mute MUST discard microphone input without ending the session, stopping tutor output or replaying buffered muted audio on resume.

#### Scenario: Manual control

- **GIVEN** manual mode is active
- **WHEN** the control is focused and activated
- **THEN** its accessible label/state changes and the session and tutor output continue.

#### Scenario: Mute and resume

- **GIVEN** frames arrive while muted
- **WHEN** the user unmutes
- **THEN** those frames never reach STT/LLM then or later.

11. **S11 — Localized iOS speech permission (M11)**

Every built target requesting Speech authorization MUST contain accurate localized NSSpeechRecognitionUsageDescription and microphone usage descriptions. Source-file checks MUST NOT be represented as built-bundle/UI proof.

#### Scenario: Built permission declaration

- **GIVEN** an app bundle is built for every supported localization
- **WHEN** permission keys are inspected and Check is opened on a clean test install
- **THEN** Speech and microphone explanations are present and authorization does not terminate the app.

12. **S12 — Recoverable iOS pronunciation lifecycle (M12)**

Explicit request-scoped states MUST gate permission, recording, recognizing, result, failure and unavailability. One idempotent terminal path MUST cancel task/timer/recorder, invalidate callbacks, delete a unique temporary file and release only owned audio resources. Denial/error/timeout/navigation/background MUST settle busy state without false scoring and permit a later retry.

#### Scenario: Every error settles

- **GIVEN** permission is denied/restricted, recording fails, recognition yields (nil,error), or timeout occurs
- **WHEN** the operation terminates
- **THEN** busy clears, failure stays visible, cleanup happens once and no successful score is recorded.

#### Scenario: Navigation and late callback

- **GIVEN** an attempt is active
- **WHEN** navigation/background cancels it and a late callback arrives before a subsequent attempt
- **THEN** the old callback cannot mutate lesson results and shared conversation/TTS audio is not deactivated unilaterally.

13. **S13 — On-device-only pronunciation (M13)**

The adapter MUST check recognizer availability and supportsOnDeviceRecognition and set requiresOnDeviceRecognition on every request. Unsupported locale/model or local error MUST return a non-scored listen-and-repeat fallback, never a silent cloud request.

#### Scenario: Unsupported local model

- **GIVEN** local recognition is unsupported or unavailable
- **WHEN** Check is invoked
- **THEN** no recognition request/audio upload occurs and the learner can use non-scored practice.

#### Scenario: Supported local model

- **GIVEN** local recognition is supported
- **WHEN** a request is created and tested offline on a suitable device
- **THEN** local-only mode is required and no network fallback is introduced.

14. **S14 — Recoverable typed reply (M14)**

Successful generation MUST close the editor with one local user message. Network/HTTP failure MUST keep editor, draft, focus and persistent error. Manual retries MUST reuse the same pending message identity with one concurrent generation, not one lifetime retry. Uncertain provider calls MUST NOT auto-retry; no external provider exactly-once/cost guarantee is claimed. Explicit dismissal MUST invalidate local continuation.

#### Scenario: Failure then repeated manual retry

- **GIVEN** a send fails twice before succeeding
- **WHEN** the learner retries explicitly and also double taps
- **THEN** draft/focus persist until success, one local message exists, and only one generation runs concurrently.

#### Scenario: Late response after close

- **GIVEN** generation is active
- **WHEN** the sheet is dismissed or the conversation/account changes
- **THEN** late response cannot dismiss a newer sheet or claim success for the old conversation.

15. **S15 — Truthful topic-search capability (M15)**

Fresh source-backed topic search MUST check the current engine capability before a provider call. Unsupported engines MUST explain the limitation and preserve valid cached topics. Capable engines MUST retain successful search and genuine network retry behavior; proxy, billing and credentials MUST remain unchanged.

#### Scenario: Unsupported engine with or without cache

- **GIVEN** the engine cannot return required sources
- **WHEN** the learner opens/searches the library
- **THEN** zero provider calls occur, limitation is explained and any valid cached topics remain usable.

#### Scenario: Capable engine failure

- **GIVEN** the selected engine supports source-backed search
- **WHEN** search succeeds or a network failure is retried
- **THEN** existing results and genuine retry remain functional without capability misclassification.

16. **S16 — Session adoption independent of profile (M16)**

Secure persistence and in-memory adoption MUST complete before one adoption notification, independent of profile lookup. A profile 500 MUST retain the adopted session with recoverable error. Actual credential rejection MUST follow existing symmetric invalidation. Notifications MUST NOT be the only S3 stale-operation check.

#### Scenario: Profile unavailable after exchange

- **GIVEN** exchange and secure persistence succeed but profile returns 500
- **WHEN** a bearer-dependent helper is used before conversation start
- **THEN** the adopted bearer is already visible, adoption is notified once and the session remains persisted.

#### Scenario: Refresh and invalid credential

- **GIVEN** adoption has been notified
- **WHEN** profile refresh succeeds or credentials are genuinely rejected
- **THEN** refresh does not duplicate adoption and rejection emits the appropriate invalidation transition.

## Tasks

Preserve the pre-existing untracked `.playwright-mcp/` and `informe-revision-app-movil-2026-10-25.md` unchanged.

- T1 — linked S1; delegated-direct (sequential fresh implementer then reviewer; explicit user-selected method); status: committed; connected UI verification pending; commit: `f4673e2`.
- T2 — linked S2; delegated-direct (sequential fresh implementer then reviewer; explicit user-selected method); status: planned; commit: pending.
- T3 — linked S3; delegated-direct (sequential fresh implementer then reviewer; explicit user-selected method); status: planned; commit: pending.
- T4 — linked S4; delegated-direct (sequential fresh implementer then reviewer; explicit user-selected method); status: planned; commit: pending.
- T5 — linked S4; delegated-direct (sequential fresh implementer then reviewer; explicit user-selected method); status: planned; commit: pending.
- T6 — linked S2, S4; delegated-direct (sequential fresh implementer then reviewer; explicit user-selected method); status: planned; commit: pending.
- T7 — linked S2, S4; delegated-direct (sequential fresh implementer then reviewer; explicit user-selected method); status: planned; commit: pending.
- T8 — linked S5; delegated-direct (sequential fresh implementer then reviewer; explicit user-selected method); status: planned; commit: pending.
- T9 — linked S6; delegated-direct (sequential fresh implementer then reviewer; explicit user-selected method); status: planned; commit: pending.
- T10 — linked S7, S8, S9; delegated-direct (sequential fresh implementer then reviewer; explicit user-selected method); status: planned; commit: pending.
- T11 — linked S10; delegated-direct (sequential fresh implementer then reviewer; explicit user-selected method); status: planned; commit: pending.
- T12 — linked S11, S12, S13; delegated-direct (sequential fresh implementer then reviewer; explicit user-selected method); status: planned; commit: pending.
- T13 — linked S14; delegated-direct (sequential fresh implementer then reviewer; explicit user-selected method); status: planned; commit: pending.
- T14 — linked S15; delegated-direct (sequential fresh implementer then reviewer; explicit user-selected method); status: planned; commit: pending.
- T15 — linked S16; delegated-direct (sequential fresh implementer then reviewer; explicit user-selected method); status: planned; commit: pending.
- T16 — linked S1–S16; delegated-direct (sequential fresh implementer then reviewer; explicit user-selected method); status: planned; commit: pending.

## Log

- L1 (2026-10-25): “implementa todo lo encontrado en el documento generado [informe-revision-app-movil-2026-10-25.md](informe-revision-app-movil-2026-10-25.md) utiliza sdd superpowers para ello”
- L2 (2026-10-25): “ok, adelante” — baseline design approval and selection of sequential implementer/reviewer phases.
- L3 (2026-10-25): “revisa el diseño que has planteado junto con la especificación y las tareas, si encuentras algo que no esté bien actualizalo” — review/correct planning artifacts only.
- L4 (2026-10-25): “cuando hayas terminado implementa los cambios” — explicit authorization to execute the corrected plan.
- L5: Begin T1 S1. The plan and OpenSpec tasks are canonical; this full feature document is the Engram recovery mirror. Parent owns git and commits reviewed bytes.
- L6 (2026-10-25): T1 implements requesting-owner SQL isolation, guest-only NULL semantics, owner-scoped legacy summary display, and generation/request-sequence fencing. Focused and full Android unit tests pass; UI test source compiles; connected UI test unavailable because no emulator/device is attached. Independent review approved after one correction batch; no device proof is claimed. Commit: `f4673e2`.
