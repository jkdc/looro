# Tasks: progress-exercises-listening

## F1 — Backend ledger

- [x] `030_user_progress.sql` (closed kind CHECK, slug CHECK, cascade FK, monotonic merge PK)
- [x] `031_usage_events_catalog_exercises.sql` + `USAGE_CATALOG` entries
- [x] `src/progress.ts` parser/merge/list + `POST/GET /v1/progress` routes
- [x] `user_progress` browsable in the admin panel
- [x] `progress.test.ts` + `progress-http.test.ts`; suite green (416/416)

## F2 — Local ledger, engine, sync, progress UI

- [x] `CompletionMark` + `Archive.completions` (Kotlin + Swift, validate + merge + 2,000 cap)
- [x] `ARCHIVE_STRUCTS` gains `CompletionMark`
- [x] `ProgressEngine` projection (Kotlin + Swift) + tests
- [x] Write hooks: lesson end, theme session, recall round, words practice, assessment
- [x] `ProgressSync` (rollup derivation + remote merge) + `ProgressClient` (OkHttp/URLSession)
- [x] Learn tab HECHA/percent; progress sheet extensions; strings EN/ES
- [x] Gradle unit suite green (441/441 at this phase); Swift core mirrored (macOS gate pending)

## F3 — Temario exercises

- [x] `exercises` schema in `en-foundations.json` + exporter validation + regenerate cores
- [x] `Exercise` models (Kotlin + Swift) + `ExerciseEngine` + tests
- [x] Exercise player UI in LessonDetail (both platforms) + completion marks + telemetry
- [x] Authored exercises for all 18 lessons (72 total)
- [x] Exporter + courses tests green (444/444 Android)

## F4 — Listening

- [x] `shared/listening/en-listening.json` (10 texts) + `scripts/export_listening.py` + tests
- [x] `ListeningData` cores + content tests
- [x] Fifth tab + list + player + written questions (both platforms) + marks + telemetry
- [x] Strings EN/ES (328/328 lockstep); contracts.yml gains both exporters' `--check`

## F5 — Verification

- [x] Android: `testDebugUnitTest` + `lintDebug` + `assembleDebug`
- [x] Backend: `npm run check && npm test` (docker recipe)
- [x] Scripts: unittest discover + both exporters `--check`
- [ ] iOS core `swift test` declared macOS-gate-pending
- [x] `informe-progreso-listening-2026-09-28.md` + verify report
