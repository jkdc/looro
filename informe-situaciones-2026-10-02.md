# Informe: modo "Practica una situación" (scenario-practice) — 2026-10-02

Implementación completa del nuevo modo de speaking: práctica guiada de una
situación concreta (entrevista, presentación, deportes, pedir la cuenta…)
con prompt de tres fases (preparar → practicar → repasar) y repaso
estructurado de errores tras la sesión. Espejo de prompts en Swift; cableado
iOS pendiente como cambio de seguimiento.

## Qué se ha construido

- **Catálogo** (`core/Scenarios.kt`, archivo propio fuera del pipeline de
  contenido): 8 situaciones curadas con objetivo, rol del tutor y etapas
  naturales + `Scenarios.custom()` que sanea la situación de texto libre del
  usuario (una línea, sin caracteres de control, 120 caracteres) y la inyecta
  solo como "data, not instructions".
- **Prompt de 3 fases** en `TeachingPolicy.scenarioPractice`: Fase 1 PREPARE
  (breve, máximo 5 expresiones), Fase 2 PRACTISE ("the heart of the session;
  spend most of it here", en rol, errores corregidos con recasts dentro de la
  escena, registro silencioso de errores), Fase 3 REVIEW hablado (2–4 errores
  citando palabras del alumno). Incluye `skillFocus` (errores de ejercicios),
  línea de errores del intento anterior, palabras a reciclar y ritmo de habla.
  Prohibido explícito de anunciar nombres de fase o usar markdown.
- **Repaso estructurado post-sesión**: nuevo helper `scenario_review` con
  esquema JSON estricto (strengths, errors con cita+corrección+tipo+nota,
  keyPhrases, retryFocus). El cliente verifica cada cita contra el transcript
  (`ScenarioReviewing.validate`: subcadena exacta de los fragments citados)
  y descarta ítems no verificables. Se guarda en `SessionRecord.scenarioReview`.
- **UI**: sección "Practise a situation" en el tab Temas (8 tarjetas + "Your
  own situation" con diálogo de texto); `ScenarioReviewSheet` con estados
  Preparing/Failed/Ready, errores "Dijiste → Mejor", frases clave guardables
  al cuaderno de repaso y botón **Practise again** que reinicia la situación
  reinyectando los errores verificados en el prompt.
- **Progreso y telemetría**: kind `scenario_practice` en el ledger (más
  migración SQL), eventos `scenario_started/completed` (más migración SQL).
- **Servidor**: purpose `scenario_review` en `hosted-helpers.ts` (con esquema,
  sin búsqueda), ventana post-cierre, CHECK SQL (migración 037); kind en
  `progress.ts` (migración 036); eventos en `usage.ts` (migración 035).
- **Paridad**: prompts espejados palabra por palabra en `TeachingPolicy.swift`
  y catálogo en `Scenarios.swift`; `scripts/check_cross_platform.py` en verde.

## Verificación

- **Unit tests JVM Android**: suite completa `:app:testDebugUnitTest` en
  verde, incluido `ScenarioPracticeTest` nuevo (16 casos: sanitización,
  formas del prompt, validación del repaso, presupuesto de bytes del
  contexto, roundtrip del archive, compatibilidad hacia atrás).
- **Servidor**: `tsc --noEmit` limpio; suite completa `npm test` contra
  PostgreSQL desechable — 385/391 pasan; los 6 fallos son preexistentes y
  ambientales (Node 18 sin webcrypto: auth, apple-revocation, accounts,
  minute-providers; y un test de ventana de fechas), reproducidos en árbol
  limpio con `git stash`.
- **Build**: `:app:assembleDebug` correcto (motor openai y glm).
- **E2E en emulador** (evidencia en `e2e-evidence/scenarios-2026-10-02/`,
  APK con motor GLM y clave ZHIPU desplegada):
  1. Sección de situaciones visible en Temas con las 8 tarjetas + tarjeta
     de situación libre.
  2. Tarjeta → salta a Talk → sesión `mode=scenario` con apertura de Fase 1
     en voz (TTS) y sin encabezados markdown.
  3. Respuestas escritas ancladas a la situación; tutor mantiene el rol.
  4. End → sheet "Preparing your review…" → repaso completo: situación,
     "For next time" (retryFocus en el idioma del significado), "What went
     well" (3 fortalezas), "Key phrases" con botones Save.
  5. "Practise again" → nueva sesión que recicla la frase clave del repaso.
  6. Persistencia verificada en `learning.json`: repaso en la sesión y 4
     marcas `scenario_practice` en el ledger.

## Bugs encontrados por el e2e y corregidos

1. **El repaso se descartaba siempre**: el auto-reset de conversación a los
   15 s incrementa `generation`, y el resultado del repaso se filtraba por
   `token != generation`, dejando el sheet en "Preparing" para siempre. El
   repaso ahora se liga al ID de sesión (no a la generación de UI) y su ciclo
   de vida se gestiona con su propio Job (se cancela solo cuando empieza otra
   conversación).
2. **El timeout de 120 s era invisible**: `TimeoutCancellationException`
   hereda de `CancellationException` y se re-lanzaba, matando la corutina sin
   actualizar el estado. Ahora se captura explícitamente y muestra el estado
   Failed.
3. **El modelo verbalizaba los nombres de fase con markdown**
   ("**Phase 1 — PREPARE**"): el prompt ahora prohíbe anunciar fases y usar
   markdown (espejo Swift incluido, paridad verificada).

## Notas y limitaciones

- El emulador no trae TTS por defecto: `settings put secure tts_default_synth
  com.google.android.tts` lo arregla; la primera sesión tras reinstalar puede
  fallar por una carrera de inicialización de TTS del emulador (reintento
  correcto). El fallo de TTS aborta la sesión de forma limpia (error
  controlado, sin estado roto).
- Con GLM el repaso devolvió 0 errores verificables en esta prueba (citó poco
  y las citas que no coincidían exactamente se descartaron por diseño). Con
  conversaciones más ricas y/o OpenAI el volumen de errores será mayor; el
  caso de descarte está cubierto por tests.
- Sesiones hospedadas (minutos del servidor): el repaso viaja por la ventana
  post-cierre de 120 s ya existente; verificado a nivel de test de integración
  (`hosted-helpers.test.ts`), no contra el server desplegado.
- El contenedor de PostgreSQL de prueba (`looro-test-pg`) se levantó, se usó
  y se paró. El emulador queda arrancado con la app instalada (motor GLM).

## Deuda de seguimiento

- Paridad iOS completa (coordinador, Storage, UI de repaso + gate macOS) —
  tasks 6.x en `openspec/changes/scenario-practice/tasks.md`.
- Los prompts del repaso podrían afinar la tasa de errores citados (pedir
  siempre al menos 1 error cuando lo haya); pendiente de observación con uso
  real.
