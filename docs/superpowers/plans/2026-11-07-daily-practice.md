# Daily Practice Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to implement this plan task-by-task. Keep task state in this document.

**Goal:** Deliver one shared, recoverable daily language-practice session across API, Android, iOS, and admin, with durable AI feedback and safe import into Words.

**Architecture:** The API owns identical published daily packages, private attempt state, speaking bindings, review jobs, and editorial operations. Mobile clients persist local drafts/outboxes and merge review vocabulary into each platform's existing owner-scoped Words store; the admin UI manages generation, review, publication, and withdrawal. Shared JSON contracts and golden fixtures keep server and mobile projections aligned.

**Tech Stack:** Node.js 22+, TypeScript, Fastify, PostgreSQL 17; Kotlin/Jetpack Compose; Swift/SwiftUI; shared JSON contracts; existing admin vanilla JS and browser E2E.

**Spec:** `openspec/changes/daily-practice/README.md`, `proposal.md`, `design.md`, `contracts.md`, `data-and-sync.md`, `pedagogy.md`, `specs/daily-practice/spec.md`, `tasks.md`, and `verify-report.md`.

## Global Constraints

- One common package per editorial date and language; v1 enables English only, with unsupported profiles rejected rather than silently substituted.
- Target session length is approximately 15–20 minutes; it is not a timer, forced stop, or speaking turn limit.
- One attempt per learner/language/editorial date by default; keep the cap configurable, persist progress, and resume the same attempt after any interruption.
- Speaking is an open, bidirectional topic conversation with opening and explicit user-controlled close; feedback is delivered after speaking, not as an interruption.
- Persist and expose review results for later reading; words and expressions feed the existing Words feature without creating generalized cloud Words sync.
- Never expose private answer keys, transcripts, learner evidence, provider credentials, or PII in public responses, logs, browser-visible admin data, or metric labels.
- Do not hand-edit generated `*Data.kt` or `*Data.swift`; preserve legacy hosted voice, exam, progress, and Words behavior.
- Daily listening publishes a transcript plus profile-bound `speechLocale`; Android/iOS synthesize locally with existing platform TTS. The daily API must not store or serve audio binaries, add a TTS provider operation, or silently fall back to a voice in another language; if the requested voice is unavailable, allow retry or an explicit partial skip.
- Keep English and Spanish Android strings in their respective resources; technical artifacts and code use English.
- Do not use real LLM providers, production data, deployment, or activation without separate authorization; keep feature flags off until approved. Daily listening reuses the existing on-device TTS in each app; no server TTS provider or generated audio asset is in scope.
- Operational values in README § “Decisiones de diseño que requieren revisión del propietario” are proposals, not approved decisions; block only the affected implementation until the owner resolves them.

## Review Focus

- **Late/unknown network writes, app death, and midnight rollover:** test durable idempotency and resume under the same attempt and frozen package.
- **Cross-owner, guest, and account-switch behavior:** test authorization on every private read/write and prevent late writes/imports crossing owner boundaries.
- **Provider timeout, retry, and uncertain cost:** test no duplicate dispatch/charge and keep unknown operations reserved until reconciled.
- **User-deleted or already learned Words entries:** test replay preserves recall progress and does not resurrect explicitly suppressed expressions.
- **Slow/out-of-order admin responses and route changes:** test stale runs cannot overwrite the selected date/language and errors stay visible with a safe retry.

---

### Task 1: Shared contracts and language profile

**Files:**
- Create `shared/contracts/daily-practice/{package,attempt,event,review}.schema.json` and fixtures.
- Create `services/api/src/daily-content.ts`, `daily-language-profiles.ts` and `services/api/tests/daily-content.test.ts`.
- Create Android `core/DailyPractice.kt` and iOS `Core/DailyPractice.swift`, with mirrored core tests.
- Update contract checkers only if required for these new schema/fixture files.

**Interfaces:** `DailyLanguageProfile`, `validateDailyPackage`, `projectPublicDailyPackage`, `hashDailyText`, `hashDailyArtifact`, and `gradeDailyClosedAnswers`; use the exact fields, limits, and errors defined by `contracts.md`.

