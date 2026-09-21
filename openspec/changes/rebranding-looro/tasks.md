# Tasks: rebranding-looro

- [x] **T1 — iOS identity**: ordered rename over `apps/ios/**` +
      `scripts/*` (generator, icon tool, cross-platform checker); rename
      `MuralApp.swift`; regenerate project as `Looro.xcodeproj`; remove
      `Mural.xcodeproj`; generator runs clean.
- [x] **T2 — Android identity**: move `java/chat/mural` trees to
      `java/com/jkdc/looro`; rename namespaces, applicationId, resources,
      branded files; JNI `chat_mural` symbols; `:app:testDebugUnitTest`,
      `:core:engine-local:testDebugUnitTest`, `:app:lintDebug` green.
- [ ] **T3 — Backend + CI**: rename package, databases, role, locks, HMAC
      domains, headers, compose project, workflow artifacts; full backend
      docker suite green.
- [ ] **T4 — Strings parity**: Android `values/` + `values-es/` renamed in
      lockstep; `check_cross_platform.py` green; TeachingPolicy wording
      identical on both platforms.
- [ ] **T5 — Support contact + live docs**: centralized placeholder email
      with TODO, refactor second hardcoded mailto, fix its test;
      `SECURITY.md`, README, live docs, top-level release metadata
      (`[REQUIRED]` for publisher-only data); upstream repo links →
      `jkdc/looro`.
- [ ] **T6 — Cross-check + close**: case-insensitive sweep of the live tree
      shows only documented exemptions; all runnable suites green; verify
      report written; macOS gate recorded as pending.
