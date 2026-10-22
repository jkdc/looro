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

## 4. Verification
- [x] 4.1 `npx tsc --noEmit` clean; focused suites `# pass 20`, `# fail 0`
- [x] 4.2 Full API suite green (`make test-api`)
- [x] 4.3 Two consecutive browser E2E runs on a fresh stack (37/37 each)
- [x] 4.4 `services/api/docs/admin-panel.md` updated (sidebar table, clickable errors, route drift fixed)
- [x] 4.5 verify-report written
