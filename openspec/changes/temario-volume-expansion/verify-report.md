# Verify Report: temario-volume-expansion

**Fecha:** 2026-10-02 · **Branch:** `feature/learning-platform` · Commits de
contenido: `bb609cf` (unidades B1/B2), `fe8fbf3` (fixes ronda 1),
`6a3ef4c` (listenings + readings), `bb00c98` (fixes ronda 2). Cierre del
change record: este commit de docs.

## Qué se entregó

- **F-V3 (B1 +2 unidades)**: `en-foundations-done` y
  `en-foundations-problems` — 6 lecciones B1 nuevas (causative have/get,
  phrasal verbs for tasks, requests; wish/if only, condicionales,
  negotiating). `en-foundations-problems-hypothetical` enseña la second
  conditional antes del tratamiento profundo de la lección B2
  `en-foundations-viewpoints` — **regla de repaso en espiral aceptada**
  (primera exposición B1, profundización B2).
- **F-V4 (B2 +1 unidad)**: `en-foundations-innovation` — 3 lecciones
  (relative clauses avanzadas, passive reporting, speculative language).
  Catálogo de skills +5 (106→111): `grammar:causative-have`,
  `grammar:wish-clauses`, `grammar:third-conditional`,
  `grammar:passive-reporting`, `grammar:speculation` — todos enseñados,
  cobertura 100%, sin UNTAUGHT_SKILL ni PREREQ_INVERSION.
- **F-V5 (listenings + readings)**: 3 listenings de diálogo (44→47 en;
  B1 7→9, B2 7→8) y 3 readings (22→25 en; B1 5→7, B2 3→4), todos
  taggeados a las unidades nuevas.
- **F-V1/F-V2 — superados con evidencia, no autoría nueva**: las olas de
  relleno/cierre de multilanguage-parity ya entregaron A1 = 14 unidades /
  40 lecciones en en-foundations (incluidas `en-foundations-shopping` y
  `en-foundations-home`) y la unidad de mercado en los 5 idiomas
  (`es-foundations-u6`, `fr-foundations-u5`, `it-foundations-u5`,
  `de-foundations-u5`, `pt-foundations-u5`). Evidencia: Verify F5 +
  closure en `openspec/changes/multilanguage-parity/verify-report.md`
  (2026-10-02). Marcadores cerrados a posteriori tras recontar el JSON
  canónico.

## Corrección de aritmética del diseño

design.md decía "B1 12→18" y "B2 6→9"; el baseline real era **B1 = 14** y
**B2 = 5**. El delta autorizado (+6 B1, +3 B2) coincide con la intención;
finales reales: **B1 = 20, B2 = 8**. en-foundations queda en 30 unidades /
91 lecciones con escalera {A1:40, A2:18, B1:20, B2:8, C1:5} — orden de
enseñanza monótono por nivel (`check_teaching_order` verde).

## Pins cambiados (ambos mirrors, Kotlin + Swift)

- Task 1 (`bb609cf`): `CoursesTest.kt` + `CoursesTests.swift` — unidades
  en-foundations 27→30, lecciones 82→91, lista de niveles (B1×6, B2×3).
- Task 2 (`6a3ef4c`): `ListeningContentTest.kt` + `.swift` — listening en
  44→47 (nombres forty-four→forty-seven), listas unitID B1 (+done,
  +problems) y B2 (+innovation); **drift Swift preexistente corregido**
  (la lista B2 de Swift tenía 5 entradas `en-foundations-viewpoints`
  frente a 6 reales; Kotlin ya tenía 6). `ReadingContentTest.kt` + `.swift`
  — readings en 22→25 (twenty-two→twenty-five).
- Ningún pin se movió sin cambio de contenido legítimo detrás; los tests
  python no pinean conteos en.

## Gates ejecutados

| Gate | Resultado |
|---|---|
| `content_health.py` | ✅ "no issues found" — catálogo 16 cursos, 283 listenings, 125 readings, 30 checkpoints, 111 skills |
| `content_health.py --parity` | ✅ "no issues found" — PARITY_GAP = 0 |
| 4 exportadores `--check` (courses/listening/readings/assessments) | ✅ in sync |
| `python3 -m unittest discover -s scripts/tests -t .` | ✅ 101 tests OK |
| `check_cross_platform.py` | ✅ exit 0 |
| Gradle `:app:testDebugUnitTest` | ✅ **548 tests, 0 fallos, 0 skipped** (74 suites) — última ejecución completa en `bb00c98`, NO re-ejecutada en el cierre: `shared/` sin cambios desde entonces (este cierre solo añade docs) |

También verificado en su día por task (misma batería, mismos conteos):
exportadores regenerados de verdad (CoursesData/SkillsData/ListeningData/
ReadingData .kt + .swift) y suites Gradle verdes tras cada ronda de fixes.

## Rondas de revisión (fixes de contenido)

- Ronda 1 (`fe8fbf3`, unidades): distractor "What"→"Shall" en
  problems-negotiating (respuesta doblemente correcta); nota de registro
  garbled en done-phrasals q4; duplicado sin apóstrofe eliminado en
  innovation-speculation q5; skill tags decorativos depurados en
  innovation-relative-clauses (−2, +`function:talk-about-work`).
- Ronda 2 (`bb00c98`, listenings/readings): pitch-night q4 "Jonas"→"Priya"
  (la línea clave es concesión de Priya); flat-renovation "followed him up
  with"→"followed up with" (uso idiomático del phrasal objetivo, también en
  el explain de q4); en-read-what-if "never finishes"→"never ends".

## Follow-ups conocidos (fuera de este change)

- **Espejo de volumen B1/B2 por idioma**: los 5 no-EN igualan la forma en
  40/18/14/5/5 (paridad de forma, PARITY_GAP = 0) pero no llevan las
  unidades B1/B2 nuevas de en; el volumen extra por idioma queda como
  trabajo futuro.
- **Tracks 6-8**: fuera de alcance según proposal (igual que
  pronunciación F2-F5, que tiene SDD propio ya cerrado, y mastery
  snapshots de servidor).
- **Gate macOS**: los pins Swift (`CoursesTests.swift`,
  `ListeningContentTests.swift`, `ReadingContentTests.swift`) se ejecutan
  en el gate macOS de iOS, pendiente por política de repo; el fix del
  drift viewpoints se verificó contra la lista ordenada computada, no en
  máquina.
