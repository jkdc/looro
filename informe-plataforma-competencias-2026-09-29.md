# Informe: plataforma de aprendizaje por competencias (2026-09-29)

SDD: `openspec/changes/learning-platform/` · Rama
`feature/learning-platform` (salida de `develop`, que integra
`feature/rebranding-looro`) · 8 commits, uno por fase · Fuente:
`docs/plan_mejoras_app_idiomas.md`.

## Qué se pidió

Evolucionar de curso lineal a plataforma por competencias: Skill Graph,
separación contenido/progreso/dominio/gamificación, Mastery Engine,
A1-A2 completos, personalización, B1, escalado multiidioma y B2/C1 con
tracks profesionales — todas las fases del roadmap en esta rama, con
mastery sin etiquetas CEFR y catálogo universal etiquetado primero en
inglés.

## Arquitectura resultante

- **Catálogo pedagógico** (`shared/skills/`): 104 skills en 8 categorías
  (grammar, vocab, function, speaking, listening, reading, writing, pron)
  con grafo de prerequisites + matriz Can Do universal (16 competencias).
- **Schema v2**: cursos y listenings llevan `schemaVersion`; las lecciones
  declaran `skills/canDo/prerequisites/exposureSkills` y los listenings
  `skills/exposureSkills`. Los exportadores validan cada referencia y
  emiten todo a Kotlin/Swift, incluido `SkillsData` (categorías + grafo).
- **MasteryEngine** (kt+swift, proyección pura del ledger): mastery por
  skill = mejor evidencia ponderada (checkpoint 1.0 · práctica oral
  guiada 0.9 · ejercicios 0.6 · listening/lectura 0.5) con decaimiento
  exponencial (vida media 60 días). Estructuras en exposición pasiva no
  miden. La hoja de progreso separa las tres métricas: nivel
  (gamificación), % de curso (contenido recorrido) y Habilidades
  (dominio, sin mención CEFR).
- **Reading** como skill de primera clase: `shared/readings/` +
  exportador propio + kind nuevo `reading` en todo el stack (migración
  033, catálogo del servidor, sync, mastery) + biblioteca con estantes
  Listen | Read.
- **Checkpoints reales**: bancos de ítems (`shared/assessments/`) con
  nota global mínima y suelos por categoría; montados sobre el kind
  `assessment` (sin migrar el catálogo del servidor). Escalera A1-B2.
- **Personalización**: ErrorMemory por skill en el Archive (local-first,
  no sincronizado en v1) que alimenta la línea "error patterns" del
  tutor (paridad de prompt pinned), el Daily Plan (5/10/15/20 min:
  recall → lección recomendada → comprensión) y el recomendador
  ("Suggested next" por prerequisites + mastery ≥60).
- **Multiidioma**: los 6 idiomas etiquetados vía matriz Can Do; mastery
  computa en todos; validadores fallan si algo queda sin etiquetar.
- **Tracks**: `en-work-track` (English for Work, B1: reuniones, emails,
  entrevistas) como segunda tarjeta de curso en inglés.

## Estado final del contenido

| Bloque | Unidades | Lecciones | Listenings | Readings | Checkpoint |
|---|---|---|---|---|---|
| A1 (en) | 4 originales + 2 nuevas | 25 | 12 | 6 | A1 |
| A2 (en) | 4 | 12 | 11 | 4 | A2 |
| B1 (en) | 2 | 6 | 4 | 4 | B1 |
| B2 (en) | 1 | 3 | 2 | 2 | B2 |
| C1 (en) | 1 | 3 | — | — | — |
| Track Work (en) | 1 | 3 | — | — | — |
| es/de/fr/it/pt | 1 c/u | 3 c/u | 6 c/u | — | — |

Todas las lecciones y listenings de los 6 idiomas llevan skills.

## Verificación

- Android: **488/488** (contenido, mastery, plan diario, checkpoints,
  readings, niveles). El flaky preexistente de `LocalVoiceTransportTest`
  falló dos veces en suite y pasó siempre en aislado y en re-ejecución.
- Python: **98/98** (exportadores de cursos, listening, readings y
  assessments + paridad + validadores de cobertura).
- `export_*.py --check` (4/4) y `check_cross_platform.py` en verde en
  cada fase; strings EN/ES **387/387**.
- Servidor: migración 033 (`reading` en el CHECK), catálogo y pin de
  test actualizados. Checkpoints sin cambio de catálogo (kind
  `assessment`).

## Commits

`cb3e4b8` docs plan · `1fa2729` SDD paraguas · `15b7d15` F1 · `983ae3e`
F2 · `50e1a5f` F3 · `3786dd1` F4 · `b44208e` F5 · `a74706c` F6 ·
`2f20e56` F7.

## Pendientes honestos

1. Gate macOS para el núcleo espejado (política del repo desde siempre).
2. Pasada manual en emulador de las superficies nuevas (biblioteca,
   checkpoints, plan, track).
3. Volumen editorial: B2 y C1 son bloques completos pero pequeños; B1
   similar. Crecerlos es trabajo editorial sobre el pipeline existente.
4. Snapshot de mastery y analítica pedagógica quedan fuera de alcance
   (documentado en el SDD); el ledger de 2.000 marks sigue siendo
   suficiente con este catálogo.
5. El drill de pronunciación es práctica sin evidencia en v1; medirlo
   requiere el módulo de pronunciación con grabación del doc §19.
