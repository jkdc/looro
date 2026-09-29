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

