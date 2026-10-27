# Mobile audit remediation — implement all 16 audit findings and account-isolate local learner data using SDD/Superpowers.
Branch: `feature/learning-platform`; delivery: sequential implementer/reviewer phases; worktree changes forbidden.
Runner: `make check`, `make test-api`, offline Gradle, SwiftPM/Xcode only where available; no Gentle AI.

## Specs

1. **S1 — Owner-scoped exam history (M01)**

History queries MUST use the requesting active identity, including a stable guest identity; NULL ownership is legacy-only and MUST NOT act as a wildcard or active guest. Identity changes MUST immediately clear prior visible summaries and reject late results. Existing foreign-owner detail protections MUST remain intact.

#### Scenario: Account isolation

- **GIVEN** stored attempts owned by A, B and guest identity G for the same language/level
- **WHEN** each identity requests history
- **THEN** only its rows are returned and guest G's rows are not exposed to A or B.

#### Scenario: Late history response

- **GIVEN** an A-owned load is suspended and the UI changes to B or guest
- **WHEN** the A response completes
- **THEN** old summaries remain absent and the current identity can load its own history.

2. **S2 — Time-safe progress and recoverable legacy data (M02)**

The instant recorded by the originating mobile device is canonical; the backend MUST preserve and return it unchanged. Wire timestamps MUST be safe-integer Unix milliseconds; both native progress domains MUST use Apple-reference seconds, converting exactly once with offset 978307200 seconds and explicit millisecond rounding. V2 MUST preserve distinct firstAt and lastAt. Recovery MUST use persisted evidence for the mobile writer/version and timestamp unit of the exact field; this is encoding evidence, not classification of local-versus-backend origin. Pre-v6 completion rows without such evidence MUST remain unchanged and MUST NOT be repaired from timestamp magnitude, field, kind, or database version. New writes that may participate in recovery MUST persist explicit writer/version and unit evidence. Missing or conflicting evidence MUST leave the record unchanged; exclude it from sync only if its timestamp is invalid in the supported internal domain. Recovery MUST be transactional and idempotent.

#### Scenario: Distinct instants survive persistence

- **GIVEN** firstAt=1792929599000 and lastAt=1792929600000, plus an iOS local completion at the latter instant
- **WHEN** each client decodes, persists, reopens and uploads to a fresh v2 ledger
- **THEN** both instants remain unchanged to millisecond precision and the local completion uses Apple-reference seconds.

#### Scenario: Evidence-backed recovery preserves ambiguous legacy data

- **GIVEN** one valid record, one contaminated record with persisted source/version and unit evidence, and one pre-v6 completion record without provenance
- **WHEN** recovery runs twice or fails before commit
- **THEN** only the evidence-backed value is repaired; the pre-v6 record remains byte-for-byte unchanged, valid sync continues, and replay/rollback produces no extra changes.

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

V2 MUST use existing /v1/progress with GET version=2 and POST {version:2,entries:[...]}. Mobile devices upload progress generated on that device under the active account identity. GET is a one-time bootstrap for a newly authenticated account on a device that has no local progress for that account; after successful bootstrap, routine syncs MUST NOT download again. GET MUST return {version:2,progress,nextCursor,complete}, with at most 1000 rows and complete iff nextCursor is null. POST MUST accept at most 200 entries with kind,itemID,language,firstAt,lastAt,attempts,bestScore, validate both timestamps and acknowledge the whole batch with {merged:N}. The limits and strict existing v1 shape MUST remain separate. PROGRESS_SYNC_V2_ENABLED defaults false; authenticated v2 requests while disabled MUST return HTTP 503 and {error:{code:"progress_sync_version_unavailable"}}. Only that exact result permits one corrected v1 compatibility pass. Stable ASCII/codepoint identity order, validated opaque cursors and atomic rollup/page cursor commits MUST permit replay of bootstrap pages and later upload convergence. Invalid pages MUST leave cursor and stored data unchanged. Guest accounts remain separate identities per installation for now; their progress is isolated by the guest account ID.

#### Scenario: Complete stable dataset

- **GIVEN** 201 upload and 1001 download identities including mixed languages and equal timestamps
- **WHEN** an empty device bootstraps and uploads its resulting effective progress
- **THEN** final identity sets match exactly; page bounds hold; replay and restart do not duplicate effects.

