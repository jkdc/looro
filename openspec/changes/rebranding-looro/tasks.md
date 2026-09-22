# Tasks: rebranding-looro

- [x] **T1 — iOS identity**: ordered rename over `apps/ios/**` +
      `scripts/*` (generator, icon tool, cross-platform checker); rename
      `MuralApp.swift`; regenerate project as `Looro.xcodeproj`; remove
      `Mural.xcodeproj`; generator runs clean.
- [x] **T2 — Android identity**: move `java/chat/mural` trees to
      `java/com/jkdc/looro`; rename namespaces, applicationId, resources,
      branded files; JNI `chat_mural` symbols; `:app:testDebugUnitTest`,
      `:core:engine-local:testDebugUnitTest`, `:app:lintDebug` green.
- [x] **T3 — Backend + CI**: rename package, databases, role, locks, HMAC
      domains, headers, compose project, workflow artifacts; full backend
      docker suite green. *(Executed as `looro-*` in 6ee7750; superseded by
      `brand-constants` T3, which went further and made all backend/CI
      identifiers brand-free (`app-server`, `app`, `app-*`) so renames never
      touch them. 2026-09-22: end state re-verified — zero old-brand
      references in `services/api` + CI, one live leak fixed
      (TeachingPolicy lesson prompt persona, both platforms), full backend
      docker suite re-run green on a fresh trust-auth postgres. See
      verify-report addendum.)*
- [x] **T4 — Strings parity**: Android `values/` + `values-es/` renamed in
      lockstep; `check_cross_platform.py` green; TeachingPolicy wording
      identical on both platforms.
- [x] **T5 — Support contact + live docs**: centralized placeholder email
      with TODO, refactor second hardcoded mailto, fix its test;
      `SECURITY.md`, README, live docs, top-level release metadata
      (`[REQUIRED]` for publisher-only data); upstream repo links →
      `jkdc/looro`.
- [x] **T6 — Cross-check + close**: case-insensitive sweep of the live tree
      shows only documented exemptions; all runnable suites green; verify
      report written; macOS gate recorded as pending.
