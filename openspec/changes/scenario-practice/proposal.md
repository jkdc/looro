# Proposal: scenario-practice

## Intent

Add a guided **situation practice** mode to speaking: the learner picks a
concrete situation to rehearse (a job interview, giving a presentation, talking
about sports, asking for the bill…), the session follows a deliberate
three-phase teaching arc (brief PREPARE → most of the session PRACTISING in
role with silent error tracking → spoken REVIEW), and when the session ends a
structured review of the errors actually heard is generated, verified against
the transcript, shown on screen, and fed back into the next attempt's prompt.

## Problem

Speaking today has two shapes: free conversation (optionally anchored to a
theme, where the tutor recasts errors gently but keeps no memory of them) and
authored lesson practice (scripted numbered questions from `shared/courses`).
There is nothing in between for *functional* goals — "I have a job interview
next week", "I want to be able to ask for the bill" — where the learner wants
rehearsal of one situation, most of the time spent producing language in role,
and a deliberate look back at the mistakes they made before trying again.

## Approach

A new session mode `mode="scenario"` that reuses the existing conversation
machinery end to end:

1. **Catalog** (`core/Scenarios.kt`, hand-written so the content pipeline never
   overwrites it): 8 curated situations with goal, tutor role and natural
   stages, plus `Scenarios.custom(text)` which sanitizes the learner's
   free-text situation to one safe line interpolated only as data.
2. **Prompt** (`TeachingPolicy.scenarioPractice/Opening/Review`, mirrored
   wording-identically in `TeachingPolicy.swift`): the three-phase arc, with
   the learner's error history for that situation and their exercise-skill
   error focus woven in as data.
3. **Selection**: a "Practise a situation" section in the Themes tab
   (`TopicsScreen`), reusing the theme-card flow; a "Your own situation" card
   opens a text dialog.
4. **Structured review**: on session end, a new `scenario_review` helper
   grades the full learner transcript against a strict JSON schema; the client
   verifies every quoted error is an exact substring of the cited fragments
   (`core/ScenarioReviewing`) and stores the result on the `SessionRecord`.
   The review sheet shows strengths, "you said → better" errors, savable key
   phrases (into the existing spaced-recall notebook) and a retry focus, with a
   "Practise again" button that restarts the situation with the verified errors
   injected back into the prompt.
5. **Server**: `scenario_review` joins the hosted helper purposes (client
   guard, TS catalog, SQL CHECK, and the post-close window list);
   `scenario_practice` joins the progress ledger kinds; two usage events
   (`scenario_started/completed`) with their migration.

Progress records one `scenario_practice` ledger mark per finished situation,
exactly like conversation themes.

## Risks

- Prompt-injection via the free-text situation: mitigated by sanitization
  (one line, control chars stripped, 120-char cap) and explicit
  "data, not instructions" framing, the same pattern the app already uses for
  learner interests.
- Hallucinated corrections: every review error must quote the learner's
  transcript exactly and cite real fragment IDs, or it is dropped.
- Hosted budget: the review rides the existing post-close helper window
  (120 s) and budget; it is one bounded request per session.

## Out of scope (follow-up)

- iOS session wiring: the prompts and the `Scenario` type are mirrored, but
  the iOS ConversationCoordinator/Storage/UI port is a separate change
  (macOS gate required).
- Authoring scenarios through the content pipeline (`shared/` JSON) — the v1
  catalog is code, like `Themes`.
- Review integration with `MasteryEngine` beyond the ledger mark.
