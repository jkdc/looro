# Informe — Registro de progreso, ejercicios de temario y Listening

**Fecha:** 2026-09-28 · **Rama:** `feature/rebranding-looro` · **SDD:** `openspec/changes/progress-exercises-listening/`

## Qué se pidió

1. Registrar todo lo que el usuario completa (temas y actividades) para que vea su avance y reciba métricas.
2. Poder añadir ejercicios a los distintos temarios.
3. Un nuevo apartado de **Listening**: escuchar un texto y responder preguntas por escrito.

## Qué se entregó (4 commits)

| Fase | Commit | Contenido |
|---|---|---|
| F1 backend | `752afe1` | Ledger `user_progress` (030) con merge monótono, rutas `POST/GET /v1/progress` (invitados incluidos), catálogo de uso ampliado con `exercise_completed`/`listening_completed` (031), tabla navegable en el panel admin |
| F2 progreso | `d263f94` | `CompletionMark` en el archivo local (espejado Kotlin/Swift), `ProgressEngine` (%, rachas, gráfico 30 días), `ProgressClient` con push/pull, badges HECHA y % por curso/unidad en Aprender, hoja "Tu progreso" ampliada, hooks de lección/tema/recall/palabras/assessment |
| F3 ejercicios | `2c12bae` | 72 ejercicios autorados (4×18 lecciones) en `en-foundations.json`, 4 tipos (opción múltiple, hueco, ordenar, respuesta libre con patrones `<name>`), `ExerciseEngine` determinista sin IA, player en lección en ambas plataformas, telemetría |
| F4 listening | `c39bd74` | Quinto tab "Listen/Escuchar": 10 textos A1–A2 con 3–4 preguntas escritas cada uno, audio por TTS local con la velocidad elegida, transcripción oculta hasta el final, mejor puntuación en la lista, CI comprueba ambos exportadores de contenido |

Además: `33d8f62` — fix previo pendiente de esta rama (asessments sin curso se descartaban en el catálogo).

## Diseño en una línea

El registro es **local-first** (marcas `CompletionMark(kind, itemID, at, bestScore)` en el archivo del dispositivo, tope 2.000, merge por tupla exacta) y **se sincroniza con la API** con acumulativos por (tipo, item) que la BD fusiona de forma monótona (`GREATEST/LEAST`), así los reintentos y varios dispositivos convergen. "Lección HECHA" = `attempts ≥ 1` (no hizo falta cambiar `LessonState`; la nota en Models.kt ya reservaba esta evolución). Ejercicios y listening son **datos** (`shared/courses`, `shared/listening`) exportados a Kotlin/Swift nativos con `--check` en CI; las preguntas reutilizan la forma `Exercise` y el mismo gradador. Los `itemID` de palabras se hashean (SHA-256/16 hex) para respetar el formato slug.

**Cambio de posición de producto:** el repo declaraba "learning records local-only (no cloud sync planned)". Este SDD lo revisa explícitamente: el ledger porta slugs de contenido (nunca texto del usuario) y vive en la cuenta del usuario, invitados incluidos. Queda documentado en proposal y verify-report.

## Verificación (números reales de esta sesión)

- **Backend:** `npm run check` limpio + `npm test` **416/416** (node:22 + postgres:17 efímero, receta del repo), incl. 8 tests nuevos (merge monótono, idempotencia, invitados, cascade, pin CHECK↔catálogo).
- **Android:** `testDebugUnitTest` **447/447** (0 fallos) + `lintDebug` + `assembleDebug` verdes.
- **Scripts/CI:** `unittest discover` 66 OK; `export_courses --check`, `export_listening --check` y `check_cross_platform.py` en verde; `contracts.yml` ampliado con ambos checks.
- **Cadenas:** 328/328 EN/ES en lockstep.

## Pruebas manuales en emulador (2026-09-28, Medium_Phone_API_36, `make deploy ENGINE=glm`)

