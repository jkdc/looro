# Informe — Niveles por tema superado (análisis y opciones)

**Fecha:** 2026-09-28 · Petición: "poder elaborar un nivel por cada tema que se va superando, así el usuario se puede motivar". Análisis sin implementación.

## 1. Qué hay sobre lo que apoyarse (todo ya existe tras progress-exercises-listening)

| Pieza | Dónde | Utilidad para niveles |
|---|---|---|
| Ledger de completados `CompletionMark(kind, itemID, at, bestScore)` | Archive local + `user_progress` en la API (sync monótono) | **Base ideal**: el nivel puede ser una proyección pura del ledger, sin estado nuevo ni migraciones |
| `exercise_set` con `bestScore` (0-100, merge max) | marks por lección + servidor | Matería prima para dominio por lección (repetir para subir) |
| `LessonState.attempts` | Archive | "Superado" de la práctica oral |
| `listening` con `bestScore` | marks por texto | Medallas por texto ("Best 100%" ya se pinta) |
| `conversation_theme` | marks por tema Talk | Puede contribuir a la escalera si se quiere |
| `CourseUnit.level` (A1/A2) | datos del temario, **no renderizado por política** | No usar como nivel de usuario aún (claim discipline: revisión de hablante pendiente) |
| `learner.challenge` (0-5) | evaluación IA, contexto del tutor | Es interno del tutor; no es un nivel de producto. No duplicarlo ni mostrarlo |

## 2. Principios que debe cumplir (del repo)

1. **Derivado, no almacenado**: el nivel se proyecta del ledger (`LevelEngine` puro espejado, como `ProgressEngine`). Cero cambios de esquema, y como el ledger ya sincroniza, el nivel sincroniza gratis.
2. **Disciplina de claims**: es un nivel de *progreso en la app*, no una certificación de idioma. Nombres juguetones ("Nivel 4 · En marcha"), nunca "tu inglés es B1" ni CEFR visible hasta revisión de hablante competente.
3. **Determinista y offline**: sin IA en el grading (ya lo es), promoción explicable ("hiciste los ejercicios con 80% o más").
4. **Anti-farming suave**: repetir lo mismo el mismo día no debe subir de tier sin límite; la 3ª estrella exige evidencia espaciada (filosofía `LearningEngine`: barras por evidencia en días/contextos distintos).

## 3. Opciones

### Opción A — Escalera global simple ("un tema = un nivel")
El nivel global = nº de temas superados, con tramos de título (1-3 "Despegue", 4-9 "En marcha", 10-17 "Ruta", 18 "Foundations completa").
- **Pro**: trivial de entender y de implementar (ya está `lessonsDone`); motivación clara ("te faltan 2 para el siguiente tramo").
- **Contra**: plano — la lección 18 vale lo mismo que la 1; no premia repetir ni los ejercicios/listening; una sola práctica mala-superficial "superaría" el tema.

### Opción B — Dominio por lección (0-3 estrellas) + nivel global derivado ⭐ recomendada
Cada lección tiene un **dominio** calculado (no almacenado):
- ★1 "Superado": práctica oral completada (`attempts ≥ 1`).
- ★2 "Con oficio": además, set de ejercicios de esa lección con `bestScore ≥ 60`.
- ★3 "Dominado": además, `bestScore ≥ 90` **o** práctica repetida en un día distinto al primero (evidencia espaciada).

El **nivel global** sale de sumar dominios: `puntos = Σ dominios` (0-54) y una escalera de ~10 niveles con títulos (umbrales fijos: 1, 4, 8, 13, 19, 26, 34, 42, 48, 54). Extensible: cada texto de listening con `bestScore ≥ 80` suma ½ punto (o +1 con ≥100) y cada tema Talk practicado en 2 días distintos suma ½.
- **Pro**: profundidad real de motivación (re-jugar ejercicios para la 3ª estrella), aprovecha `bestScore` que ya sincroniza, progresión visible por fila (estrellas junto al ✓/HECHA), nivel en la cabecera de Learn con "te faltan X puntos para Nivel 5".
- **Contra**: más superficie de UI (estrellas por fila, anillo/cabecera de nivel) y decidir umbrales (fáciles de tunear; viven en una constante espejada con test).

