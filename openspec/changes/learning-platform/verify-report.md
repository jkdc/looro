# Verify Report: learning-platform

Todas las fases de `docs/plan_mejoras_app_idiomas.md`, una rama
(`feature/learning-platform`, salida de `develop`), un commit por fase.

## F1 — Fundamentos del motor (15b7d15)

- `shared/skills/skill-catalog.json` (104 skills, 8 categorías, grafo de
  prerequisites) + `shared/skills/cando.json` (16 competencias).
- Schema v2 en cursos y listenings: `schemaVersion` obligatorio, campos
  `skills/canDo/prerequisites/exposureSkills` validados contra el catálogo,
  niveles CEFR acotados A0-C1. Emisión a Kotlin/Swift incluido `SkillsData`
  (id→categoría + prerequisites).
- `MasteryEngine` kt+swift: mejor evidencia ponderada (checkpoint 1.0 ·
  oral 0.9 · ejercicios 0.6 · listening 0.5) con vida media de 60 días;
  categoría y global como medias. Constantes fijadas en
  `check_cross_platform.py`.
- Android **476/476**, python **85/85**, paridad verde, strings 365/365.

## F2 — Inglés A1 completo (983ae3e)

- +2 unidades A1 ("Right now", "Days & feelings", 7 lecciones), +4
  listenings A1, 6 readings A1.
- `reading` como kind de primera clase: migración 033 del servidor,
  `PROGRESS_KINDS` + pin de test actualizados, ProgressEngine (stats/sync/
  merge) y MasteryEngine (peso 0.5) en ambos núcleos; superficie
  biblioteca con estantes Listen | Read.
- Checkpoint A1 (16 ítems, mínimos por categoría + global) montado sobre
  el kind `assessment` existente; tarjeta en Aprender cuando todas las
  lecciones A1 están intentadas. Drill de pronunciación (escucha y
  repite) en lecciones con targets `pron:`; sin evidencia en v1,
  documentado.
- Android verde, cuatro `--check` en sync, strings 378/378.

## F3 — Inglés A2 completo (50e1a5f)

- +2 unidades A2 ("Comparing & choosing", "Plans & advice", 6 lecciones),
  +4 listenings A2 en formato diálogo, 4 readings A2, checkpoint A2.
  `exposureSkills` deliberados (pasado en textos u5, past continuous en
  small-accident).
- Android verde, sync y paridad verde.

## F4 — Personalización (3786dd1)

- ErrorMemory por skill en `Archive.errors` (local-first, merge
  monótono por count, no sincronizado en v1, tope 100); hook en
  ExerciseScreen (Android) y en el flujo de ejercicios iOS.
- Línea "error patterns" en el prompt del tutor con paridad de redacción
  verificada; DailyPlanEngine (5/10/15/20 min) + tarjeta con chips de
  presupuesto en Aprender; recomendador (primera lección no intentada con
  prerequisites con mastery ≥60) con badge "Suggested next".
- Android verde, paridad verde, strings 387/387.

## F5 — B1 (b44208e)

- +2 unidades B1 (present perfect, past continuous narrativo, used to vs
  be used to, first conditional, relative clauses, conectores), 4
  listenings B1 con inferencia, 4 readings B1, checkpoint B1 (16 ítems).
- Android verde, sync y paridad verde.

## F6 — Multiidioma (a74706c)

- Lecciones y listenings de es/de/fr/it/pt etiquetados con el catálogo
  universal (canDo compartido); mastery activo en los 6 idiomas.
- Validadores nuevos: ninguna lección ni listening sin etiquetar en
  ningún idioma. python **98/98**, Android verde, paridad verde.

## F7 — B2/C1 + track (2f20e56)

- +2 unidades finales (B2 "Viewpoints": second conditional, pasiva,
  negociación; C1 "Nuance & register": registro, chunks, hedging), 2
  listenings B2 (debate, negociación salarial), 2 readings B2 (debate
  IA-email, informe de mercado con hedging) y checkpoint B2.
- Track **English for Work** (`en-work-track`, B1): reuniones, emails
  profesionales y entrevistas; aparece como segunda tarjeta de curso.
- Android **488/488**, cuatro `--check` en sync, paridad verde, strings
  387/387. El flaky conocido de `LocalVoiceTransportTest` falló una vez
  en suite y pasó en aislado y en la re-ejecución completa (preexistente).

## Estado final del contenido

