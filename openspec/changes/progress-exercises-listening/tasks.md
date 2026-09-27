# Tasks: progress-exercises-listening

## F1 — Backend ledger

- [x] `030_user_progress.sql` (closed kind CHECK, slug CHECK, cascade FK, monotonic merge PK)
- [x] `031_usage_events_catalog_exercises.sql` + `USAGE_CATALOG` entries
- [x] `src/progress.ts` parser/merge/list + `POST/GET /v1/progress` routes
- [x] `user_progress` browsable in the admin panel
- [x] `progress.test.ts` + `progress-http.test.ts`; suite green (416/416)

## F2 — Local ledger, engine, sync, progress UI

- [ ] `CompletionMark` + `Archive.completions` (Kotlin + Swift, validate + merge)
- [ ] `ARCHIVE_STRUCTS` gains `CompletionMark`
- [ ] `ProgressEngine` projection (Kotlin + Swift) + tests
- [ ] Write hooks: lesson end, theme session, recall round, words practice, assessment
- [ ] `ProgressSync` (rollup derivation + remote merge) + `ProgressClient` (OkHttp/URLSession)
- [ ] Learn tab HECHA/percent; progress sheet extensions; strings EN/ES
- [ ] Gradle unit suite green; Swift core mirrored (macOS gate pending)

## F3 — Temario exercises

- [ ] `exercises` schema in `en-foundations.json` + exporter validation + regenerate cores
- [ ] `Exercise` models (Kotlin + Swift) + `ExerciseEngine` + tests
- [ ] Exercise player UI in LessonDetail (both platforms) + completion marks + telemetry
- [ ] Authored exercises for all 18 lessons
- [ ] Exporter + courses tests green

## F4 — Listening

- [ ] `shared/listening/en-listening.json` (10 texts) + `scripts/export_listening.py` + tests
- [ ] `ListeningData` cores + content tests
- [ ] Fifth tab + list + player + written questions (both platforms) + marks + telemetry
- [ ] Strings EN/ES; contracts.yml `--check`

## F5 — Verification

- [ ] Android: `testDebugUnitTest` + `lintDebug` + `assembleDebug`
- [ ] Backend: `npm run check && npm test` (docker recipe)
- [ ] Scripts: unittest discover + both exporters `--check`
- [ ] iOS core `swift test` declared macOS-gate-pending
- [ ] `informe-progreso-listening-YYYY-MM-DD.md` + verify report
