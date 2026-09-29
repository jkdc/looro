# Tasks: multilanguage-progress

## F1 — Core scoping (mirror)

- [x] `CompletionMark.languageID` / `ExpressionState.languageID` (optional, both platforms)
- [x] Write sites stamp the learning language (VM + store)
- [x] `RecallEngine` per (language, text); merge/validate keys language-aware
- [x] `ProgressEngine.stats(…, courses, listeningIDs, languageID)` scoped; `ProgressSync` per language with `language` on the wire
- [x] Voice stats callers filter sessions by language
- [x] Tests: Kotlin 453/453 (scoping, legacy, bilingual dedupe); Swift mirrored

## F2 — Server

- [x] `032_user_progress_language.sql` (PK rebuild, allowlist CHECK)
- [x] `PROGRESS_LANGUAGES` + required wire field + 4-column conflict target + GET rows
- [x] admin browse language column; backend suite 417/417

## F3/F4 — UI (both platforms)

- [x] Quick language switcher in Learn (pills, `!isRunning` guard), also in the empty-course state
- [x] Per-language scope note; Android history filtered; progress sheet all per-language
- [x] Strings EN/ES lockstep (343 + plurals)

## F5 — Verification

- [x] Gradle suite + assembleDebug; backend `check + test` (417); parity + exporters + scripts green
- [x] Emulator: switch Español ↔ English live — empty state for es, en data fully intact, switcher visible in both states (evidence 24–27)
- [x] Informe + verify report + memory