- [ ] Add RED tests: `same_package_for_different_learners`, `english_disabled_profile_is_rejected`, `public_projection_contains_no_answer_keys`, `jsonb_roundtrip_keeps_hash`, duplicate question/option IDs, and English contraction grading.
- [ ] Add one complete day fixture with 5 expressions, 3 listening questions, 3 goals, and 4 practice items; validate UTF-8 sizes, codepoints, duration bounds, and normalized language profiles.
- [ ] Implement pure validation/projection/grading and equivalent Kotlin/Swift codecs; verify shared golden fixtures across API and both cores.
- [ ] Run focused tests, then `make check`; expected result: all new and existing contract checks pass without generated-file edits.

### Task 2: Durable API storage, budgets, jobs, and publication

**Files:** next migration in `services/api/migrations/`; create `daily-jobs.ts`, `daily-budget.ts`, `daily-generation.ts`, `daily-scheduler.ts`, `daily-admin.ts`; modify `main.ts`, `app.ts`, and `metrics.ts`; add daily job/budget/generation/publication/scheduler tests.

**Interfaces:** `reserveDailyCost(tx,binding,costCenter,maxNanoUsd)`, `claimDailyJob`, `ensureDailyPackage(language,editorialDate)`, and `publishDailyPackage(tx,packageVersionId,artifactHash,expectedRevision)` follow `tasks.md` and `data-and-sync.md`.

- [ ] Add RED PostgreSQL tests for budget reservation races, zero budget, safe pre-send reclaim, unknown sent lease, and two-replica claims; use an injected clock.
- [ ] Add RED publication tests proving judge failure, uncertain language, modified answer/keys, profile locale mismatch, hash mismatch, and unknown jobs cannot publish or auto-retry.
- [ ] Implement schema constraints/FKs, durable reservation and job leases, frozen generation configuration, independently invoked judge, locale/profile validation, immutable publication, and startup ensure for today/tomorrow once.
- [ ] Add public read routes with no private keys, cache/ETag/withdrawal behavior, and closed-label domain metrics; test no regression in exam schedulers.
- [ ] Run focused tests and isolated PostgreSQL tests via `make test-api`; expected result: races and restart paths are exercised against PostgreSQL, not mocks alone.

### Task 3: Private attempts, hosted speaking binding, and durable review

**Files:** create `daily-attempts.ts`, `daily-voice.ts`, `daily-review.ts`; modify `app.ts`, `hosted-voice.ts`, `live-provider.ts`, plus Android/iOS hosted voice request builders; add attempt, voice, review, and job tests.

**Interfaces:** `createDailyAttempt(principal,body,idempotencyKey)`, `appendDailyEvent(principal,attemptId,event)`, `getDailyAttempt(principal,id)`, `resolveDailyVoiceContext(principal,attemptId)`, `freezeDailyReviewInput(tx,attemptId)`, and `validateDailyReview(raw,snapshot,profile)`.

- [ ] Add RED tests for owner isolation, guest admission under existing policy, simultaneous devices, midnight replay, configurable one-attempt cap, indefinite resume, rejected/no-op events, idempotent replay, conflicting IDs, CAS rebase, and stage sealing/skip.
- [ ] Add RED voice tests for optional `dailyAttemptId` binding, strict context fields, no personal history, unchanged legacy behavior, one active connection, free turn count, explicit close, reconnect continuity, no interrupting feedback, and out-of-order close.
- [ ] Add RED review tests for source-grounded quotes, uncertain transcription, acoustic-claim limits, durable completion past the legacy 120-second helper window, polling without LLM calls, one review per finish, unknown dispatch reservation, and deleted-account late results.
- [ ] Implement private event-sourced/CAS attempts and immutable close; resolve only bound topic context; freeze review input/hash; validate and persist admitted review snapshots; never reuse transient scenario review as durable review.
- [ ] Run focused tests, `npm run check`, unit tests, and PostgreSQL integration via `make test-api`; expected result: exact attempt and review survive process restart without duplicate provider operation.

### Task 4: Words import and mobile persistence primitives

**Files:** modify `shared/db/schema.sql` and schema checker/fixtures; create Android `core/DailyWordsImport.kt` and iOS `Core/DailyWordsImport.swift`; modify Android `LearningDbWriter.kt`, `LearningDbDao.kt`; iOS `LearningDbWriter.swift`, `Storage.swift`; add mirrored import/database/backup tests.

