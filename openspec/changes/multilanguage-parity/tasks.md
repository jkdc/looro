# Tasks: multilanguage-parity

- [x] F0 SDD paraguas + `PARITY_GAP` en content_health
- [x] F1 B2 × 5 idiomas — 2 unidades/5 lecciones + 2 listenings + reading + checkpoint por idioma
- [x] F2 C1 × 5 idiomas — unidad de 3 lecciones + 3 listenings + 3 readings + checkpoint
- [ ] F3 A1 ola 1 — hogar y barrio (2u × 5 idiomas + listenings)
- [ ] F4 A1 ola 2 — gente, rutinas, el tiempo (hasta ~30l)
- [ ] F5 A1 ola 3 — transporte, hobbies, salud, tiendas (A1=40l, 15 listenings)
- [ ] F6 A2 hasta 18 lecciones (+6 listenings, +3 readings)
- [ ] F7 B1 hasta 14 lecciones (+5 listenings, +4 readings)
- [ ] F8 track profesional por idioma (B1, 3 lecciones + Talk)
- [ ] F9 pronunciación F2-F5 + drills (pron 8/8)
- [ ] F10 cierre: en-c1 balance, pins iOS, PARITY_GAP=0, informe final

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
