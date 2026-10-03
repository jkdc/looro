# Tasks: temario-volume-expansion

- [x] F-V1 A1 inglés 30→40 lecciones (+3 unidades deep-dive) — **superado con
      evidencia**: la ola de relleno/cierre de multilanguage-parity ya entregó
      el volumen (A1 = 14 unidades / 40 lecciones en en-foundations.json,
      incluidas `en-foundations-shopping` y `en-foundations-home`). Cierre a
      posteriori por conteo, no autoría nueva. Evidencia:
      `openspec/changes/multilanguage-parity/verify-report.md` (Verify F5 +
      closure, 2026-10-02) y `tasks.md` F5/F6/F7.
- [x] F-V2 A1 es/fr/it/de/pt +1 unidad (comida/mercado) — **superado con
      evidencia**: unidad de mercado presente en los 5 (`es-foundations-u6`
      "En el mercado", `fr-foundations-u5` "Au marché", `it-foundations-u5`
      "Al mercado", `de-foundations-u5` "Einkaufen", `pt-foundations-u5`
      "Na feira"), con A1 = 40 lecciones en los seis foundations. Misma
      evidencia que F-V1.
- [x] F-V3 B1 inglés +2 unidades — shipped en `bb609cf` (+ fix `fe8fbf3`):
      `en-foundations-done` "Getting things done" (causative, phrasals,
      requests) y `en-foundations-problems` "Dealing with problems" (wish,
      hypothetical, negotiating) — 3 lecciones cada una, tras
      `en-foundations-telling`.
- [x] F-V4 B2 inglés +1 unidad — shipped en `bb609cf` (+ fix `fe8fbf3`):
      `en-foundations-innovation` "Innovation & change" (relative clauses,
      passive reporting, speculation), 3 lecciones, tras
      `en-foundations-media` y antes de `en-foundations-nuance`.
      Skills nuevos en catálogo (+5, 106→111): `grammar:causative-have`,
      `grammar:wish-clauses`, `grammar:third-conditional`,
      `grammar:passive-reporting`, `grammar:speculation` — todos enseñados,
      cobertura 100%.
- [x] F-V5 listenings y readings para las unidades nuevas — shipped en
      `6a3ef4c` (+ fix `bb00c98`): listenings `en-listen-flat-renovation`
      (B1, done), `en-listen-wrong-platform` (B1, problems),
      `en-listen-pitch-night` (B2, innovation) — 44→47; readings
      `en-read-getting-things-done` (B1, done), `en-read-what-if`
      (B1, problems), `en-read-hype-column` (B2, innovation) — 22→25.

## Notas de diseño (honestas)

- **Regla de repaso en espiral**: `en-foundations-problems-hypothetical`
  (B1) enseña la second conditional ANTES de que la lección B2
  `en-foundations-viewpoints` la retrate en profundidad. Aceptado como
  repaso en espiral (primera exposición B1, tratamiento avanzado B2), no
  como duplicado.
- **Aritmética del diseño obsoleta**: design.md decía "B1 12→18" y
  "B2 6→9"; el baseline real era B1 = 14 y B2 = 5 lecciones. El delta
  autorizado (+6 B1, +3 B2) coincide con la intención del diseño; los
  finales reales son **B1 = 20** y **B2 = 8** lecciones.
- Fuera de alcance (sin cambios): pronunciación F2-F5 (SDD propio),
  mastery snapshots de servidor, tracks 6-8.

## Verify (2026-10-02, cierre)

- `content_health.py` y `--parity`: "no issues found" ✓ (PARITY_GAP = 0).
  Catálogo: 16 cursos, 283 listenings, 125 readings, 30 checkpoints,
  111 skills.
- 4 exportadores `--check` in sync (courses, listening, readings,
  assessments).
- `python3 -m unittest discover -s scripts/tests -t .`: 101 tests OK.
- `check_cross_platform.py`: exit 0.
- Gradle NO re-ejecutado en el cierre: última suite completa verde en
  `bb00c98` (548 tests, 0 fallos, 0 skipped) y `shared/` sin cambios
  desde entonces (este cierre solo añade docs). Detalle completo en
  `verify-report.md`.
