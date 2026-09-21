# Tasks: themed-conversation-steering

## Task 1 — Android prompt steering

- [ ] 1.1 `TeachingPolicy.voice`: the theme branch becomes an active anchor
      block (`Theme: <title>. <situation> <themeSteering>`); the no-theme
      branch keeps the free-conversation default; new single-literal
      `themeSteering`/`freeConversationContext` functions carry the wording.
- [ ] 1.2 `TeachingPolicy.greeting(language, theme = null)`: with a theme the
      opening question "steps into the theme situation" (`themeOpening`).
- [ ] 1.3 `TeachingPolicy.theme`: mid-session switch anchors the conversation
      (explore the theme's situations, steer back on drift).
- [ ] 1.4 `MuralViewModel` greeting kickoff passes `selectedTheme`.
- [ ] 1.5 JVM `TeachingPolicyTest` covers the four behaviors.

## Task 2 — iOS mirror (reasoning-verified; macOS gate REQUIRED)

- [ ] 2.1 `TeachingPolicy.swift` mirrors 1.1–1.3 wording-identically.
- [ ] 2.2 `ConversationCoordinator` greeting kickoff passes `selectedTheme`.
- [ ] 2.3 `Tests/TeachingPolicyTests.swift` mirrors the JVM cases.

## Task 3 — Record close

- [ ] 3.1 `scripts/check_cross_platform.py` green.
- [ ] 3.2 Full Android suites green (`:app:testDebugUnitTest`,
      `:core:engine-local:testDebugUnitTest`).
- [ ] 3.3 verify-report.md with honest gates (macOS `swift test`, device
      listen-check).
