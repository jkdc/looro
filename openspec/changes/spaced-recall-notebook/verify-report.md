# Verify report: spaced-recall-notebook (partial: Android complete)

**Date:** 2026-09-22 · **Branch:** `feature/rebranding-looro`

## Delivered in this round (commits `8d10abe`, `9f74da3`)

- **T1 (both cores):** `ExpressionState` in the Archive as an optional list
  (Kotlin default-empty list; Swift optional array) following the repo
  compatibility convention; codec validate/merge extended with a 2,000-entry
  cap and upsert-by-normalized-text; `check_cross_platform.py` pins the new
  struct; shared fixture `archive.json` extended (2 expressions) and asserted
  on both sides.
- **T2 (core):** `RecallEngine` (Kotlin + Swift mirror): idempotent lesson
  seeding, lookup save/upsert, remove, due-queue ordering, ladder grading
  (1/1/4/14 days; lapse → bar 1).
- **T3 (Android complete, iOS UI pending):** Learn tab recall card, typed
  recall session, saved-expressions section in Words, save-for-recall in the
  lookup sheet. EN/ES strings in lockstep (262/262 → 279/279).
- **Emulator battery (fresh install):** onboarding → L1 practice with GLM →
  session end seeds expressions → Recall card shows "7 expressions ready" →
  typed recall grades ("Correct!" verdict, Continue) → Words notebook lists
  entries with bars and Remove. Evidence: `e2e-evidence/bateria-2026-09-22/`
  44–48.

## Gates

| Gate | Result |
|---|---|
| Android `:app` + `:core:engine-local` suites | ✅ 420/420 |
| `check_cross_platform.py` | ✅ exit 0 |
| Emulator manual flow (above) | ✅ |
| iOS `swift test` mirror (`RecallEngineTests`) | ⏳ macOS gate pending |
| **iOS UI mirror (recall screen, notebook)** | ❌ NOT implemented yet — remaining task of this change |

## Verdict

Android delivery complete and user-tested on the emulator; core is mirrored
and pinned for parity. The change stays OPEN until the iOS UI mirror lands.
