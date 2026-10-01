# Tasks: pronunciation-module

## F1 — Feature flag + gate

- [x] F1.1 `Preferences.pronunciationEnabled: Boolean? = null` en Models.kt/.swift + ArchiveCodec (nullable → backward compatible)
- [x] F1.2 Settings row "Pronunciation practice (beta)" con Switch (EN/ES)
- [x] F1.3 Gate en LessonDetail: pron drill solo visible si flag ON
- [x] F1.4 SkillCatalogIndex filtra pron skills si flag OFF (mastery los excluye)

## F2 — Scoring engine

- [x] F2.1 `PronunciationEngine` kt+swift: WER scoring (target vs transcript)
- [x] F2.2 Speech capture en LessonDetail (reutiliza el STT de Talk)
- [x] F2.3 Score → feedback inline (✓ o "Try again" + model replay)

## F3 — Ledger kind

- [x] F3.1 `pronunciation` en ProgressEngine kt+swift
- [x] F3.2 Migración 034 server + PROGRESS_KINDS + pin test
- [x] F3.3 Evidence recording en LessonDetail (kind=pronunciation, itemID=lessonID)

## F4 — Mastery integration

- [x] F4.1 MasteryEngine: pronunciation evidence a pron skills (weight 0.8)
- [x] F4.2 Tests: flag OFF excluye pron, flag ON incluye, WER scoring
- [x] F4.3 Verificación completa

## F5 — Contenido pron

- [x] F5.1 Taggear las 5 skills pron sin contenido en lecciones A1-A2 existentes
- [x] F5.2 Listenings con objetivos pron específicos