- en-foundations: 14 unidades (8 A1, 4 A2, 2 B1, 1 B2, 1 C1), 43
  lecciones, todas con skills/canDo/prerequisites.
- en-work-track: 1 unidad, 3 lecciones (B1, funciones de trabajo).
- Listenings: en 29 (A1 12 · A2 11 · B1 4 · B2 2), resto de idiomas 6.
- Readings: en 16 (A1 6 · A2 4 · B1 4 · B2 2).
- Checkpoints: A1, A2, B1, B2 (16 ítems cada uno).

## Pendientes (transversales)

- Gate macOS (`swift test` / xcodebuild) para todo el núcleo espejado y
  las vistas iOS — política del repo desde el primer cambio.
- Pasada manual en emulador/dispositivo: biblioteca Listen|Read,
  checkpoints de principio a fin, tarjeta de plan diario, badge
  recomendado y la segunda tarjeta de curso (track).
- Volumen editorial: B2 (2 listenings/readings) y C1 (0 listenings
  propios) son bloques completos pero pequeños, listos para crecer sobre
  el mismo pipeline.
- Snapshot de mastery y analítica pedagógica (doc §36-37, §70-71):
  deliberadamente fuera de alcance; el ledger de 2.000 marks sigue
  siendo suficiente con el catálogo actual.

## Post-F7: olas de contenido con la skill looro-content

- **B1 wave 1 (da40340):** unidad "Changes" (will vs going to, reported
  speech, phrasal verbs) + 2 listenings; cierra
  grammar:reported-speech-basic, function:describe-goals y estrena
  grammar:phrasal-verbs (nuevo en catálogo).
- **Tanda A1/A2 (cfb0f8c):** unidad "On the move" (transporte, commute;
  writing:sentence) y "Staying somewhere" (quantifiers — cierra
  grammar:quantities —, accommodation, mensajes — writing:message).
- **Tanda B1/B2/C1 (esta):** sugerencias (cierra
  function:make-suggestions) y escritura narrativa (writing:story) en B1;
  dos listenings B2 (noticia en pasiva con connected-speech, podcast de
  hábitos); bloque C1 completo: lección "academic voice", listening
  "keynote", readings op-ed y memo institucional, y checkpoint-en-c1
  (16 ítems) cerrando la escalera A1-C1.
- **Cobertura final: 100/100 en las 8 categorías salvo pron 3/8**
  (bloqueada por el módulo de pronunciación, pendiente transversal).
- Android 488/488 · python 98/98 · 4 exportadores en sync · paridad
  verde · strings 387/387.

## Estado final del contenido (actualizado)

- en-foundations: 17 unidades, 54 lecciones (A1 7u/21l · A2 5u/15l ·
  B1 3u/9l · B2 1u/3l · C1 1u/4l).
- Listenings: en 34 (A1 12 · A2 11 · B1 6 · B2 4 · C1 1); resto 6 c/u.
- Readings: en 18 (A1 6 · A2 4 · B1 4 · B2 2 · C1 2).
- Checkpoints: A1, A2, B1, B2, **C1** — escalera completa.
- Quedan fuera del 100%: volumen A1 hacia 30-40 lecciones (21 hoy),
  engorde B2/C1, tracks 2-6 del doc §12, y el A1 de los otros idiomas.

## Fix transversal: troceo de datos generados

El crecimiento del catálogo (63+ lecciones) hizo que `CoursesData.<clinit>`
superara el límite de la JVM de 64 KB por método (fallo al compilar F9).
Los cuatro exportadores ahora emiten **un namespace por unidad/texto**
(objects Kotlin con `<clinit>` propio, enums Swift con `static let` lazy)
y montan `all` por referencias — el pipeline vuelve a escalar sin techo.
Verificado con la suite completa en verde tras el cambio.

## Post-F7: temario completion (F8-F13)

- **F8 A1 to 30 (341a395):** 3 new A1 units (Out and about, My town,
  Family & friends, 9 lessons) reaching the doc §7 minimum of 30.
- **F9 A2 health (5036962 + 4ce987d):** new A2 unit Health & habits
  (symptoms, should advice, used to vs now). Hit the JVM 64 KB method
  limit — fixed by splitting all generated data into per-unit/text
  objects (4ce987d).
- **F10 B1/B2/C1 (80beadf):** B1 "Telling it well" (collocations, news
  reactions, problem structure), B2 "Media & argument" (news language
  with passives, opinion pieces), C1 "Presenting with confidence".