**Interface:** `planDailyWordsImport(result,owner,language,existing,receipts,suppressions,now)` returns only `insert | keep | suppress | pendingCapacity` actions; platform writers atomically apply expression and receipt after checking current owner admission.

- [ ] Add RED tests for import replay, preserved recall progress/meaning, single words, 2,000-entry capacity, deleted-entry suppression, manual save/mark interactions, language/account switch, and rollback between upsert and receipt.
- [ ] Add RED backup/restore tests for legacy archives, optional metadata, cross-platform backup, owner mismatch, missing-metadata confirmation, and account-scoped suppression.
- [ ] Implement pure import planner and transactionally persisted receipts/suppressions without adding a new source to the shared source enum or cloud Words sync.
- [ ] Run Android JVM and Swift Core tests plus `make check`; expected result: replay is safe, capacity never evicts, and deleted expressions stay suppressed.

### Task 5: Android and iOS daily experience

**Files:** create Android `network/DailyPracticeClient.kt`, `ui/DailyPracticeScreen.kt`; iOS `App/DailyPracticeClient.swift`, `App/DailyPracticeView.swift`; integrate Android `AppViewModel.kt`, `LearnScreen.kt`, `AppRoot.kt`, `LibraryScreens.kt`; integrate iOS `CoursesViews.swift`, `RootView.swift`, `ConversationCoordinator.swift`, `LibraryViews.swift`; add EN/ES Android and iOS strings and unit/UI tests.

**Interfaces:** both clients consume the shared package/attempt/event/review DTOs from Task 1 and the backend endpoints from Tasks 2–3; local draft/outbox writes precede network sends, and confirmed vs unknown outcomes remain distinct.

- [ ] Add RED reducer/core tests for stage transitions, incremental answers, process death/resume in each stage, midnight, late responses, transport failure, and no time-based closure.
- [ ] Add RED listening tests proving playback uses the package `speechLocale` and transcript locally, never downloads a server audio asset, reports unavailable locale without wrong-language fallback, and records an explicit partial skip while preserving the attempt.
- [ ] Add RED UI tests for loading, no package, microphone denial, offline/HTTP errors, late response after navigation, retry, empty/partial result, hints, focus/keyboard, accessibility announcements, and persistent errors.
- [ ] Implement the shared flow with explicit user-visible save state, speaking open/close, review history, and Words import; do not claim cloud synchronization for Words.
- [ ] Run Android unit/lint/build and connected UI tests when device is available; run Swift Core tests and macOS-only App/UI checks only on supported host. Expected result: each platform resumes the same attempt and old free voice remains usable.

### Task 6: Admin workflow and joint readiness

**Files:** create `services/api/admin-ui/features/daily-practice.js`; modify admin `app.js` and styles as needed; modify admin route registration; add `admin-daily-practice.test.ts` and `tests/browser/admin-e2e.mjs`; update OpenSpec verify report.

- [ ] Add RED pure admin tests for dirty config, date/language selection, run ID fencing, abort-on-navigation, persistent HTTP errors, retry, and late responses.
- [ ] Add RED API tests for authorization/Origin/audit, hash mismatch, withdrawal, invalid budget, failed reconciliation, and exclusion of private tables from browser results.
- [ ] Add real browser E2E: login → configure → stub generate → watch own run → preview → publish → read public package → withdraw; assert visible focus, error, and retry behavior.
- [ ] Run shared golden fixtures through server/Android/iOS and test end-to-end interrupted attempts/review, account deletion, retention, cost/privacy, and compatibility; record every unavailable platform check honestly.
- [ ] Keep flags off pending approval, provider calls/pilot/deployment separately authorized; update verify report with executed commands, failures, skipped checks, and activation blockers.

## Execution Notes

Current disposition (2026-11-08): Tasks 1–6 have substantial implementation but remain **partial**. API/model checks and local contract checks pass; PostgreSQL integration, Android/iOS native test runs, browser end-to-end execution, and independent review remain unverified or unavailable. Do not mark the feature complete based on the partial checklist below.

- Work in dependency order; after each task, update its checkbox and OpenSpec task/verification evidence.
- Use test-first RED → GREEN for each listed behavior; existing tests passing alone do not prove feature coverage.
- No commits are implied by this plan; the user has not requested commits.
- Owner approval is needed for unresolved operational proposals before their affected tasks can ship: editorial/access policy, publication defaults, and review funding/retention. Do not infer consent from approval of this plan.