#### Scenario: Existing device does not download again

- **GIVEN** the account already has local progress or completed its bootstrap on this device
- **WHEN** a later routine sync runs
- **THEN** the client uploads local/effective progress and makes no backend progress download request.

#### Scenario: Interrupted or concurrent pass

- **GIVEN** a page apply or ack boundary fails, or a row changes behind the cursor
- **WHEN** the client restarts and later completes another full pass
- **THEN** rollback is atomic, unacknowledged ranges replay, and the later pass converges without an infinite immediate loop.

#### Scenario: Downgrade guard

- **GIVEN** an authenticated explicit v2 request is made
- **WHEN** the server returns the exact disabled status/code, versus auth/rate-limit/timeout/cancel/other-5xx, malformed or legacy-shaped 200, or invalid cursor
- **THEN** only the disabled result permits one compatibility pass without v2 cursor changes; every other failure retains data and is surfaced.

5. **S5 — Remote aggregate projection without invented history (M05)**

Account/language scoped rollups MUST merge firstAt by minimum and lastAt/attempts/bestScore by maximum, not sum. They represent progress originally recorded on mobile and retained by the backend for a later empty-device bootstrap; the backend MUST NOT invent progress. Score, mastery, completion reads and uploads MUST use the same effective aggregate without creating local events, sessions or streaks. Existing legacy completion marks MUST remain unchanged when their timestamp encoding cannot be proven. Unresolved legacy-language rollups MUST NOT be assigned to an arbitrary active language.

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

17. **S17 — Account-owned local learner data (user-approved scope extension)**

Every learner-data read and write MUST be scoped to the currently active identity, which is always either a signed-in member or a local guest. Persist the namespaced owner key `member:<accountID>` or `guest:<installationUUID>` on each mutable learner-owned row; resolve it before migration. Switching identities MUST make the prior identity's learning data unavailable to the new identity. Sign-out MUST transition to a guest identity, not leave an ownerless active state. Existing ownerless development data MUST be assigned once to the identity active when the ownership migration runs; fresh installs and newly created accounts MUST not inherit it. Guest data MUST remain isolated when that guest later signs into or registers a member account: no automatic merge, copy, or visibility is allowed. Only member-owned progress is eligible for backend sync; the backend download remains a one-time bootstrap only when a member account has no local data on that device. Archive export/import, reset/delete, history and all learner-data stores MUST preserve these ownership boundaries; device-global cache/install metadata is not learner-owned data.

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

#### Scenario: Archive and member-only sync

- **GIVEN** local records exist for A, B and guest G
- **WHEN** A exports/imports an archive or performs progress sync
- **THEN** the archive/sync contains only A-owned learner data; guest progress is local-only and an already-bootstrapped member device does not download backend state again.

## Tasks

Preserve the pre-existing untracked `.playwright-mcp/` and `informe-revision-app-movil-2026-10-25.md` unchanged.

