# Informe: plataforma de competencias + temario completo (2026-09-29)

SDD: `openspec/changes/learning-platform/` · Rama `feature/learning-platform`
(desde `develop`) · 15 commits de feature + 2 de docs · Fuente:
`docs/plan_mejoras_app_idiomas.md` + las 6 olas de contenido posteriores.

## Resultado final del catálogo

| Bloque | Unidades | Lecciones | Listenings | Readings | Checkpoint |
|---|---|---|---|---|---|
| A1 (en) | 10 | 30 | 15 | 7 | ✓ |
| A2 (en) | 6 | 18 | 12 | 4 | ✓ |
| B1 (en) | 4 | 12 | 7 | 5 | ✓ |
| B2 (en) | 2 | 6 | 5 | 2 | ✓ |
| C1 (en) | 1 | 5 | 2 | 2 | ✓ |
| **en-foundations** | **23** | **72** | **41** | **20** | **5 niveles** |
| en-work-track | 1 | 3 | — | — | — |
| en-business-track | 1 | 3 | — | — | — |
| en-technology-track | 1 | 3 | — | — | — |
| en-hospitality-track | 1 | 3 | — | — | — |
| en-healthcare-track | 1 | 3 | — | — | — |
| es/fr/it/de/pt | 2 c/u | 6 c/u | 6 c/u | — | — |

**Skill coverage: 100% en las 8 categorías excepto pron (3/8, bloqueada
por el módulo de pronunciación).**

## Commits (cronológico)

| Commit | Fase | Qué |
|---|---|---|
| `cb3e4b8` | git | plan doc en develop |
| `1fa2729` | SDD | proposal + design + tasks |
| `15b7d15` | F1 | skill catalog, schema v2, MasteryEngine |
| `983ae3e` | F2 | A1 completo: unidades, listenings, readings, checkpoint, pron |
| `5036962` | F3 | A2 completo: comparatives, plans, modals |
| `3786dd1` | F4 | error memory, daily plan, recommender, tutor skill-aware |
| `b44208e` | F5 | B1: experiences, connections |
| `a74706c` | F6 | multi-language skill tagging |
| `2f20e56` | F7 | B2 viewpoints + C1 nuance + English for Work track |
| `341a395` | F8 | A1 a 30 lecciones (3 unidades nuevas) |
| `5036962` | F9 | A2 health & habits |
| `4ce987d` | fix | 64KB method limit: per-unit namespaces |
| `80beadf` | F10 | B1 telling + B2 media + C1 presenting |
| `bc10d04` | F11 | 4 tracks: business, tech, hospitality, healthcare |
| `f57903a` | F12 | A1 segundo bloque en 5 idiomas |
| `c173757` | F13a | B1/B2/C1 volume wave |
| `965eb8b` | F13b | 7 listenings + 2 readings de las unidades nuevas |

## Pendientes (transversales)

- Gate macOS para el núcleo espejado y las vistas iOS.
- Pasada manual en emulador de todas las superficies nuevas.
- Módulo de pronunciación con evidencia (bloquea 5 skills pron).
- Tracks 6-8 del doc (Job Interviews ya está dentro de Work; los
  siguientes serían Sales, Finance, Legal…).
- Extender los 5 idiomas más allá de A1 (B1+ con sus propias unidades).
- Snapshot de mastery y analítica pedagógica (fuera de alcance v1).
