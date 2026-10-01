# Plan: completar los 6 idiomas hasta paridad con el temario inglés

Fecha: 2026-10-01 · Rama: `feature/learning-platform` · Método: SDD por fase
(`openspec/changes/multilanguage-parity/`), una ola = un commit convencional +
verify-report. Ejecución con la skill `looro-content`.

## Objetivo y definición de "hecho"

Cada idioma (es/fr/it/de/pt) iguala la escalera jugable del inglés:
**82 lecciones** (A1 40 · A2 18 · B1 14 · B2 5 · C1 5), **34 listenings**
(15/12/7/7/3), **19+ readings** (7/4/5/3/3), **5 checkpoints** (A1-C1,
16 preguntas cada uno, categorías ≥3 en las umbralizadas) y **1 track
profesional** (B1, 3 lecciones + escenarios Talk). El medidor será una
comprobación nueva `PARITY_GAP` en `content_health.py` — "hecho" es que
reporte cero huecos por idioma.

## Estado de partida (2026-10-01)

| Idioma | Lecciones A1/A2/B1/B2/C1 | Listenings | Readings | Checkpoints | Tracks |
|---|---|---|---|---|---|
| en (ref) | 40/18/14/5/5 = 82 | 44 | 22 | A1-C1 | 5 |
| es | 10/5/2/0/0 = 17 | 10 (6/2/2) | 3 (1/1/1) | A1-B1 | 0 |
| fr | 8/3/2/0/0 = 13 | 10 (6/2/2) | 3 (1/1/1) | A1-B1 | 0 |
| it | 8/2/2/0/0 = 12 | 10 (6/2/2) | 3 (1/1/1) | A1-B1 | 0 |
| de | 8/2/2/0/0 = 12 | 10 (6/2/2) | 3 (1/1/1) | A1-B1 | 0 |
| pt | 8/2/2/0/0 = 12 | 10 (6/2/2) | 3 (1/1/1) | A1-B1 | 0 |

## Fases

### F0 — SDD paraguas + medidor de paridad
- `openspec/changes/multilanguage-parity/{proposal,design,tasks}.md` con
  este plan como fuente.
- `content_health.py`: nueva comprobación `PARITY_GAP` que calcula, por
  idioma y nivel, lecciones/listenings/readings/checkpoints frente a la
  tabla objetivo. Gate de todas las fases siguientes.

### F1 — Escalera completa: B2 × 5 idiomas
- 1 unidad B2 por idioma (2-3 lecciones: opinión con matices, registro
  formal/informal, negociación básica) + 2 listenings B2 (diálogo) +
  1 reading B2 + checkpoint B2 (16 preguntas).
- Volumen: ~15 lecciones, 10 listenings, 5 readings, 5 checkpoints.

### F2 — Cierre de escalera: C1 × 5 idiomas
- 1 unidad C1 por idioma (matiz y precisión, registro, ambigüedad) +
  3 listenings C1 + 3 readings C1 + checkpoint C1.
- Tras F2 los 6 idiomas tienen el recorrido completo A1→C1 jugable.

### F3-F5 — Engorde A1 en 3 olas temáticas (la mayor brecha: +30-32 lecciones/idioma)
Olas de 2 unidades por idioma (4 lecciones cada una, shape completo:
5+ practice, 4 ejercicios, skills/prereqs), cada una con 1 listening A1
por unidad nueva:
- **F3 — Hogar y barrio**: mi casa, mi barrio (+8 lecciones, +2 listenings ×5).
- **F4 — Gente y días**: familia/amigos, rutinas y horas, el tiempo atmosférico
  (+12 lecciones, +3 listenings ×5).
- **F5 — Salir al mundo**: transporte y direcciones, hobbies y planes, salud
  básica, tiendas (+10-12 lecciones, +2-3 listenings ×5). Cierra A1 en 40
  lecciones y 15 listenings por idioma.

### F6 — Engorde A2 (hasta 18 lecciones)
- Pasados en profundidad, modales y permisos, condicionales sencillas,
  citas y arreglos. 3-4 unidades por idioma (~13-16 lecciones), +6
  listenings A2, +3 readings A2 por idioma. Los B1 ya existentes
  (fr/it/de/pt u6, es u5) ganan exposureSkills donde toque.

### F7 — Engorde B1 (hasta 14 lecciones)
- Narrativa con conectores, sugerencias y propuestas, problemas y
  soluciones, trabajo y estudios. 3 unidades por idioma (~12 lecciones),
  +5 listenings B1, +4 readings B1 por idioma.

### F8 — Primer track profesional por idioma (B1)
- Paralelo a *English for Work*: «Español para el trabajo», «Français au
  travail», «Italiano per il lavoro», «Deutsch im Beruf», «Português no
  trabalho» — 3 lecciones + escenarios Talk, reutilizando skills del
  catálogo (talk-about-work, make-requests, negotiate).

### F9 — Módulo de pronunciación real (transversal, feature-flagged)
- Implementar F2-F5 del SDD `pronunciation-module` y autorar los drills
  de las 5 skills sin contenido (`th`, `r`, `sentence-stress`,
  `short-long-vowels`, `connected-speech`) más los sonidos propios de
  cada idioma (nasales pt, «u» fr, ü/ö de, c/g it). El flag sigue OFF
  por defecto; la cobertura de skills pron pasa de 3/8 a 8/8.

### F10 — Cierre y verificación final
- Balance del checkpoint en-c1 (≥2 speaking/writing).
- Pins iOS al día (`CoursesTests.swift` a datos actuales; macOS gate
  sigue pendiente por política de repo).
- `PARITY_GAP` = 0 · exportadores `--check` ×4 · unittests python ·
  suite Gradle (500+) · `check_cross_platform.py` · informe final.

## Gates por fase (idénticos en todas)

1. Autoría con la skill → exportadores → 4 `--check` en sync.
2. `content_health.py` sin avisos nuevos (solo los 7 aceptados hasta F10).
3. Suite Gradle con `pipefail` + unittests python + cross-platform.
4. Pins de conteo actualizados en **ambos** mirrors (Kotlin y Swift).
5. Commit convencional por fase + verify-report actualizado.

## Orden elegido (por qué)

Primero la **escalera completa** (F1-F2: volumen pequeño, valor estructural
alto — ningún idioma se queda sin final de recorrido), luego el **engorde
A1** (F3-F5: donde entra el usuario nuevo, mayor brecha), después A2/B1
(F6-F7), tracks (F8) y pronunciación (F9) como cierre transversal.

## Nota honesta de alcance

Las fases F3-F7 son sobre todo autoría editorial de calidad (~65-70
lecciones + 30 listenings + 12 readings por idioma). El pipeline, los
validadores y la skill ya están probados; el riesgo es de volumen, no de
arquitectura. Cada fase deja el temario jugable y commiteado — no hay
estado intermedio roto.
