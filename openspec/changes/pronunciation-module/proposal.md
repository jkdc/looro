# Proposal: pronunciation-module

## Intent

Implement a real pronunciation module that unblocks the 5 `pron:` skills
currently without content (th, r, short-long-vowels, sentence-stress,
connected-speech). The module must be **fully toggleable** via a preference
so it can be excluded from the initial release without code removal.

## Problem

The skill catalogue already defines 8 pronunciation skills; 3 are tagged in
content via `pronTargets` on lessons, but the drill is cosmetic — no
evidence is recorded, mastery stays 0, and the learner never receives
feedback beyond "repeat after the voice." A real module needs microphone
capture, some form of accuracy check, and skill evidence generation.

## Approach

- **Feature flag**: `preferences.pronunciationEnabled` (Boolean?, default
  `false`). When `false`, the pron drill is hidden, pron skills contribute
  nothing to mastery (already excluded — they have no tagged evidence), and
  the progress sheet omits the pronunciation category.
- **Scoring**: on-device speech recognition (the same engine Talk uses)
  transcribes the learner's attempt. The transcript is compared to the
  target phrase using word-error-rate; a threshold converts WER to a 0-100
  score. No audio fingerprinting or external API.
- **Privacy and lifecycle**: pronunciation requests require an available
  on-device recognizer and set its local-only request flag. Unsupported
  locales remain non-scored listen-and-repeat practice. Each attempt owns a
  unique temporary recording and request-scoped recognition task; denial,
  error, timeout, navigation, and backgrounding settle through one cleanup
  path. Permission explanations state that practice audio stays on device.
- **Evidence**: a new `pronunciation` ledger kind stores (itemID = lesson
  pron target, bestScore). MasteryEngine picks it up at
  `CHECKPOINT_WEIGHT × 0.8`.
- **UI**: the existing listen-and-repeat drill in lessons gains a
  "Check pronunciation" button that records, transcribes, scores and shows
  the result inline. No separate screen.
- **Toggle**: visible in Settings → Preferences as "Pronunciation practice
  (beta)". Turning it off hides the drill and excludes pron skills from
  mastery, but doesn't delete any recorded evidence.

## Scope

- F1: feature flag + preference UI + gate in lesson detail
- F2: speech recognition capture + WER scoring engine (kt+swift)
- F3: `pronunciation` ledger kind (migration + ProgressEngine + sync)
- F4: MasteryEngine integration (pron evidence weight)
- F5: listenings/readings tagged with pron skills for coverage

## Risks

- STT accuracy varies by device and accent; WER is a proxy, not a true
  pronunciation assessment. Calibrate the threshold conservatively (≥50%
  word match = passed).
- The `pronunciation` ledger kind requires a server migration (same
  pattern as `reading` in 033).
- On devices without a working STT engine, the module degrades to
  listen-and-repeat without scoring (already exists; no regression).

## Delivery strategy

Feature flag default `false` → mergeable to main without user-facing
impact. Toggle ON in settings to activate. Conventional commits per phase.

## Out of scope

- Audio waveform comparison or accent scoring
- Real-time feedback during speech
- External pronunciation APIs
- Dedicated pronunciation screen (integrated into lessons only)