### Opción C — Medalla por tema de conversación y por texto de listening
Extensión de B: los temas Talk y los textos de listening también tienen tier 0-3 (listening: % del mejor intento; tema Talk: nº de días distintos practicados). El nivel global suma todo.
- **Pro**: "cada tema que se supera" literal — también fuera del temario; motiva a quien usa Talk sin temario.
- **Contra**: los temas Talk no tienen "fin" claro (sesión de 30 segundos también marca hoy); hace falta un criterio mínimo de sesión para no regalar medallas.

### Opción D — Logros/badges (complemento, no sustituto)
Hitos puntuales: "7 días de racha", "primer listening", "10 ejercicios", "primera conversación por tema". Proyección del mismo ledger; muy barato.
- Se puede añadir como fase posterior sobre el mismo `LevelEngine`; no resolver la escalera principal.

## 4. Recomendación

**B con un trocito de C**: dominio 0-3 por lección (los temas del temario son el esqueleto del progreso) + nivel global derivado con títulos, y de regalo la medalla de listening (ya hay "Best %", falta el umbral de medalla). Los temas Talk quedan fuera del nivel en v1 (criterio de "superado" poco fiable hoy); se pueden incorporar cuando exista una noción de sesión mínima.

## 5. Cómo se implementaría (boceto de fases SDD)

- **F1 — `LevelEngine` core espejado**: `lessonMastery(lessonState, exerciseMarks) -> 0..3` y `levelStats(lessons, marks) -> (level, title, points, pointsToNext, perLessonMastery, nextGoal)`. Umbrales como constantes espejadas (check_cross_platform CONSTANTS). Tests Kotlin+Swift con fixtures.
- **F2 — UI Android**: estrellas (0-3) en cada fila de lección, cabecera de nivel en Learn (nivel + título + puntos para el siguiente), fila nueva en la hoja de progreso con "próximo hito". Strings EN/ES.
- **F3 — UI iOS** espejada + tests.
- **F4 — (opcional) medalla de listening** (umbral 80/100 sobre "Best %") y ajuste de umbrales con datos reales.

Sin migraciones, sin backend, sin sync nuevo: todo lo que el nivel lee ya viaja en el ledger. Esfuerzo estimado: notablemente menor que el SDD anterior (un motor puro + UI).

## 6. Riesgos / notas

- **Tunear umbrales**: con 18 lecciones, ★3 por `bestScore ≥ 90` es exigente pero alcanzable; revisable con telemetría (`exercise_completed` ya llega con `scoreBucket`).
- **No prometer idioma**: revisar copy con la disciplina de claims ("Dominado" se refiere a la lección, no a tu inglés).
- **Motivación vs castigo**: nunca bajar de estrellas una vez ganadas (el ledger es monótono por merge max — encaja).
- **Futuras cursos**: la escalera global debe reiniciarse o ramificarse por curso cuando haya un segundo temario (decidir en F1: nivel por curso vs global; recomendado: por curso ahora, global cuando exista el segundo).

---

## 7. Implementación (2026-09-28, misma sesión)

Ejecutado como SDD `openspec/changes/lesson-levels/` en 4 commits:

| Fase | Commit | Contenido |
|---|---|---|
| F1 | `83a4c80` | `LevelEngine` Kotlin+Swift (tiers 0-3, escalera de 10, medallas listening), 5 tests por plataforma, umbrales fijados en check_cross_platform (60/90/80 + escalera) |
| F2 | `78e657f` | Android: tarjeta de nivel por curso en Aprender, ★0-3 en cada fila de lección (nuevo AppSymbol.Star), filas Nivel y Medallas en "Tu progreso" |
| F3 | `a0b7650` | iOS espejado + concordancia singular/plural ("1 point to Level 2") en ambas plataformas |
| F4 | `2f47add` | Verificación y docs |

**Verificación:** Android 452/452; paridad (CONSTANTS nuevas incluidas), exportadores y scripts en verde; strings 343/343 + plurales. **Verificado en emulador** con el estado de las pruebas manuales: "Level 1 · First steps — 1 point to Level 2" con barra de progreso, y ★★★ en la lección dominada (práctica + ejercicios 100%) — capturas 22-23 en e2e-evidence/manual-2026-09-28/.

**Reglas finales de dominio:** ★1 práctica hecha · ★2 + ejercicios ≥60 · ★3 + ejercicios ≥90 o práctica en 2 días distintos con ≥60. Medalla listening: plata ≥80, oro =100 (no suma puntos de nivel en v1). Pendiente: gate macOS, pasada en Pixel, y retune de umbrales con telemetría real.
