# Tasks: listening-tema-levels

## F1 — Schema, exporter, content

- [x] F1.1 `export_listening.py`: require `topic`, validate optional `unitID`
      against `shared/courses/<lang>-foundations.json` (exists + level match),
      raise the question minimum to 5, emit both new fields.
- [x] F1.2 Rewrite `shared/listening/en-listening.json`: topic + unitID on the
      ten existing texts, five new A1 texts, two extra questions everywhere.
- [x] F1.3 Rewrite `shared/listening/{es,fr,it,de,pt}-listening.json`: topic +
      unitID, three new texts each, two extra questions everywhere.
- [x] F1.4 `Listening.kt` / `Listening.swift`: new fields, sorted
      `forLanguage`; regenerate `ListeningData.kt` / `ListeningData.swift`.
- [x] F1.5 Content tests (Kotlin + Swift): counts, ≥5 questions, topic
      presence, unit-level agreement, ordering; answers still grade.

## F2 — Listening points

- [x] F2.1 `LevelEngine.kt` / `LevelEngine.swift`: listening points in
      `stats`, `LevelStats.lessonPoints/listeningPoints`, new ladder.
- [x] F2.2 `AppViewModel.levelStats` passes the language's listening texts.
- [x] F2.3 Level-card breakdown line (Android `LevelCard`, iOS
      `LevelCardView`); iOS call sites updated.
- [x] F2.4 Level tests (Kotlin + Swift): tiers from medals, combined points,
      ladder endpoints, breakdown.

## F3 — Listen tab UI

- [x] F3.1 `ListeningScreen`: level sections with headers, theme/topic row
      labels, detail line.
- [x] F3.2 Strings EN/ES (`listening_theme`, `listening_topic`,
      `learn_level_breakdown`).
- [x] F3.3 iOS `ListeningView` mirror (sections + labels).

## F4 — Verification

- [x] F4.1 `python3 scripts/export_listening.py --check`,
      `python3 scripts/check_cross_platform.py`, script unit tests.
- [x] F4.2 Android unit tests green (`gradlew test`).
- [x] F4.3 verify-report.md + informe-listening-temas-2026-09-29.md.
