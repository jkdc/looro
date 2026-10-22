# Tasks: admin-exam-polish

## 1. Persist the real failure reasons
- [x] 1.1 Migration `047_level_test_generation_job_error_details.sql` (`error_details jsonb`, array/≤10 constraint)
- [x] 1.2 `saveState()` writes details on every transition; exported `sanitizeGenerationErrorDetails()`
- [x] 1.3 Status endpoint returns `errors: string[]` per job and on `latestJob`

## 2. Panel
- [x] 2.1 Severity tones for week and job states in `BADGE_COLORS`
- [x] 2.1b Tones rendered with `bg-<tone>-lt`: `badge-<tone>` does not exist in this Tabler and painted neutral grey
- [x] 2.2 Error code is a button; dialog shows code, reasons, context, timestamps, next step
- [x] 2.3 Same dialog reachable from the "Último job" summary
- [x] 2.4 `detailHtml()` for structured payloads; `#detail-body` renders both modes
- [x] 2.5 Four titled sidebar sections; headings are separators, not links

## 3. Tests
- [x] 3.1 Sanitizer unit test (keeps our messages, drops provider bodies/stack traces/user text, caps at 10, falls back to code)
- [x] 3.2 Status-endpoint test asserts `errorCode` + sanitized `errors`
- [x] 3.3 Browser E2E: failed-job fixture in bootstrap, red/green checks on the computed style, dialog contents, nav DOM membership, approved/rejected tones

## 3c. Launcher never reports a skip as a run
- [x] 3c.1 `planGenerationLaunch` marks any existing week `skipped_existing` (approved stays `skipped_approved`)
- [x] 3c.2 Skipped keys create no job and make no provider call (integration test)
- [x] 3c.3 Panel toast separates approved vs already existing and points to «Regenerar»; launcher copy matches
- [x] 3c.4 E2E reproduces the misleading green "completed" before the fix and passes after

## 3d. Runs, retries and provenance
- [x] 3d.1 Migration `049`: one row per attempt (`id` PK, `attempts` ordinal, key index)
- [x] 3d.2 `exists` writes no run; retries append instead of overwriting a failure
- [x] 3d.3 Automatic cap kept for scheduler/CLI; operator launch retries beyond it (`manual: true`)
- [x] 3d.4 Migration `048`: `level_test_weeks.origin` (`user_operator|automatic_worker|unknown`), required in `GenerateOptions`
- [x] 3d.5 Listing returns `origin`; panel paints `Origen` in the table and the review page
- [x] 3d.6 Watch keeps the newest run per key (run log is newest-first)
- [x] 3d.7 Tests: no run on `exists`, manual retry appends, cap stops automatic callers, origin persisted and listed
- [x] 3d.8 E2E: seeded rows show `Automático`, the launched one `Operador`

## 3b. Generation watch
- [x] 3b.1 Cadence fixed at `GENERATION_POLL_MS = 5_000` (was an inline 2 s literal)
- [x] 3b.2 Leaving the view parks the watch (no ghost timer); returning resumes it
- [x] 3b.3 `status.jobs` shape guarded before painting
- [x] 3b.4 E2E: cadence within 4-9 s, retry after a 500, stop after terminal, park on leave, resume on return
- [x] 3b.5 Fix router re-entrancy (concurrent renders wiped the new view and armed two watches)
- [x] 3b.6 Fix leaked timer (stale timer nulled the next watch's handle, duplicating the loop) + single in-flight tick
- [x] 3b.7 Fix CSP-blocked `white-space` on `#detail-body` (move inline style to `app.css`); 41/41 E2E twice

## 4. Verification
- [x] 4.1 `npx tsc --noEmit` clean; focused suites `# pass 20`, `# fail 0`
- [x] 4.2 Full API suite green (`make test-api`)
- [x] 4.3 Two consecutive browser E2E runs on a fresh stack (37/37 each)
- [x] 4.4 `services/api/docs/admin-panel.md` updated (sidebar table, clickable errors, route drift fixed)
- [x] 4.5 verify-report written
