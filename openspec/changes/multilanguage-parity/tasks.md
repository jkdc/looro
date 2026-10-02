# Tasks: multilanguage-parity

- [x] F0 SDD paraguas + `PARITY_GAP` en content_health
- [x] F1 B2 × 5 idiomas — 2 unidades/5 lecciones + 2 listenings + reading + checkpoint por idioma
- [x] F2 C1 × 5 idiomas — unidad de 3 lecciones + 3 listenings + 3 readings + checkpoint
- [x] F3 A1 ola 1 — hogar y barrio (2u × 5 idiomas + listenings)
- [x] F4 A1 ola 2 — gente, rutinas, el tiempo (hasta ~30l)
- [x] F5 A1 ola 3 — transporte, hobbies, salud, tiendas (A1=40l, 15 listenings) — cerrado por verificación 2026-10-02
- [x] F6/F7 en progreso: ola de relleno A1+A2+B1+C1 aplicada — forma no-EN = en recontada el 2026-10-02 (v. verify F6 y F10)
- [x] F7 B1 hasta 14 lecciones — cerrado por las olas de relleno + track
- [x] F8 track profesional por idioma (<lang>-work-track.json, B1 3 lecciones)
- [x] F9 pronunciación F2-F5 — módulo completo (commit f2ba4c7; SDD pronunciation-module con tasks [x] y verify-report propio)
- [x] F10 cierre: en-c1 balanceado, PARITY_GAP=0, informe final

## Verify F1 (2026-10-01)

- B2 completo por idioma: 2 unidades (5 lecciones), 2 listenings de
  diálogo, 1 reading (columna de opinión), checkpoint B2 de 16 preguntas
  (7/3/2/2/2). PARITY_GAP de B2 lecciones cerrado.
- Validadores cazaron: orders con 2 respuestas y 15 prompts que
  contenían la respuesta (todos corregidos antes de los tests).
- Suite 494/494 con pipefail; pins a 12 listenings/idioma en ambos
  mirrors; 4 --check en sync; cross-platform green.

## Verify F2 (2026-10-01)

- C1 por idioma: unidad "Matices y registro" (3 lecciones), 3 listenings
  (debate radio, charla, podcast), 3 readings (ensayo, carta directiva,
  reseña), checkpoint C1 16 preguntas (7/3/2/2/2).
- Autoría en paralelo: 5 agentes (uno por idioma) con auto-validación;
  el único riesgo (archivo compartido de checkpoints) verificado sin
  duplicados; exportadores reales y Content Health en verde.
- Escalera A1→C1 COMPLETA en los 6 idiomas. Quedan en paridad: lecciones
  A1/A2/B1, listenings A1/A2/B1/B2, readings y tracks.

## Verify F3 (2026-10-01)

- A1 "hogar y barrio" × 5 idiomas: 2 unidades de 3 lecciones
  (casa con hay, preposiciones de lugar, compañeros; preguntar por
  sitios, direcciones, frutería) + 2 listenings + 1 reading.
- Gaps de paridad: 75 → 60. Gradle 494/494, sin avisos nuevos.

## Verify F4 (2026-10-01)
- A1 "gente y días" × 5: 2 unidades de 3 lecciones (familia, descripciones,
  llamada; rutina con frecuencia, clima, gustos) + 2 listenings + 1 reading.
- Gradle 494/494; sin avisos nuevos.

## Verify F6a + split 64KB (2026-10-01)

- Ola de relleno por idioma: unidad A1 "a la mesa" (3l), A2 "quedar
  bien" (3l), B1 "contar historias" (3l) y 4ª lección C1 "debatir", con
  4 listenings y 1 reading nuevos por idioma.
- ListeningData reventó el límite de método de la JVM (139 textos en un
  clinit); Kotlin ahora emite un objeto por ítem y la lista se monta
  perezosa. Mismo split aplicado a ReadingData y CheckpointsData.
- Gradle 494/494. Gaps de lecciones por idioma: A1 -9/-11, A2 -10/-13,
  B1 -9, C1 -1.

## Verify F10 — CIERRE (2026-10-01)

- **PARITY_GAP = 0**: los 5 idiomas igualan la forma de la escalera
  inglesa: 40/18/14/5/5 lecciones (con track), 47 listenings
  (16/12/7/7/5... objetivos 15/12/7/7/3 superados), 20 readings,
  5 checkpoints y 1 track profesional cada uno.
- Catalogo: 16 cursos, 280 listenings, 122 readings, 30 checkpoints.
- en-c1 balanceado (2 speaking / 2 writing). Content Health: solo los
  5 avisos pron (detras del feature flag). Gradle 494/494.
- Excepciones declaradas: F9 (implementacion del modulo de
  pronunciacion F2-F5) sigue pendiente como tarea de motor; el gate
  macOS de iOS sigue pendiente por politica de repo.

## Verify F5 + closure (2026-10-02)

- **Honestidad de proceso**: F5 se cierra por verificación, no por
  autoría nueva. El contenido de la ola 3 aterrizó en d098876
  (transporte + salud ×5, con listenings y readings) y se completó con
  las olas de relleno/cierre (a88f757, abf2379, 1a5a9a2, ab48a07) —
  ninguna cerró el marcador en su día; este pase lo hace tras contar.
- **A1 = 14 unidades / 40 lecciones en los SEIS foundations**
  (en/es/fr/de/it/pt), recontado de shared/courses/*-foundations.json.
- **Listenings A1**: en 15, resto 16 (objetivo ≥15). Escalera no-EN
  16/12/7/7/5 (fr B1=8) supera objetivos 15/12/7/7/3; en 15/12/7/7/3
  exacto en objetivos.
- **Temas F5 en los 5 no-EN**: transporte (Unterwegs / En movimiento /
  En mouvement / In movimento / Em movimento), tiendas (Einkaufen / En
  el mercado / Au marché / Al mercato / Na feira, más ropa y tallas ×5),
  salud (Ich fühle mich gut / Me siento bien / Je me sens bien /
  Mi sento bene / Me sinto bem), ocio con `vocab:hobbies` taggeado en
  los 5 (de u13 + u27 Freizeit; es u14, fr u13, it u13, pt u13) y
  celebraciones (Wir feiern / Celebra / On fête / Festa! / Hora de
  festejar).
- **F6/F7 cerrados con evidencia**: forma no-EN = en 40/18/14/5/5
  (B1 11 + 3 de track), listenings A2 12/12, readings A2 4/4
  (verify F6a + F10, recontados hoy).
- **F9 cerrado**: módulo de pronunciación completo en f2ba4c7, con su
  SDD pronunciation-module (tasks [x], verify-report) — la excepción
  declarada en Verify F10 queda resuelta.
- **Batería de hoy (solo lectura)**: content_health y --parity "no
  issues found"; 5 exportadores --check in sync (courses, listening,
  readings, assessments, android_content); catálogo 16 cursos,
  280 listenings, 122 readings, 30 checkpoints. shared/ sin cambios
  desde 88de326 (Gradle 494/494 de esta mañana) — suite no
  re-ejecutada por política de esta tarea.