- T1 — linked S1; delegated-direct (sequential fresh implementer then reviewer; explicit user-selected method); status: ownership implementation and Android verification completed through T17; iOS runtime proof deferred to T16; original commit: `f4673e2` remains historical evidence.
- T2 — linked S2,S4; delegated-direct (sequential fresh implementer then reviewer; explicit user-selected method); status: committed; Swift execution pending; commit: `d58a2c9`.
- T3 — linked S3; delegated-direct (sequential fresh implementer then reviewer; explicit user-selected method); status: committed; iOS app execution pending T16; commit: `6a69351`.
- T4 — linked S4; delegated-direct (sequential fresh implementer then reviewer; explicit user-selected method); status: committed; focused PostgreSQL 17/17, `make check` 146, API type-check, fresh full suite 756/756, independent review clean, parent focused spot-check 17/17; commit: `98ae12d`.
- T5 — linked S2,S4,S5; inline continuation of tracked task; status: recovery committed `5dd7da4`; Android focused verification and independent high-risk recheck passed; Swift runtime verification deferred to T16. Untagged pre-v6 rows remain unchanged; checkpoint `07685b5` contains prior rollup/schema work.
- T6 — linked S2, S4; delegated-direct (sequential fresh implementer then reviewer; explicit user-selected method); status: planned; commit: pending.
- T7 — linked S2, S4; delegated-direct (sequential fresh implementer then reviewer; explicit user-selected method); status: planned; commit: pending.
- T8 — linked S5; delegated-direct (sequential fresh implementer then reviewer; explicit user-selected method); status: planned; commit: pending.
- T9 — linked S6; delegated-direct (sequential fresh implementer then reviewer; explicit user-selected method); status: complete; S6 topic transition can proceed independently while T5's evidence-backed historical repair remains unresolved; commit: `442c585`.
- T10 — linked S7, S8, S9; delegated-direct (sequential fresh implementer then reviewer; user-authorized available model `gpt-6-luna`); status: Needs decision after two correction rounds; commit: pending. S8's durable no-phantom-turn regression still lacks a deterministic acknowledgement that the producer has classified/discarded the final interrupt frame. Independent scratch verification passed but does not eliminate that scheduling race.
- T11 — linked S10; delegated-direct (sequential fresh implementer then reviewer; explicit user-selected method); status: planned; commit: pending.
- T12 — linked S11, S12, S13; delegated-direct (sequential fresh implementer then reviewer; user-authorized available model `gpt-6-luna`); status: implemented and scoped review complete; Swift/iOS verification deferred to T16; commit: `c8c2d48`.
- T13 — linked S14; delegated-direct (sequential fresh implementer then reviewer; user-authorized available model `gpt-6-luna`); status: implementation/review committed as `185b152`; Swift/iOS verification deferred to T16; mounted focus-after-failure proof also pending T16.
- T14 — linked S15; delegated-direct (sequential fresh implementer then reviewer; user-authorized available model `gpt-6-luna`); status: implementation/review committed as `e4a5375`; Swift/iOS verification deferred to T16; direct configured-hosted-route test remains a minor follow-up.
- T15 — linked S16; delegated-direct (sequential fresh implementer then reviewer; user-authorized available model `gpt-6-luna`); status: source-verified; Swift/iOS execution deferred to T16; commit: `e545c79`.
- T16 — linked S1–S16; delegated-direct (sequential fresh implementer then reviewer; explicit user-selected method); status: planned; commit: pending.
- T17 — linked S1,S17 and S3–S5; delegated-direct (sequential fresh implementer then reviewer; explicit user-selected method); status: Android implementation and independent high-risk verification complete; iOS runtime proof deferred to T16; depends on resolved active identity before T6–T8 account-scoped persistence; one-time migration of existing ownerless learner data, account-aware stores/archive/import/reset, guest isolation and member-only sync; commit: `07685b5` (includes shared T5 schema/rollup checkpoint; T5 remains partial).

