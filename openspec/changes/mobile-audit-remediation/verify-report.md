# Mobile remediation document review

**Scope:** User-requested review/correction of design, OpenSpec requirements, task list and execution plan on 2026-10-25. **Application implementation: not started.**

## Corrections

| Finding in the draft | Correction | Evidence / rationale |
|---|---|---|
| T5 promised atomic page+cursor writes before T8 created rollup storage | T5 now creates remote rollups, recovery and atomic persistence; T6/T7 consume it, T8 wires readers | Existing `audit-progress-sync/design.md` requires page data and cursor in one transaction |
| V2 route/flag/error/fallback were unspecified or conflicted with prior SDD | Preserve `/v1/progress`; explicit GET/POST version selector, 1000/200 page limits, default-off flag and one exact 503 structured error; negative downgrade scenarios added | Prior sync design negotiation; `services/api/src/app.ts` ServiceError/auth flow |
| Upload contract could lose firstAt because v1 accepts only at | V2 explicitly carries firstAt/lastAt and tests persistence-to-fresh-ledger round-trip; v1 remains unchanged | `services/api/src/progress.ts` strict parser and one-at merge; both native aggregate serializers |
| Schema/exporter paths and migration checks were placeholders | Name canonical SQL, real schema exporter, generated SchemaData outputs, native openers, pinned inventory and upgrade tests; append next available version after 5 | `shared/db/schema.sql`, `scripts/export_db_schema.py`, `scripts/check_db_schema.py` |
| Core tests were treated as possible proof for uncompiled iOS App code | T3 explicitly adds AppTests target and scheme; later App tasks test actual adapters; SwiftPM/App/UI checks remain separate | `apps/ios/Package.swift` only Core/Tests; existing Xcode scheme only AppUITests |
| S14 table still said “at most once” despite repeated manual retry decision | Consistent single concurrent generation per pending message, repeated explicit retries, one local message, no provider exactly-once promise | Existing design builder-check resolution and M14 |
| T13 mixed three independent iOS behaviors and task steps had generic tests | Split typed reply/search/session into T13/T14/T15; integration becomes T16; all tasks include exact surfaces, named assertions, commands, interfaces and prerequisite gates | Superpowers writing-plans task right-sizing and source-backed test map |
| New OpenSpec spec lacked delta Requirement/Scenario structure | Use ADDED Requirements and GIVEN/WHEN/THEN scenarios for all S1–S16; preserve stable audit requirement IDs | Existing progress-sync spec convention |
| Earlier task mirror duplicated requirements and introduced an excluded workflow | Keep a recovery pointer at the old path, with canonical OpenSpec + Superpowers plan only | Repository explicitly requires Superpowers and excludes Gentle AI workflows |

## Checks performed for this document revision

- Read source report, all current remediation documents and existing progress-sync contract.
- Independent read-only source mapping confirmed persistence ordering, actual schema/exporter files, missing App test target, real Android UI variant and database-test skip behavior.
- Parent spot-check confirmed existing-route negotiation, current schema version 5 and actual exporter ownership.
- Structural consistency check executed successfully: 16 normative requirements with GIVEN/WHEN/THEN scenarios, 16 matching plan/task/design entries, task file surfaces present or created by a prerequisite, six explicit gates per task, matching route/timestamp/error contract, and no application/source-tree modifications. This checks document consistency, not application behavior.

## Limitations and implementation gates

- During the original document-only revision, CodeGraph CLI/MCP was unavailable; bounded source reads were used instead of treating its existing index as fresh.
- During that document-only revision, no application source, generated artifact, migration, dependency, branch or production configuration was changed; later implementation evidence is recorded below.
- No Gradle, Swift, Xcode, PostgreSQL integration or device suite was run during the document-only revision. Earlier audit results are historical, not fresh remediation evidence.
- OpenSpec CLI was not installed during the document-only revision; local structural checks cannot be represented as official CLI semantic validation.
- V2 defaults off. Local v2-on/v2-off compatibility must be verified before code delivery; production rollout requires separate authorization. Missing App/device proof remains pending.

## Implementation evidence matrix

- **T1 / S1:** Unit coverage `historyIsScopedToRequestingOwner`, `lateHistoryCannotRepopulateAfterOwnerSwitchOrReturnToSameAccount`, and `switchingToGuestClearsAccountHistoryBeforeGuestLoadCompletes`; instrumentation coverage `lateOwnerHistoryCannotRepopulateNewAccount`. Focused Android unit test and full app unit suite passed. Instrumentation source compiles, but `./gradlew --offline :app:connectedUiTestAndroidTest` could not execute because no device is attached; S1 remains unverified pending that run. Independent review approved after one correction batch. Commit: `f4673e2`.
- **T5–T16 / remaining S2–S16:** Pending implementation and scenario-level verification.
- **T2 / S2,S4:** Shared fixture/exporter, generated Kotlin/Swift constants and checked platform time primitives added; recovery spec requires persisted per-record provenance and preserves ambiguous values. Exporter test (5 cases), `make check` (146 tests), focused Android time tests and full Android suites passed. One initial full Android run had a single `LocalVoiceTransportTest` assertion failure, which passed on focused rerun and a second full run (731 tests, zero failures); baseline status is unknown. Swift unavailable, so SwiftPM execution remains pending. Independent review approved after one correction batch. Risk: item 3 (shared cross-platform contract). Commit: `d58a2c9`.
- **T3 / S3:** Android runner and queued commit fences cover identity/import admission and coherent storage/memory publication; iOS coordinator has injected network/storage edges. AppTests definitions are inside PBX `objects`, App Debug enables testability, and strict OpenStep parsing confirms the required objects terminator. Fresh scratch review verified 109 direct objects, AppTests source/product/dependency/config/host references, scheme binding and a failing negative control without the terminator. Scratch Android app tests passed 733/0 (including 13 T3 tests); engine-local 11/0. Per user direction, defer Swift/Xcode App execution to T16. Commit: `6a69351`.
- **T4 / S4:** Existing `/v1/progress` now negotiates v2 with an exact default-off gate, strict authenticated selector handling, C-collated keyset pages bounded to 1,000 rows, version-tagged validated cursors, a 200-entry versioned upload preserving both timestamps, and atomic server merge acknowledgement. Added `services/api/migrations/054_progress_allow_historical_first_at.sql` to remove only the legacy arbitrary 2025 cutoff; migration 030 remains immutable. Independent review found and verified fixes for selector fallthrough, historical timestamp persistence, and cursor versioning. T4 tests: RED 4 original-contract failures and correction RED 3 findings; final progress PostgreSQL suite 17/17, parent focused rerun 17/17, `make check` 146, API type-check passed, and fresh full API integration `make test-api` 756/756 passed. A separate parent full-suite run failed 582/757 with an admin-auth 401 and later PostgreSQL connection timeouts; this was recorded, not represented as green. Risk: items 1 and 3 (additive migration plus shared HTTP contract). Work-unit commit pending.
