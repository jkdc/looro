# Verify Report: themed-conversation-steering

Date: 2026-09-21 · Branch: `feature/switchable-conversation-engine` (local)

## What was delivered

- `TeachingPolicy.voice` (both cores): the theme now enters the session
  instructions as an active anchor — `Theme: <title>. <situation>` plus a
  steering sentence (keep the conversation on the theme, explore its concrete
  situations, steer back on drift, deepen command of the language). No theme →
  unchanged free-conversation default.
- `TeachingPolicy.greeting(language, theme)`: with a theme, the opening
  instruction asks its first question "that steps into the theme situation".
  Wired from the greeting kickoff in `MuralViewModel` / `ConversationCoordinator`.
- `TeachingPolicy.theme`: the mid-session switch now re-anchors the live
  conversation (same steering verbs).
- New wording hoisted into `themeSteering` / `freeConversationContext` /
  `themeOpening` single-literal functions so `check_cross_platform.py` pins
  the exact text on both platforms (previously the fallback text lived inside
  interpolations, unverifiable).

## Verified on this machine

- `scripts/check_cross_platform.py` — green (includes the new prompt wording,
  both platforms).
- Android JVM suites — `:app:testDebugUnitTest` 389/389,
  `:core:engine-local:testDebugUnitTest` 5/5, 0 failures (4 new
  `TeachingPolicyTest` cases included; previously recorded 390 total).
- Pre-checker drift run confirmed the checker actually sees the new functions
  (6 expected failure lines before the iOS mirror, then green).

## Gates pending (not run here)

- **macOS gate (REQUIRED before merge):** `swift test --package-path apps/ios`
  including the new `TeachingPolicyTests.swift` (mirror of the JVM cases);
  iOS sources reasoning-verified only.
- **Device listen-check (recommended, not blocking):** start a themed session
  on the Pixel 9 (engine glm) and confirm the opening question references the
  theme and the tutor steers back after a deliberate detour; model behavior is
  prompt-steered, not guaranteed, so this validates perception, not wiring.

## Spec

`specs/teaching-experience/spec.md` — Requirement: Themed conversation
steering, with the three scenarios above.