User decision (2026-11-08): “me parece bien, pero de momento usar el mismo provider que se usa para los exámenes y si este está activado seria suficiente para su uso”. Reuse the existing exam LLM resolver and active provider for daily generation/review; no separate credentials/configuration. Preserve Daily Practice-specific budgets and flags (zero/off by default), and do not call real providers, activate, change prices, or deploy without separate authorization.

Execution note (2026-11-08): Added a narrow provider adapter for daily jobs that resolves the existing exam LLM configuration once per job and reuses its transport; it fails closed if no exam provider is configured. RED was observed before implementation (`daily-llm.js` module missing), then `daily-llm.test.ts` passed 2/2 and `npm run check` passed. No provider calls were made. This adapter is only an enabling unit; content generation/review job consumers remain unfinished.


Execution note (2026-11-08): Before a generated package can be judged/published, API validation now requires private listening/practice keys to bind exactly to public question IDs, valid option/accepted-answer values, EN/ES explanations, and existing target expression IDs. RED observed in `invalid_daily_answer_key_binding_cannot_publish`; focused daily generation (7 pass, 1 DB skip), key-binding (3/3), admin (11/11), API type-check, and `git diff --check` passed. PostgreSQL publication integration was skipped without `TEST_DATABASE_URL`.


Execution note (2026-11-08): Added the pure Daily Practice quality-gate orchestration: server binds profile/date/locale/hashes; private answer keys are validated before a separate judge call; all namespaced package IDs require complete non-contradictory evidence, otherwise publication status is rejected/unknown rather than ready. RED observed for missing gate and incomplete judge evidence; final focused tests: pipeline 5/5, answer-key validation 3/3, generation 7 pass/1 PostgreSQL skip, admin 11/11, API type-check/diff check passed. It is not yet wired to durable workers or live providers.

User decision (2026-11-08): “esto podemos anotarlo para revisión futura y seguir la misma lógica que actualmente tenemos para la revisión de exámenes?” Confirmed after checking the exam pipeline: reuse its token-usage accounting (`complete|partial|unknown`) and defer provider/model monetary rates and settlement to future review. Do not convert token counts into money. Daily flags and budgets remain off/zero by default; a positive budget must not be treated as a guaranteed monetary ceiling until that future review is resolved. No provider call was made.

Execution note (2026-11-08): Extended `daily-llm.ts` to aggregate input/output token usage through the existing `GenerationUsageTracker`, including unknown usage after a failed call without reported usage. Added tests for aggregation and unknown usage. The node test runner could not start because the sandbox denied its local IPC socket (`listen EPERM /tmp/tsx-1000/...pipe`); `npm run check` and `git diff --check` passed.

User decision (2026-11-08): “yo de momento registraría tokens como lo hacemos para los examenes pero no lo tendría encuenta para el uso ya que este será superfluo y no se tendrá en cuenta al menos de momento, si me gustaría tener un registro de todas estas cosas para poder analizar con detenimiento antes de la release como las otras cosas que hemos comentado”. Persist only operational state and token-count checkpoints for later analysis; do not use them for quotas, access, availability, or user-facing decisions. Never persist prompts or learner/provider content in usage records.

Execution note (2026-11-08): Added recordDailyProviderUsage to persist a strict allowlisted {state,tokensIn,tokensOut} snapshot on the provider-operation row and reject extra/personal fields. Usage checkpoints are emitted before and after a provider call through the adapter callback. TypeScript check passes; tsx tests remain unable to start due sandbox-denied local IPC. Admin token-history projection remains a planned Task 9 item; no provider calls made.

Verification correction (2026-11-08): Although `node --import tsx --test` reported sandbox stream-fd warnings and a zero aggregate count, executing the TS test modules directly with `node --import tsx` produced passing TAP cases: daily-llm 6/6, daily-usage 2/2, daily-jobs 3 passed/4 PostgreSQL tests skipped because `TEST_DATABASE_URL` is unset. `npm run check` and `git diff --check` pass. The usage checkpoints are currently exposed for a future consumer but are not yet wired into durable generation/review workers.

