# Proposal: level-tests

## Intent

Weekly level-verification tests ("¿estoy listo para superar mi nivel?"): one
test per CEFR level (A1–…), runnable any time, 10–15 minutes, **rotating
every week** (never the same test), always showing the last result, and
closing with a directional suggestion — pass → ready for the next level
(with a jump action that never fixes), fail → what to reinforce (weak
categories + concrete units). Designed in
`informe-tests-verificacion-nivel-2026-10-08.md`; decisions closed
2026-10-08 (§11).

## Problem

The Learn tab shows one CEFR level at a time and the placement suggests
where to start, but nothing answers "have I outgrown my level?". The old
checkpoint was a fixed 16-item gate; this change replaces its entry with a
weekly, per-level verification test built from harvested, already-authored
content.

## Approach

- **Harvest, don't author**: `scripts/build_leveltests.py` composes pools per
  language+level from already-validated content — lesson exercises
  (choice/gap/order/written, unit-tagged), checkpoint questions, and
  listening texts (their text doubles as reading passages; their questions
  as comprehension items, spoken via TTS for the listening block). Every
  item keeps its provenance (`unitID`) so a failed test can recommend
  concrete units to reinforce.
- **Weekly deterministic rotation** (`LevelTestEngine`, pure Kotlin): the
  week's test is selected by `seed = sha256(lang|level|isoWeek|version)`
  over the pool with fixed section counts (vocab 4, grammar 8, reading 2
  passages × 3, listening = 2 passages × 2 questions, writing 3 ≈ 25 items,
  10–15 min). No network;
  same test for everyone that week; consecutive weeks share (near) nothing.
  The composed JSON is exactly the contract a v2 weekly endpoint would
  serve (client falls back to the bundled pool offline).
- **Exam mode UI**: no feedback while answering, section headers, final
  review with every explanation, interruption-safe session (same
  `rememberSaveable` pattern as the placement). Grading reuses
  `CheckpointEngine` semantics: overall threshold + per-category floors
  taken from the level's own checkpoint thresholds.
- **Always-visible results**: the level's card in Learn keeps the last
  attempt (date, score, pass/fail, weak units); the ledger records one
  `assessment` completion per attempt (`levelcheck-<lang>-<level>`,
  slug-safe) so results sync with zero schema change.
- **Languages**: EN + ES (same scope as the placement v1). A level ships
  only where the harvest meets the per-section minimums; levels without a
  valid pool show no entry (no empty promises). C1 ships where it reaches
  minimums, else is simply absent.

## Out of scope (this change)

- The v2 weekly backend endpoint (specified in the informe; the client is
  built to consume it without UI changes), iOS parity, pronunciation items
  (device calibration pending), and languages beyond EN/ES.
- The old checkpoint composable stays in the codebase but its Learn entry
  is replaced by this test once green (decision §11.9).
