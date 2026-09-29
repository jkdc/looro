# Informe: listenings por temas y niveles (2026-09-29)

SDD: `openspec/changes/listening-tema-levels/` · Branch
`feature/rebranding-looro` · Commits `31000c2` (SDD), `a001ff0` (contenido),
`3d4ab3d` (nivel), `3e175e5` (UI), este docs.

## Qué se pidió

Ordenar los listenings por niveles con dificultad creciente, añadir topics
por nivel ligados al temario ("listening del tema 1, tema 2, …"), más
preguntas por listening, y que el listening cuente para subir el nivel de
usuario.

## Qué se hizo

### Contenido (45 textos, antes 25)

- **Inglés: 15 textos.** Doce vinculados a las seis unidades del temario,
  dos por tema: u1 "First sentences" (nuevos *The language café*,
  *The new neighbour*), u2 "People & things" (nuevo *The blue umbrella*,
  *A new flatmate*), u3 "Everyday actions" (*A coffee before work*, nuevo
  *The night shift*), u4 "Getting around" (nuevo *A walk in the new city*,
  *The Sunday market*), u5 "Food & likes" A2 (*The language-exchange
  evening*, *The rainy picnic*), u6 "The past begins" A2 (*Sofia's first
  day*, *A small accident*). Los tres A2 que son relatos en pasado
  (*lost-jacket*, *night-train*, *grandpas-garden*) quedan como extras
  solo-topic (viaje, ropa, familia) — re-nivelarlos a A1 sería falsearlos.
- **es/fr/it/de/pt: 6 textos cada uno.** Los tres existentes (etiquetados a
  su unidad A1) más tres nuevos alineados con las lecciones del tema 1
  (verbo central, preguntas y negaciones, presentarse): *la clase de
  idiomas*, *la vecina nueva*, *mi hermana pequeña* — mismas historias
  traducidas en los cinco idiomas, como los tres originales.
- **6 preguntas por texto** (antes 3-4): los existentes conservan sus
  preguntas y ganan dos. El mínimo validado sube de 3 a 5.
- Los **ids no cambian**, así que el histórico de mejores puntuaciones y el
  ledger sincronizado con el servidor siguen valiendo.

### Esquema y ordenación

- `shared/listening/*.json`: `topic` obligatorio y `unitID` opcional;
  `export_listening.py` valida que la unidad exista en el curso del idioma y
  que su nivel coincida con el del texto, y emite ambos campos a Kotlin y
  Swift.
- `ListeningRegistry.forLanguage` ahora ordena: nivel (A1<A2<…), orden de la
  unidad en el curso, id. Sin bloqueos: los niveles superiores se pueden
  escuchar desde el principio.

### El listening sube el nivel

- La nota de medalla se convierte en puntos de nivel: plata (mejor ≥80) = 1
  punto, oro (100) = 2, por texto. `LevelEngine.stats` suma esos puntos a los
  tiers de lecciones y `LevelStats` expone el desglose
  (`lessonPoints`/`listeningPoints`), visible en la tarjeta de nivel de
  Aprender ("X lesson points · Y listening points").
- Escalera recalibrada `1, 6, 12, 19, 27, 37, 49, 61, 69, 78`: el 78 es el
  dominio completo de Foundations en inglés (54 de lecciones + 24 de
  listening). En la práctica el listening pesa ~31% en inglés (24/78) y el
  nivel 10 exige también las medallas de listening. Solo con lecciones se
  llega ahora a nivel 7.
- Sin cambios de servidor: el kind `listening` ya sincroniza `bestScore`.

### UI

- El tab Listen agrupa por nivel con cabeceras de sección; cada tarjeta
  muestra "Tema 3 · Everyday actions · 6 preguntas" si pertenece a una
  unidad, o "A2 · travel · 6 preguntas" si es extra solo-topic. Igual en el
  detalle. Espejo iOS con inglés inline.
- Strings EN/ES nuevas (`listening_theme`, `listening_topic`,
  `learn_level_breakdown`); `listening_level` retirada.

## Verificación

- Gradle Android: **469/469** (contenido, niveles, UI). Un fallo puntual y
  preexistente de `LocalVoiceTransportTest` (flaky de timing, verde en
  aislado y en la re-ejecución completa).
- Python de scripts: **76/76**; `export_listening.py --check` y
  `check_cross_platform.py` en verde; strings EN/ES 354/354.
- Tests de contenido nuevos: conteos por idioma, ≥5 preguntas, orden
  nivel→unidad→id, unidad del mismo nivel, cada pregunta corrige sus
  respuestas; tests de nivel nuevos: puntos de listening desde la mejor
  nota, 54 → nivel 7, 78 → nivel 10 con desglose.

## Pendientes

- Gate macOS (`swift test`) para el núcleo espejado, como en cambios
  anteriores.
- Pasada manual en emulador/dispositivo de la lista agrupada y del desglose
  del nivel con historial real.
- Ajuste fino de umbrales con telemetría real cuando acumule
  `listening_completed`.
