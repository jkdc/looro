# Verify Report: multilanguage-progress

## F1 — Core scoping (d512fcc)

- Android: `testDebugUnitTest` **453/453**. New/updated tests: ambiguous kinds
  (themes, recall rounds, word hashes, "free" assessments) require the language
  stamp while content-id kinds scope by id sets; legacy nil-language marks
  count for streaks/charts but for no language's counts; sync entries carry
  and filter by language; remote merge ignores other languages and stamps
  restored marks; bilingual marks at the same instant are two rows, not a
  duplicate (archive dedupe key now includes language); recall upserts keyed
  by (language, text).
- `check_cross_platform.py` green (both cores gained the optional fields in
  the same change; `ARCHIVE_STRUCTS` optional exemption covers them).
- Swift core mirrored (RecallEngine/ProgressEngine/Models + tests) — macOS
  gate pending per repo policy.

## F2 — Server (500547c)

- `npm run check` clean; `npm test` **417/417** (node:22 + postgres:17 fresh):
  unknown/missing language rejected; same item in two languages is two rows;
  per-language merge isolation (an en replay cannot lift the fr row); cascade
  and idempotency re-verified on the rebuilt PK.

## F3/F4 — UI (5796d56 + 023b83b)

- Learn tab: native-name language pills (disabled during a live session) on
  Android and iOS — also in the empty-course state.
- Progress sheet scope note rewritten (per language); Android history filtered
  to the current language; strings EN/ES 343 + plurals lockstep.

## Manual emulator verification (2026-09-28)

- Switched live Español ↔ English from the new pills (evidence
  `e2e-evidence/manual-2026-09-28/24–27`): Spanish shows "No courses yet"
  with the switcher still visible (a first cut hid it there — fixed in
  023b83b after the emulator run); back on English everything is intact:
  Level 1 · First steps, 33%, ★★★, recall count. Per-language isolation
  observed live.
- logcat: no new crashes.

## Pending gates

- macOS `swift test` / xcodebuild for the mirrored core and app.
- Pixel pass: switching languages with real history on both.
- Second-language temario content: separate authoring project (pipeline ready;
  name the language and it can be piloted).
