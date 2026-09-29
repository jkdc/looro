# Proposal: multilanguage-progress

## Intent

The learner should be able to practise several languages at the same time, and
every metric, completed theme, level and streak must belong to one language.
Question posed by Joaquín: would a database (for temarios, exercises, etc.,
organised by language) be the right tool? Analysis + decision in
`informe-multiidioma-2026-09-28.md`.

## Problem

Language switching already existed (Settings, nothing cleared, everything
re-filtered), and most state was already language-keyed: session.languageID,
prefixed content ids (`en-foundations-…`, `en-listen-…`), word keys
(`lang|lemma|meaning`), course-scoped levels. What mixed languages:

- the recall notebook (`ExpressionState` had no language; upsert key was text
  alone, so identical text in two languages collided);
- voice stats (totals, streaks, 14-day chart over all sessions);
- `ProgressEngine` (streaks + 30-day chart global; `themesPractised` — the 24
  conversation themes have GLOBAL ids shared across languages; `recall_round`
  constant item; `"free"` assessments; mixed totals);
- the sync wire and the server ledger (`user_progress` had no language, so
  shared ids collided and the 200-entry cap evicted across languages);
- Android's history sheet could resume another language's session.

## Approach

**Scoped read-model, no storage rewrite.** Content stays in canonical JSON
(the pipeline already accepts N languages and `forLanguage` filters);
local progress stays in the archive (nullable-field pattern proven with
`completions`); the server ledger gains a `language` column.

- `CompletionMark.languageID` / `ExpressionState.languageID` — optional, nil
  only on legacy entries (grandfathered: visible for streaks/charts in every
  language, adopted by the current language on next text match, and ambiguous
  kinds count for no language).
- All write sites stamp the current learning language.
- `RecallEngine` keys and queues per (language, text); same key in both
  archives' merge.
- `ProgressEngine.stats` takes `languageID`, `courses` and `listeningIDs` of
  the language: streaks/charts over that language's marks+sessions; ambiguous
  counts require the stamp; content-id counts match against the language's
  id sets (robust even for legacy rows).
- `ProgressSync` uploads one language at a time (cap 200 per language, fair)
  with `language` on every entry; remote merges respect it.
- Server: migration 032 — `language text NOT NULL DEFAULT 'legacy'`, PK
  `(account_id, kind, item_id, language)`, CHECK allowlist `PROGRESS_LANGUAGES`
  (8 registry ids + legacy); wire entry field required; merge/conflict and GET
  carry it; admin browse gains the column.
- UI: quick language switcher (native-name pills) in the Learn tab on both
  platforms — also in the empty-course state, so a language without a temario
  never strands the user; progress sheet scope note rewritten (everything is
  per language now); Android history filtered to the current language.

## Out of scope

- Authoring a second temario/listening set (pipeline ready; content is its own
  project — pilot possible on request).
- A language dimension in usage telemetry (courseID already embeds language;
  the catalog is content-free and closed).
- "Delete all" remains an account-wide action.
- Room/SwiftData rewrite: rejected — this is a scoping problem, not storage.

## Risks

- Postgres PK rebuild on existing rows (dev-only data).
- Legacy nil-language entries count as global activity (streaks/charts) but
  not for ambiguous per-language counts — deliberate, documented.
- Both platforms must add the optional fields in the same change
  (`check_cross_platform` archive-fields symmetry).