- **F11 tracks (bc10d04):** Business, Technology, Hospitality, Healthcare
  — 4 new B1 tracks (12 lessons) alongside English for Work. Registry fix:
  unit-order resolution across all courses of a language.
- **F12 multi-language A1 (f57903a):** second A1 unit in all 5
  non-English languages (Mi día a día / Mon quotidien / La mia giornata /
  Mein Alltag / Meu dia a dia) — routines, neighbourhood, plans.
- **F13 volume (c173757 + this commit):** 7 new listenings (A1 town,
  A2 health call, B1 news swap, B2 news desk + podcast, C1 keynote +
  radio debate), 2 new readings (A1 neighbourhood blog, B1 Tuscany
  story).

## Final catalogue state

- en-foundations: 23 units, 72 lessons (A1 30 · A2 18 · B1 12 · B2 6 · C1 5)
- en tracks: 6 tracks × 3 lessons (Work, Business, Technology, Hospitality,
  Healthcare + Foundations)
- en listenings: 41 (A1 15 · A2 12 · B1 7 · B2 5 · C1 2)
- en readings: 20 (A1 7 · A2 4 · B1 5 · B2 2 · C1 2)
- Checkpoints: A1, A2, B1, B2, C1 — complete ladder
- es/fr/it/de/pt: 2 units, 6 lessons each — all skill-tagged
- Skill coverage: **100% in all 8 categories except pron (3/8)**
- Android **488/488** · python **98/98** · 4 exporters in sync ·
  cross-platform parity green · strings EN/ES 387/387

## Still open (transversal, as before)

- macOS gate for the mirrored core and app UI.
- Manual emulator pass of the new surfaces.
- Pronunciation module (blocks 5 pron skills).
- Volume growth: A1 30→40 (doc target), B2/C1 listenings, tracks 6-8
  (doc §12 remaining), and extending the other 5 languages beyond A1.


## Wave multiidioma (2026-10-01, dbed393 + 4b077fe)

- Cursos: es 7 unidades/17 lecciones · fr 6/13 · it/de/pt 6/12 — todos
  con mercado (A1), comparaciones/planes (A2) y experiencias (B1).
- Listenings: 8 por idioma (6 A1 + 2 A2 nuevos: piso y plan del finde).
- Readings: primeros 2 por idioma (A1+A2); 32 en total.
- Checkpoints: A1+A2 × 5 idiomas (16 preguntas, umbrales 70/60/60,
  todas las categorías ≥2/16). 15 checkpoints en el catálogo.
- Content Health: 7 avisos, todos preexistentes y aceptados (5 pron
  tras feature flag, 2 de balance en el c1 inglés).
- Android **494/494** (3 nuevos: multilang listening/reading +
  CheckpointCoverageTest) · cross-platform green · exportadores en sync.
- Nota de proceso: tres ejecuciones previas "verdes" eran falsos
  positivos del pipe con `tail` (exit code enmascarado); re-ejecutadas
  con `pipefail` y arreglados los 3 fallos reales (shape de las 8
  lecciones de mercado, conteo por idioma de listenings, assert de
  niveles de checkpoint).

## Still open (transversal, actualizado)

- macOS gate; pron skills 5 sin contenido (módulo feature-flagged);
  balance del checkpoint en-c1; listenings B1+ y más readings para los
  5 idiomas; checkpoints B1 por idioma.

## Ola B1 multiidioma (2026-10-01, d9e18fe — con la skill looro-content)

- Listenings: 2 B1 por idioma (diálogo, 90-120 palabras), atados a las
  unidades B1; 10 por idioma (6 A1 · 2 A2 · 2 B1).
- Readings: primer B1 por idioma (el viaje inolvidable); 3 por idioma.
- Checkpoints: B1 × 5 idiomas (16 preguntas; reparto gramática 7 ·
  vocabulario 3 · función 2 · escrita 2 · oral 2, umbrales ≥3 en las
  categorías con umbral). 20 checkpoints en el catálogo.
- Validadores: los 4 exportadores `--check` in sync · content_health
  en los 7 avisos aceptados · unittests python 98/98 · cross-platform
  green · Android 494/494 (con pipefail; pins de conteo a 10 en ambos
  mirrors, incluido el espejo Swift que había quedado en 6).

## Still open (transversal)

- macOS gate; pron skills 5 sin contenido (módulo feature-flagged);
  balance del checkpoint en-c1; listenings/readings B2 para los 5
  idiomas; checkpoints B2 por idioma; segundo reading B1.
