# Informe — Cierre temario-volume-expansion (volumen B1/B2 inglés)

**Fecha:** 2026-10-02 · **SDD:** `openspec/changes/temario-volume-expansion/` ·
Commits de contenido: `bb609cf` (3 unidades B1/B2), `fe8fbf3` (fixes ronda 1),
`6a3ef4c` (listenings + readings), `bb00c98` (fixes ronda 2)

## Qué se cerró

El change quedaba abierto por su change record; el contenido ya estaba
shipped y verificado tarea a tarea (Tasks 1-2 del SDD, con una ronda de
revisión cada una). Este pase creó `tasks.md` y `verify-report.md` con los
marcadores cerrados desde evidencia — misma honestidad de proceso que el
cierre de multilanguage-parity: **F-V1 y F-V2 se marcan superados con
evidencia** (las olas de relleno/cierre ya entregaron A1 = 40 lecciones en
en-foundations, con `en-foundations-shopping` y `en-foundations-home`, y la
unidad de mercado en los 5 idiomas), no por autoría nueva de este change.

## Qué shipped (contenido nuevo, este SDD)

- **B1 inglés +2 unidades** (`en-foundations-done`, `en-foundations-problems`,
  3 lecciones cada una): causative have/get, phrasal verbs for tasks,
  requests; wish/if only, condicionales, negotiating.
- **B2 inglés +1 unidad** (`en-foundations-innovation`, 3 lecciones):
  relative clauses avanzadas, passive reporting, speculative language.
  Catálogo +5 skills (106→111), todos enseñados, cobertura 100%.
- **3 listenings** (`en-listen-flat-renovation`, `en-listen-wrong-platform`,
  `en-listen-pitch-night`) — en 44→47; **3 readings**
  (`en-read-getting-things-done`, `en-read-what-if`, `en-read-hype-column`)
  — en 22→25.
- Pins Kotlin + Swift actualizados: cursos 27→30 unidades / 82→91
  lecciones; listening 44→47 (+ listas unitID B1/B2); reading 22→25.

## Cifras verificadas (recontadas hoy del JSON canónico)

- **en-foundations: 30 unidades / 91 lecciones**, escalera
  {A1:40, A2:18, B1:20, B2:8, C1:5}, orden de enseñanza monótono.
- **Finales reales B1 = 20 y B2 = 8** (el diseño decía 18 y 9 sobre un
  baseline obsoleto; el delta autorizado +6/+3 sí coincidía).
- Catálogo: 16 cursos, 283 listenings, 125 readings, 30 checkpoints,
  111 skills.

## Notas honestas

- **Repaso en espiral**: la second conditional se enseña en B1
  (problems-hypothetical) antes del tratamiento profundo de la lección B2
  viewpoints — regla aceptada, no duplicado.
- **Dos rondas de revisión con fixes de contenido reales**: ronda 1
  (`fe8fbf3`) — distractor doblemente correcto en negotiating, nota
  garbled, duplicado sin apóstrofe, skill tags decorativos; ronda 2
  (`bb00c98`) — atribución Jonas→Priya en pitch-night, "followed up with"
  idiomático en flat-renovation, "never ends" en what-if.
- **Drift Swift preexistente corregido** (6 entradas viewpoints vs 5 en el
  pin B2 de Swift); su verificación real llega con el gate macOS.
- **Paridad de forma, no de volumen**: los 5 no-EN igualan la escalera en
  forma (PARITY_GAP = 0) pero no llevan el volumen B1/B2 extra de en;
  queda como follow-up.

## Verificación (real, esta sesión — solo lectura)

- `content_health.py` y `--parity`: "no issues found" ✓.
- Exportadores `--check` ×4 (courses/listening/readings/assessments):
  in sync.
- `python3 -m unittest discover -s scripts/tests -t .`: 101 tests OK.
- `check_cross_platform.py`: exit 0.
- Gradle no re-ejecutado: última suite completa verde en `bb00c98`
  (548 tests, 0 fallos, 0 skipped) y `shared/` sin cambios desde
  entonces.

## Pendientes

- Nada del SDD: F-V1…F-V5 todos cerrados. Abiertos por política/fuera de
  alcance: gate macOS de iOS, espejo B1/B2 por idioma, tracks 6-8.
