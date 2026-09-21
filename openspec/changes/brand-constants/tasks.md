# Tasks: brand-constants

- [ ] **T1 — Constants + wiring**: `branding.properties` at repo root;
      `app/build.gradle.kts` and `core/engine-local/build.gradle.kts` read
      namespace/applicationId from it; `scripts/generate_project.py` reads
      the iOS bundle id; the deploy Makefile derives the applicationId.
- [ ] **T2 — Android structure, generic**: namespace `com.jkdc.app` (+ dir
      moves, imports, JNI symbols), functional class/file/resource names,
      `Theme.App`, root project `app`; JVM suite + lint green.
- [ ] **T3 — iOS + backend structure, generic**: `App.xcodeproj` / target
      `App` / `AppUITests`, `App.swift`, `LearningCore` package, `APP_*`
      config keys, `Icon-1024.png`; backend/CI identifiers → `app*`;
      project regenerated; backend docker suite green.
- [ ] **T4 — `scripts/apply_branding.py` + Palique content pass**: constants
      consumed, live content rebranded (strings both locales, struct, docs,
      store metadata, placeholders).
- [ ] **T5 — Verify + close**: all runnable suites green; sweeps (`mural`=0,
      `looro`=0 in live tree); parity checker green; verify report; macOS
      gate recorded pending.
