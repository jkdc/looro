# Design: multilanguage-parity

## Medidor de paridad

`content_health.py` gana `TARGETS` (por nivel: lecciones/listenings/readings
y checkpoints por idioma) y la comprobación `check_parity()` que emite
`PARITY_GAP(<lang>): <nivel> lecciones 8/40` etc. Se ejecuta con
`--parity` o con el pase completo; el objetivo es cero huecos.

## Tabla objetivo (por idioma, es/fr/it/de/pt)

- Lecciones: A1 40 · A2 18 · B1 14 · B2 5 · C1 5 = 82
- Listenings: A1 15 · A2 12 · B1 7 · B2 7 · C1 3 = 34
- Readings: A1 7 · A2 4 · B1 5 · B2 3 · C1 3 = 22 (umbral 19)
- Checkpoints: A1, A2, B1, B2, C1
- Tracks: 1 (B1, 3 lecciones)
- Pron: 8/8 skills con contenido (drills tras F9)

## Fases

F0 medidor · F1 B2 · F2 C1 · F3-F5 A1 (hogar/barrio, gente/días/tiempo,
salir al mundo) · F6 A2 · F7 B1 · F8 track · F9 pronunciación · F10 cierre.
Cada fase repite el mismo gate: skill → exportadores → `--check` ×4 →
content_health → Gradle (pipefail) → unittests python → cross-platform →
pins en ambos mirrors → commit + verify-report.

## Riesgos

- Volumen editorial (≈350 lecciones nuevas): mitigado por olas temáticas
  commiteables y validadores estrictos.
- Deriva de calidad entre idiomas: la skill fija el shape y los tests de
  closed-shape corren sobre los 5 idiomas.
- Deriva de conteos en tests: los pins multilang usan ≥ y la tabla objetivo
  exacta vive solo en content_health.
