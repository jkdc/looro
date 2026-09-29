# Tasks: learning-platform

## F1 — Fundamentos del motor

- [x] F1.1 `shared/skills/skill-catalog.json` (≈90 skills, 8 categorías,
      prerequisites) + `shared/skills/cando.json` (matriz universal).
- [x] F1.2 Schema v2: `schemaVersion` + campos de skills en cursos y
      listenings; tagging del contenido inglés (18 lecciones, 15 listenings).
- [x] F1.3 Exportadores: validación contra catálogo (referencias,
      prerequisites, niveles CEFR A0-C1) y emisión de los campos nuevos.
- [x] F1.4 Modelos kt/swift (`Lesson`, `ListeningText`) con los campos nuevos
      y defaults; regenerar datos.
- [x] F1.5 `MasteryEngine` kt+swift (pesos, decaimiento, categoría/global) +
      AppViewModel + sección Skills en la hoja de progreso (EN/ES) + espejo
      iOS.
- [x] F1.6 Tests: MasteryEngine, contenido (referencias válidas), python de
      exportadores; verificación completa.

## F2 — Inglés A1 completo

- [x] F2.1 Dos unidades A1 nuevas (~7 lecciones completas, skills-tagged).
- [x] F2.2 Cuatro listenings A1 nuevos.
- [x] F2.3 Readings: `shared/readings/` + exportador + superficie
      "Listen | Read" + 6 readings A1.
- [x] F2.4 Checkpoint A1: `shared/assessments/` + entrada en Aprender con
      mínimos por categoría.
- [x] F2.5 Drill de pronunciación mínimo en lecciones con `pron:` targets.
- [x] F2.6 Tests + verificación.

## F3 — Inglés A2 completo

- [x] F3.1 Dos unidades A2 nuevas (~6 lecciones).
- [x] F3.2 Cuatro listenings A2 a dos voces + exposureSkills deliberados.
- [x] F3.3 Cuatro readings A2 + checkpoint A2.
- [x] F3.4 Tests + verificación.

## F4 — Personalización

- [x] F4.1 ErrorMemory por skill en Archive + integración en recall.
- [x] F4.2 Daily Plan (5/10/15/20 min) + tarjeta en Aprender.
- [x] F4.3 Recomendador de siguiente lección (prerequisites + mastery).
- [x] F4.4 Contexto de skills débiles en el prompt del tutor (paridad kt/swift).
- [x] F4.5 Tests + verificación.

## F5 — B1

- [x] F5.1 Dos unidades B1 (~6 lecciones) + 4 listenings B1 (2 voces).
- [x] F5.2 Cuatro readings B1 + checkpoint B1.
- [x] F5.3 Tests + verificación.

## F6 — Escalado multiidioma

- [x] F6.1 Tagging es/de/fr/it/pt (lecciones + listenings) vía canDo matrix.
- [x] F6.2 Validadores de cobertura por nivel/idioma.
- [x] F6.3 Tests + verificación.

## F7 — B2/C1 + tracks

- [ ] F7.1 Unidad B2 + unidad C1 + listenings/readings propios + checkpoint B2.
- [ ] F7.2 Track English for Work (curso tipo track + escenarios).
- [ ] F7.3 Tests + verificación + verify-report final.
