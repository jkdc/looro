# Tasks: listening-tema-levels

## F1 — Schema, exporter, content

- [ ] F1.1 `export_listening.py`: require `topic`, validate optional `unitID`
      against `shared/courses/<lang>-foundations.json` (exists + level match),
      raise the question minimum to 5, emit both new fields.
- [ ] F1.2 Rewrite `shared/listening/en-listening.json`: topic + unitID on the
      ten existing texts, five new A1 texts, two extra questions everywhere.
- [ ] F1.3 Rewrite `shared/listening/{es,fr,it,de,pt}-listening.json`: topic +
      unitID, three new texts each, two extra questions everywhere.
- [ ] F1.4 `Listening.kt` / `Listening.swift`: new fields, sorted
      `forLanguage`; regenerate `ListeningData.kt` / `ListeningData.swift`.
- [ ] F1.5 Content tests (Kotlin + Swift): counts, ≥5 questions, topic
      presence, unit-level agreement, ordering; answers still grade.

## F2 — Listening points

- [ ] F2.1 `LevelEngine.kt` / `LevelEngine.swift`: listening points in
      `stats`, `LevelStats.lessonPoints/listeningPoints`, new ladder.
- [ ] F2.2 `AppViewModel.levelStats` passes the language's listening texts.
- [ ] F2.3 Level-card breakdown line (Android `LevelCard`, iOS
      `LevelCardView`); iOS call sites updated.
- [ ] F2.4 Level tests (Kotlin + Swift): tiers from medals, combined points,
      ladder endpoints, breakdown.

## F3 — Listen tab UI

- [ ] F3.1 `ListeningScreen`: level sections with headers, theme/topic row
      labels, detail line.
- [ ] F3.2 Strings EN/ES (`listening_theme`, `listening_topic`,
      `learn_level_breakdown`).
- [ ] F3.3 iOS `ListeningView` mirror (sections + labels).

## F4 — Verification

- [ ] F4.1 `python3 scripts/export_listening.py --check`,
      `python3 scripts/check_cross_platform.py`, script unit tests.
- [ ] F4.2 Android unit tests green (`gradlew test`).
- [ ] F4.3 verify-report.md + informe-listening-temas-2026-09-29.md.