User request (2026-11-08): “yo de momento registraría tokens como lo hacemos para los examenes pero no lo tendría encuenta para el uso ya que este será superfluo y no se tendrá en cuenta al menos de momento, si me gustaría tener un registro de todas estas cosas para poder analizar con detenimiento antes de la release como las otras cosas que hemos comentado”.

Execution continuation (2026-11-08): Added the per-provider-operation usage history to admin job-list and run-poll projections for both content and review jobs. The projection is allowlisted to phase/state/timestamps and `{state,tokensIn,tokensOut}`, and the panel displays token counts as analysis-only; it does not expose provider text or derive spend. RED observed first: API response omitted `provider_operations`, and admin helper export was missing. Focused API/admin state tests now pass; typecheck and syntax/diff checks pass. Durable workers still do not invoke the adapter, so no new token records can appear until worker wiring is completed.

Verification update (2026-11-08): Added a run-poll projection test confirming unexpected fields inside a usage object are dropped. Final focused counts are daily-admin 13/13 and admin-ui-state 14/14; TypeScript, JavaScript syntax and diff checks pass.

## Continuation log (2026-11-08)

- Continued Task 2/3 by adding live-owner lease renewal and periodic heartbeats around long content/review provider work. RED observed for missing `renewDailyJobLease`; the fenced update and worker heartbeat now pass focused API checks.
- Added the durable review worker: it loads the immutable review input and published package, checks the frozen hash, uses the configured exam LLM provider, validates every result against learner evidence, stores an immutable review, and records only per-operation token counts. Added a review scheduler to API startup/shutdown.
- Fixed recovery so an expired review job transitions the learner-visible attempt status to `unknown` or `failed`; otherwise polling could remain queued after the job became terminal. RED/GREEN observed.
- Verification: `npm run check`; all `tests/daily-*.test.ts` files run individually passed (PostgreSQL cases skipped where `TEST_DATABASE_URL` is absent); `make check` passed (152 Python tests, generated schema/contract/parity/localization checks). `make test-api` could not start because Docker daemon access is denied. `make android-build` could not start because the Gradle wrapper lock is read-only. iOS toolchain unavailable on this Linux host. `git diff --check` passed.
- No provider call, activation, budget change, production data, deployment, commit, or push occurred. Tasks remain partial until PostgreSQL/API integration, native platform checks, browser E2E, and independent whole-feature verification are completed or honestly recorded as unavailable.

Execution continuation (2026-11-08): A high-risk independent Android storage review identified five Task 4 regressions: `SqlDatabase.update()` does not return affected-row counts on Android; Daily Practice import supplied Unix timestamps to a model using Apple-reference seconds; stale plans could overwrite current Words progress; KEEP did not fill blank meanings/adopt legacy rows; the commit candidate lacked canonical v9 schema. Added regression coverage and fixed those issues with persisted-row verification and transactional import revalidation. RED observed for the four initial tests and the no-argument epoch API; focused corrections pass 5/5. Full Android JVM + engine-local verification: 930/930; lint + assemble pass; `make check` passes 152 Python tests and schema/export/parity/localization; isolated PostgreSQL `make test-api`: 976/976. Independent bounded recheck cleared all five blockers and confirmed schema v9 and local-TTS contract. Feature tasks remain partial: connected UI suite still has 12 failures across 84; only Daily Practice entry has a focused emulator pass; iOS App/UI/Core not verified on Linux; backup/restore and broad UI recovery scenarios still need evidence. No live LLM, feature activation, pilot or deployment.

Execution continuation (2026-11-08): Cross-platform import review found exact-language vs legacy-row precedence could overwrite the exact row when restored DB data legitimately contains both. Android/iOS writers now query an exact language match first, only falling back to legacy; Android regression observed RED then GREEN and independent SQLite/source review confirmed both rows and progress remain preserved. iOS regression/schema tests were added, including the v9 table list; fixed a test accessor to `SqlRow.real`, but Swift execution remains unavailable on Linux. Added file-backed Android attempt close/reopen coverage to verify owner, frozen package, persisted state and revision resume from disk. Final Android JVM + engine-local: 932/932; `:app:lintDebug :app:assembleDebug`, `make check` 152 Python/checks pass. Prior isolated PostgreSQL suite remains 976/976 (no API files changed after that run). Feature remains partial: app-level error/retry/resume UI coverage and iOS XCTest are still pending; the broad emulator UI run has 12 failures/84, without baseline triage.