## Log
- L1 (2026-10-25): “implementa todo lo encontrado en el documento generado [informe-revision-app-movil-2026-10-25.md](informe-revision-app-movil-2026-10-25.md) utiliza sdd superpowers para ello”
- L2 (2026-10-25): “ok, adelante” — baseline design approval and selection of sequential implementer/reviewer phases.
- L3 (2026-10-25): “revisa el diseño que has planteado junto con la especificación y las tareas, si encuentras algo que no esté bien actualizalo” — review/correct planning artifacts only.
- L4 (2026-10-25): “cuando hayas terminado implementa los cambios” — explicit authorization to execute the corrected plan.
- L5: Begin T1 S1. The plan and OpenSpec tasks are canonical; this full feature document is the Engram recovery mirror. Parent owns git and commits reviewed bytes.
- L6 (2026-10-25): T1 implements requesting-owner SQL isolation, guest-only NULL semantics, owner-scoped legacy summary display, and generation/request-sequence fencing. Focused and full Android unit tests pass; UI test source compiles; connected UI test unavailable because no emulator/device is attached. Independent review approved after one correction batch; no device proof is claimed. Commit: `f4673e2`.
- L7 (2026-10-25): Begin T2 / S2,S4, using the approved version-2 shared contract and checked cross-platform time primitives. No persisted repair or production activation is authorized by this task.
- L8 (2026-10-25): T2 adds the shared v2 contract, generated Kotlin/Swift constants, checked platform time conversions and provenance-based recovery rules. `make check` (146 tests), 5 exporter tests and Android focused/full suites pass; one unrelated voice assertion failed once, then passed isolated and on a second full suite (731/0). Swift execution is unavailable. Independent review approved; risk item 3 (cross-platform shared contract). Commit: `d58a2c9`.
- L9 (2026-10-25): Begin T3 / S3. New sync operations must be fenced by account, credential and persistent-data generation, including both post-fetch writes and post-ack cursor advancement; no iOS App test target exists yet.
- L10 (2026-10-25): T3's second correction resolves the repeated Android persistence/admission races and adds injected runner/coordinator tests. Fresh independent scratch review passes Android (focused 13; full app 733 + engine-local 11) but finds a new PBX object-graph defect at `apps/ios/App.xcodeproj/project.pbxproj:2`: AppTests target definitions are outside `objects`. Swift/Xcode unavailable. No further correction or commit until user decides whether to authorize another round.
- L11 (2026-10-25): User authorized one additional focused correction (“ok”). Moved the AppTests object definitions into the PBX `objects` dictionary and explicitly enabled `ENABLE_TESTABILITY` for the App Debug configuration. Structural PBX/scheme checks and Android suites pass; fresh independent re-review is pending. Xcode/Swift remains unavailable locally; no T3 commit yet.
- L12 (2026-10-25): Fresh independent re-review confirms the object placement and 109-object graph only after a hypothetical semicolon, but rejects the actual candidate because `objects` closes without `;` before `rootObject`. Android app tests passed 733/0 including 13 T3 tests; engine-local 11/0. This is a new blocker after the authorized correction; no further correction or T3 commit without user authorization.
- L13 (2026-10-25): User authorized the necessary corrections (“sí aplica las correcciones que necesites”) and directed that Swift/iOS app execution be deferred to final verification. Added the missing PBX objects terminator semicolon; strict structural re-review is pending. iOS execution remains pending for T16, not waived. Continue with dependent phases only after T3 structure is accepted.
- L14 (2026-10-25): Fresh scratch structural re-review passes: strict OpenStep parse, 109 PBX objects, AppTests source/product/dependency/config/host references, scheme binding, Debug testability; negative control without the semicolon fails. T3 Android unit suites passed in the immediately prior scratch review (app 733/0 including 13 T3 tests; engine-local 11/0). iOS app execution is deferred to T16 by user instruction. Parent commit pending.
- L15 (2026-10-25): Committed T3 as `6a69351` (`fix(progress): fence sync by live data generation`). The commit contains Android/iOS generation fencing, production adapters, regression tests, AppTests project wiring and associated SDD evidence. iOS App execution remains pending T16.
- L16 (2026-10-26): User resumed with “continua” and asked “no deberías hacer commit de t3 antes?”. Verified T3 is already committed before T4: code `6a69351`, verification record `674b0b2` (HEAD at T4 start). T4 mapping found existing route authenticates before v1 handling; v1 has strict body shape and 200-entry upload cap; current GET is capped at 1000 but has no cursor/tie-break ordering. DB integration tests require `make test-api` with isolated PostgreSQL. CodeGraph CLI unavailable, so mapping used scoped filesystem fallback. T4 implementation remains pending review and commit.
- L17 (2026-10-26): Fresh T4 review found explicit unsupported/malformed GET version selectors return legacy 200 instead of an error, and valid historical v2 firstAt values fail against migration 030's `first_at > '2025-01-01'` check. Review also noted cursor tokens omit protocol version although the approved design requires it. Updated T4 brief/plan to add focused regression tests, a version-tagged cursor, and the next additive migration; migration 030 remains immutable. Parent full `make test-api` spot-check ended 582/757 passing and 175 failing, including admin-auth 401 and later database connection timeouts; independent reviewer passed the focused candidate (15/15) and baseline legacy probes (9/9). T4 is not complete or committed.
- L18 (2026-10-26): T4 correction round 1 addresses all three review findings: only an absent GET version selector negotiates legacy v1; migration 054 removes only the old historical timestamp cutoff; cursors encode and validate protocol version 2. Fresh independent re-review passed. Focused PostgreSQL regression suite and parent rerun each passed 17/17; `make check` (146), API type-check, and a fresh full `make test-api` (756/756) passed. Parent's separate full-suite failure (582/757: admin-auth 401 and later PostgreSQL timeouts) is retained as contradictory-run evidence, not hidden. OpenSpec and Superpowers records updated; behavior commit is the remaining T4 step. Risk: items 1 and 3 (additive schema migration plus versioned public HTTP contract).
- L19: T4 behavior commit `98ae12d` created after clean independent review, correction round 1, focused checks and a fresh full API suite pass (756/756); documentation records the earlier transient full-suite environment failure and successful rerun.
- L20 (2026-10-26): “sigue con todas las tareas pendientes hasta finalizarlas todas” — continue the approved task sequence without asking for another continuation confirmation.
- L21 (2026-10-26): Fresh T5 review blocked completion: baseline Android/iOS remote merges stored aggregates as ordinary completion marks without origin/unit metadata, and local records lack per-record provenance. S2 forbids guessing from magnitude, field, schema version or kind. Preserve the uncommitted partial candidate; ask the user whether to revise the recovery requirement or provide an authentic provenance source before T5/T6/T7 continue.
- L22 (2026-10-26): User approved the safe recovery-scope revision: “si, adelante”. Update S2/T5 so pre-v6 records without per-record provenance remain unchanged and recovery applies only when explicit source/version and unit evidence is persisted; resume T5, keeping T6/T7 dependent on its completion.
- L23 (2026-10-26): “no me queda claro  a qué viene esto del origen. todo debería generarse en el movil y sincronizarse al BE. el único momento en el que se sincroniza del BE al movil es cuando se inicia sesión en un dispositivo nuevo en el que no había datos de ese usuario. esta debería ser la premisa para todo” — clarified mobile as the source of progress and backend downloads as a one-time empty-device bootstrap; timestamp encoding evidence is distinct from local/server data origin.
- L24 (2026-10-26): “la recuperación se puede seguir haciendo y las fechas deberían ser las establecidas en el dispositivo movil. necesital algo más o puedes continuar?” — retain evidence-bounded recovery and the mobile-recorded instant as canonical; no additional product detail was requested.
- L25 (2026-10-26): “es obligatorio que el ususario haga login para poder asociar sus datos con un usuario, por tanto en local siempre debería haber un usuario” — local progress belongs to the active user identity.
- L26 (2026-10-26): “la sesion invitada la usamos sólo para pruebas locales, en producción no habrá este tipo de sesiones esto también será así para el BE, todo se sincronizará/guardará en base al usuario logueado. si es un problema lo quitamos y dejamos listo ya, aunque tenía pensado hacerlo en las últimas fases de desarrollo en el futuro” and “vale entonces lo dejamos así por el momento, sigue por donde ibas” — keep the existing guest-session product code for now; each installation's guest account remains an isolated identity, while production progress is scoped to the logged-in member.
- L27 (2026-10-26): “sí, cuando inicias cuentas distintas en la app no deberías ver mezclados datos de distintas cuentas, por tanto cada usuario debe tener los datos asignados a su cuenta” — explicit authorization that all local learner data is account-owned and isolated across account switches.
- L28 (2026-10-26): “no entiendo qué quieres decir con al cerrar sesión se conserva el progreso local siempre debería haber una sesion de un usuario activa ya sea invitado o logueado, por tanto todos los ejercicios y examenes y datos que se guarden tienen que estar asociado a una cuenta de usuario, no es posible matener esta premisa siempre¿” — every persisted learner-data operation must have an active member or guest identity; sign-out transitions to a guest identity rather than an ownerless state.
- L29 (2026-10-26): “sí lo autorizo, aunque estamos ahora mismo en fase de desarrollo por lo que lo que hay actualmente sólo es en fase de pruebas y no debería afectar para cuentas nuevas. si te refieres al paso de una cuenta invitada a usuario registrado, yo creo que de momento dejaría todo como está y el usuario logueado no tendría visibilidad de lo que hizo el invitado, así reducimos problemas, ya si más adelante queremos mergear lo vemos” — authorized one-time backfill of existing ownerless development data to the identity active at migration; future new-account data is unaffected, guest-to-member visibility/merge stays explicitly out of scope.
- L30 (2026-10-26): Updated the SDD specification, plan, design and task dependencies for S17 after user approval. Existing ownerless development rows may be backfilled once to the resolved active identity; all future learner data stays owner-scoped, guest-to-member merge remains excluded, and only member progress syncs. S17 is a new high-risk migration task required before T6–T8. Documentation checks passed; implementation has not started.
- L31 (2026-10-26): S17 changes S1 guest ownership from ownerless NULL to an explicit stable per-install guest identity. Reopened T1 history isolation; T17 will provide the identity foundation and update the S1 query/tests. NULL remains legacy-only during the one-time owner migration.
- L32 (2026-10-26): T17 source implementation is blocked at the required fresh-implementer dispatch: the repository instruction assigns `gpt-6.1-luna`, but the active collaboration tool rejects that model as unknown and lists only `gpt-6-luna` among the available Luna profiles. No fallback model or inline bypass was used; the user-selected delegated implementation path must be clarified or made available before source edits. The S17/S1 documentation updates and Engram mirror remain in place; no source files were changed for T17.
- L33 (2026-10-26): “sí adelante” — user authorized using the available `gpt-6-luna` implementation profile instead of the unavailable `gpt-6.1-luna` profile for the required fresh T17 implementer.
- L34 (2026-10-26): “si” — user authorized expanding T17 allowed edit surfaces to exactly the nine additional paths enumerated in the prior approval request: Android/iOS exam-history DAO and tests, Android MainActivity, and Android/iOS progress-sync operations and tests.
- L35 (2026-10-26): Architecture decision for S17: retain one shared SQLite DB per installation and add a stable namespaced owner key (`member:<accountID>` / `guest:<installationUUID>`) to each mutable learner-owned row. Keep content caches/device metadata global; resolve identity before the additive backfill migration. This reuses the existing shared database and explicit exam-attempt ownership field rather than splitting the application into per-account database files.
- L36 (2026-10-26): “sí autorizo este y todos los que necesites en un futuro” — authorized the additional `apps/android/app/src/main/java/com/jkdc/app/data/LevelTestAttemptStore.kt` path and future paths demonstrably required within the already approved mobile-remediation scope; keep the task log and file changes bounded to that scope.
- L37 (2026-10-26): “no me preocupa eso ahora mismo, documentalo para mirarlo más adelante. ahora me gustaría que continuaras con el resto de tareas que están pendientes que creo que son bastantes” — leave whole-install database backup export/import semantics unchanged for this remediation; track current-owner-only backup filtering as a future consideration and continue the remaining approved tasks. This does not weaken active-owner filtering in app reads/writes or the owner keys preserved in backup data.
- L38 (2026-10-26): T17 handoff: Android ownership/history/sync and raw-backup tests, schema checks and 37 Python tests passed. Full Android suite was 754/755 before a stale snapshot expectation was corrected; targeted test passed, full rerun pending then. iOS was unavailable on Linux. Raw backups retain all owner partitions; active-owner projections stay scoped; backup filtering remains deferred.
- L39 (2026-10-26): Android full suite passed 756/756. Scratch found global owner selection could late-write across accounts, history mutation accepted foreign attempts, and iOS identity/restore had mismatches. Focused tests passed; three targeted probes failed; correction followed.
- L40 (2026-10-26): T17 first correction fixed exam mutation and iOS binding/restore; recheck still reproduced stale A activation via `AccountViewModel.openLearningDb`. Second correction captured session+generation; scratch probes rejected stale A without changing B identity/preferences or DB hash. Android focused/full suites passed; iOS source was structurally checked, execution deferred to T16. T17 review complete; no commit because T5 edits were interleaved.
- L41 (2026-10-26): No historical tuple evidence supports rewriting v5 rows; per S2, leave them unchanged. T5 remained open pending explicit evidence-backed repair.
- L42 (2026-10-26): Queue T9/S6, which depends on completed T3 and is independent of partial T5/T6–T8. CodeGraph CLI is unavailable, so no broad repository map was performed; the exact S6 behavior and paths are in the plan.
- L43 (2026-10-26): Committed checkpoint `07685b5` for T17 ownership plus interleaved T5 schema/rollup; T17 Android verification/review passed, while T5 positive evidence-backed repair remains partial. Preserved the audit report and `.playwright-mcp/`; a docs commit recorded the checkpoint.
- L44 (2026-10-26): User interrupted T9 dispatch with “antes habría que hacer commit de los cambios que hay pendientes”; the implementer stopped before edits. Only T17/T5 checkpoint work was committed; T9 remains queued.
- L45 (2026-10-26): Recorded the checkpoint as behavior commit `07685b5` and SDD evidence commit `3173fec`; both are on `feature/learning-platform`. The report and `.playwright-mcp/` remain untracked and untouched. T9 now starts after those commits, as requested.
- L46 (2026-10-26): T9 implementation and bounded independent recheck are complete for code-level evidence. The recheck in a fresh scratch copy from `3173fec` passed the focused `ThemeTransitionTest` (7 tests) and structurally confirmed pending-only selection, terminal close/settlement gating, stale/account/navigation checks, and cancel invalidation. Android UI test source compiles, but no connected device is available; mounted AppViewModel state behavior is not directly exercised. User requested committing pending changes before starting the next task, so commit this verified implementation checkpoint and keep UI runtime proof explicit.
- L47 (2026-10-26): Committed T9 as `442c585` with production changes, tests and SDD evidence. Focused tests and Android APK assembly passed; connected UI was unavailable. Review found no blocker; mounted AppViewModel runtime proof remained a documented gap. `git diff --check` passed.
- L48 (2026-10-26): User resumed the approved task sequence with “continua”. T9 is complete; begin T10/S7–S9 from baseline `4d8942c`, using the existing delegated sequential implementer/reviewer route. The task brief must record frame-derived preroll, capture limits, queue bound, and deterministic overflow policy before production edits. Preserve existing untracked report and `.playwright-mcp/`.
- L49 (2026-10-26): Required fresh T10 implementer dispatch failed before starting because the runtime rejects the prescribed worker profile `gpt-6.1-luna` as unknown (available Luna profile is `gpt-6-luna`). No source files were edited and no alternate profile was substituted. Task brief is prepared with exact edit surfaces, S7–S9 scenarios, TDD commands and pre-code limits requirement; implementation awaits an authorized profile substitution or runtime availability.
- L50 (2026-10-26): User authorized the available model with “autorizo el modelo disponible ahora mismo”. For T10 only, use the available `gpt-6-luna` implementer profile; keep the existing independent reviewer gate and do not infer authorization for other tasks.
- L51 (2026-10-26): T10 review round 1 fixed mute-epoch invalidation, cancellation-safe cleanup, history truncation and added a no-phantom-turn check. Re-review accepted the first three but found S8 still used a fixed sleep, last-sample checks and repeated follow-up samples; scratch probes were not durable tests.
- L52 (2026-10-26): T10 round 2 added an STT/LLM counter and producer latch; focused suite passed 44 tests. Re-review found the latch fires before producer classification, so a final frame may still queue; scratch probes pass but scheduling race remains. Both correction rounds exhausted; T10 is Needs decision, uncommitted.
- L53 (2026-10-26): User resumed with “continua” and allowed T12 independently of pending T10/T11. Preserve the instruction verbatim: “respecto a la parte de swift podemos dejar toda la parte que tiene que ver con probar la app, etc para el final y continuar con las siguientes fases si no hay nada más que cubrir en esta fase.” T12 can author tests but no SwiftPM/Xcode/`make ios-test` until T16.
- L54 (2026-10-27): T12 review found PBX membership, retry-timer, timeout, mounted cancellation and localization gaps. Round 1 fixed those; round 2 added a persistent DEBUG cleanup probe and pre-reopen UI assertion. Re-review then found missing `ObservableObject` conformance; no Swift run or commit.
- L55 (2026-10-27): Round 2 closed the navigation-proof gap but review found `PronunciationUITestProbe` used by `@ObservedObject` without `ObservableObject`. Swift execution is deferred to T16; T12 awaited one scoped fix.
- L56 (2026-10-27): User resumed with “continua” in response to the request to authorize a scoped correction for the T12 DEBUG compile blocker. Reopen T12 for one bounded fix and re-review of `PronunciationUITestProbe` conformance; preserve all earlier findings/fixes and continue deferring Swift/iOS execution until T16.
- L57 (2026-10-27): Added `ObservableObject` to DEBUG-only `PronunciationUITestProbe`; rereview and independent verification passed. Swift/Xcode deferred to T16; commit followed as T12.
- L58 (2026-10-27): Committed the reviewed T12 implementation as `c8c2d48` (`fix(ios): enforce local pronunciation lifecycle`). The commit includes localized permission declarations, on-device-only recognition, recoverable lifecycle cleanup, AppTests/UI tests, Xcode target membership and pronunciation proposal updates. Static source checks and staged/unstaged whitespace checks passed; Swift/Xcode/App/UI execution remains deferred to T16. T12 implementation is committed, not platform-verified.
- L59 (2026-10-27): User resumed the approved sequence with “continua”. T12 status records were committed as `8b447c2`; T13/S14 is independent of unresolved T10/T11 and proceeds from baseline `8b447c2`. Continue honoring the explicit iOS test deferral to T16; tests may be authored but Swift/Xcode/iOS commands must not run.
- L60 (2026-10-27): T13 implementer identified `TypedReplyTests.swift` was not registered in the explicitly enumerated AppTests sources. Parent verified `apps/ios/App.xcodeproj/project.pbxproj` contains the existing AppTests source list but omits the new file. User authorized the exact additional project path with “sí”; add only the new test source and build-file references to the AppTests target, preserving the T16 Swift/iOS execution deferral.
- L61 (2026-10-27): T13 verifier found cancellation could strand the shared “Checking that for you…” indicator because learner-generation invalidation skipped the typed defer reset. Parent confirmed; fix must preserve unrelated delegation loading and defer Swift execution to T16.
- L62 (2026-10-27): T13 review finding #1 was addressed in fix round 1: coordinator-level tests now drive two provider failures and explicit retries to success, asserting three provider calls and one local typed fragment; a suspended duplicate-send test checks single flight. Scoped re-review marked the finding ADDRESSED. Final independent verification then identified stale shared `working` state after generation invalidation, which is being corrected before commit; focus-after-failure remains a T16 runtime check.
- L63 (2026-10-27): T13 fix round 2 split typed/delegation loading and added a suspended-response regression; re-review addressed stale spinner. Fresh-scratch verifier passed 18/18 static assertions. Swift and mounted UI proof remain pending T16. Risk: item 4.
- L64 (2026-10-27): T13 source, AppTests target, regressions and SDD committed as `185b152`; `git show --check` passed. No Swift commands ran; runtime proof is deferred to T16.
- L65 (2026-10-27): After T13 commit `185b152`, continue with independent T14/S15. Keep the same user constraint: author iOS tests but defer all Swift/iOS test and build execution until T16. Preserve unrelated Android T10 changes and user-owned untracked files.
- L66 (2026-10-27): T14 review found the UI test only checked generic source copy, not the conditional unsupported-engine warning. A deterministic preview state and exact warning assertion were requested; Swift execution remains deferred to T16. The reviewer also suggested a tab-scoped locator.
- L67 (2026-10-27): T14 fix added DEBUG-only unsupported-preview flag; UI test asserts conditional warning via tab-scoped Themes. Re-review addressed; parent diff/static checks passed. Swift/UI tests await T16. Minor deferred: configured-hosted route lacks a direct test; route mapping was source-reviewed. Risk none; commit pending.
- L68 (2026-10-27): Committed T14 as `e4a5375` (`fix(ios): gate unsupported topic search`) with coordinator/UI regressions and AppTests target registration. `git show --check --oneline --stat HEAD` passed; runtime Swift/iOS checks remain deferred to T16.
- L69 (2026-10-27): After T14, start T15/S16. Preserve secure-persist/adopt/notify order, profile-500 recovery and the user's T16 Swift-test deferral.
- L70 (2026-10-27): T15 review found two Important coverage gaps: production adoption/profile and refresh/invalidation. Fix round 1 added actual `signIn`/`refresh`, persistence, notification and router assertions.
- L71 (2026-10-27): T15 rereview addressed both findings; independent verifier passed 12/12 assertions. Minor: stale-exchange test omits replacement owner. Swift runtime and RED/GREEN await T16. Commit: `e545c79`. Risk: item 2.

- L72 (2026-10-27): Fixed OpenSpec: `e545c79` belongs to T15, not T6; T6 remains planned.
- L73 (2026-10-27): T5 repairs only exact persisted evidence tuples; legacy untagged rows stay unchanged. Android UPDATE count is unreliable (-1 on success), so recovery validates owner-scoped readback inside the transaction before marking repaired. Targeted tests and independent high-risk recheck passed; Swift runtime verification is deferred to T16. Risk: item 1.
- L74 (2026-10-27): Committed T5 recovery correction as `5dd7da4`; SDD now links the commit. Swift/iOS runtime proof remains explicitly deferred to T16. Risk: item 1.
