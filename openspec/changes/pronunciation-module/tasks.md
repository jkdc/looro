# Tasks: pronunciation-module

## F1 — Feature flag + gate

- [ ] F1.1 `Preferences.pronunciationEnabled: Boolean? = null` en Models.kt/.swift + ArchiveCodec (nullable → backward compatible)
- [ ] F1.2 Settings row "Pronunciation practice (beta)" con Switch (EN/ES)
- [ ] F1.3 Gate en LessonDetail: pron drill solo visible si flag ON
- [ ] F1.4 SkillCatalogIndex filtra pron skills si flag OFF (mastery los excluye)

## F2 — Scoring engine

- [ ] F2.1 `PronunciationEngine` kt+swift: WER scoring (target vs transcript)
- [ ] F2.2 Speech capture en LessonDetail (reutiliza el STT de Talk)
- [ ] F2.3 Score → feedback inline (✓ o "Try again" + model replay)

## F3 — Ledger kind

- [ ] F3.1 `pronunciation` en ProgressEngine kt+swift
- [ ] F3.2 Migración 034 server + PROGRESS_KINDS + pin test
- [ ] F3.3 Evidence recording en LessonDetail (kind=pronunciation, itemID=lessonID)

## F4 — Mastery integration

- [ ] F4.1 MasteryEngine: pronunciation evidence a pron skills (weight 0.8)
- [ ] F4.2 Tests: flag OFF excluye pron, flag ON incluye, WER scoring
- [ ] F4.3 Verificación completa

## F5 — Contenido pron

- [ ] F5.1 Taggear las 5 skills pron sin contenido en lecciones A1-A2 existentes
- [ ] F5.2 Listenings con objetivos pron específicos