Recorrido completo de las tres features con capturas en `e2e-evidence/manual-2026-09-28/`:

1. **Launch + shell**: app arranca; quinto tab **Listen** visible con icono auriculares (01).
2. **Learn**: Foundations "Completed: 5%", unidad "First sentences" "Completed: 33%", lección hecha con ✓ + "Practised 1×" (datos reales del archivo previo), resto "New" (02).
3. **Player de ejercicios** (03-09): los 4 tipos se completaron end-to-end — opción múltiple (veredicto "Correct!" + explicación), hueco escrito con teclado, ordenar fichas barajadas tocándolas en orden, respuesta escrita libre ("I am Pedro" aceptada por la variante "I am `<name>`") → resumen **"You answered 4 of 4 correctly."** → Finish.
4. **Hoja de progreso** (10-12): "Lessons completed 1/18", **"Exercises completed 1"**, **"Activity streak 1"**; gráficos Last 14 days (voz previa) y **Last 30 days con la marca de hoy** conviven.
5. **Listening** (13-19): detalle con "A1 · 4 questions"; **"Playing…"** activo con Stop habilitado (TTS del emulador sintetizó el texto); 4 preguntas respondidas (2 choice + 1 escrita "half past eight" + 1 choice) → **"You answered 4 of 4 correctly."**; toggle "Show the transcript" revela el texto; Finish → la lista muestra **"Best 100%"** y el resto "New".
6. **Persistencia** (20-21): `force-stop` + relanzamiento → "Exercises completed 1", **"Listening texts 1"** y racha intactos.
7. **logcat**: tras el fix, cero FATAL/ANR (el crash buffer solo contiene el crash previo al arreglo).

### Bug encontrado y arreglado por las pruebas manuales

Al avanzar del último ejercicio al resumen, la app **crasheaba** (`ArrayIndexOutOfBoundsException` en `ExerciseScreen.kt:93`, commit previo): el proveedor de items de `LazyColumn` se re-evalúa de forma diferida y podía leer el `index` ya incrementado con el `finished` viejo, haciendo `exercises[4]` sobre 4 elementos. Mismo patrón latente en `ListeningScreen`. Fix `6266e18`: resolución del ejercicio dentro del item con `getOrNull` acotado (el espejo iOS ya era seguro por diseño). Suite re-verificada en verde (447/447) tras el fix.

### No cubierto en manual (y por qué)

- **Práctica de escritura de Words y rondas de Recall en UI**: este archivo del emulador no tiene palabras de lector (requieren una conversación real con IA que genere evidencia); el hook de registro es la misma llamada `recordCompletion` verificada en ejercicios/listening, y la ronda Recall exige 7 respuestas escritas seguidas — se dejan para la pasada en el Pixel con historial real.
- **Sync de progreso contra API en el emulador**: el origen gestionado apunta fuera; forzarlo ensuciaría un entorno real. La capa HTTP+BD está verificada por la suite de integración (arranca el Fastify real contra Postgres real), que es equivalente a un smoke curl.
- **Locale español en dispositivo**: cadenas ES verificadas por lockstep 328/328 + tests; el emulador está en EN.

## Puertas pendientes (no ejecutadas aquí, por entorno)

- **Gate macOS**: `swift test` para el mirrored core (ProgressEngine, ExerciseEngine, ListeningData, Archive changes) + build iOS con xcodebuild — requerido antes de release, igual que en todos los cambios previos.
- **Gate dispositivo**: pasada final en el Pixel 9 (micrófono real, voz, historial del usuario). La app no se ha instalado en hardware físico en esta sesión; el emulador queda encendido con el estado de pruebas.

## Ideas aparcadas (no solicidadas, anotadas)

- Pausa/seek del TTS (los motores actuales solo soportan speak/stop).
- Pestaña de métricas con los nuevos eventos en el panel admin (aparecen en "top features" automáticamente).
- Edición de contenido del temario/listening desde el panel admin.
