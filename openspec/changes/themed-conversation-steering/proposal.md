# Proposal: themed-conversation-steering

## Intent

Make a chosen conversation theme actively steer the tutor: the conversation
SHALL revolve around the theme, explore the different concrete situations it
suggests, and avoid drifting too far, so the learner deepens their command of
the language across varied situations on that theme.

## Problem

Selecting a theme today is nearly invisible to the model. The theme's
`situation` enters the session instructions only as a passive one-line
`Context:` note among ~19 lines of teaching policy, while the same policy
mandates a fixed generic first greeting ("Say ‘¡Hola!’, ask one small, natural
question"). The practical result — confirmed on 2026-09-21 — is that picking a
theme changes the header title but the conversation neither opens on the theme
nor stays on it, which is not what a learner selecting "A coffee?" or "At the
market" expects. The strong version of topic steering only exists in the
separate searched-current-topic flow (`discuss`), not for the curated theme
grid.

## Approach

Rewrite the three prompt surfaces that carry the theme, in both cores
(`TeachingPolicy.kt` / `TeachingPolicy.swift`, wording kept identical and
pinned by `scripts/check_cross_platform.py`):

1. `voice()` session instructions: replace the passive `Context:` line with an
   active anchor block — `Theme: <title>. <situation>` plus a steering
   sentence: keep the conversation on the theme as the thread, explore its
   different concrete situations, gently steer back when the chat drifts too
   far, and use that variety to deepen the learner's command of the language.
   Without a theme the existing free-conversation default line is unchanged.
2. `greeting()` opening instruction: gains an optional theme parameter; with a
   theme the first question is asked from inside the theme situation.
3. `theme()` mid-session switch: the "Move naturally into this situation"
   command now also anchors the conversation there (same steering verbs).

The steering text lives in new single-literal policy functions
(`themeSteering`, `freeConversationContext`, `themeOpening`) so the
cross-platform checker verifies the exact wording instead of collapsing it
into an opaque interpolation.

## Scope

- **In scope:** the three `TeachingPolicy` surfaces above, their two callers
  (`MuralViewModel` / `ConversationCoordinator` greeting kickoff), JVM tests,
  mirrored iOS tests, and this record.
- **Out of scope (parked):** the searched-current-topic flow (`discuss`) — it
  already carries a strong topic prompt with sourced reference data;
  theme-aware `language.greeting` strings (the fixed greeting utterance stays,
  only its framing question becomes themed); VAD/pedagogy changes.

## Risks

- Prompt wording changes model behavior but not deterministically: the
  steering is verified as text reaching both engines (base instructions +
  greeting command), not as observed dialogue — a device listen-check on one
  theme per engine remains desirable (device gate, not blocking).
- The iOS side is reasoning-verified only on this Linux machine; macOS
  `swift test` is a REQUIRED gate before merge (unchanged policy).
- Editing `voice()`/`greeting()`/`theme()` can silently break the
  cross-platform prompt parity; mitigated by hoisting the new wording into
  checker-verified helper functions and running `check_cross_platform.py`.

## Delivery strategy

`single-pr` — direct conventional commits on
`feature/switchable-conversation-engine` (never pushed): record first, then
one commit per platform, then the verified record close. Android JVM suites +
`check_cross_platform.py` must be green before the close; iOS tests run under
the macOS gate.
