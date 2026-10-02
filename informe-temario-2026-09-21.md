# Informe de temario — aprender gramática y expresiones hablando

**Fecha:** 2026-09-21 · **Rama:** `feature/switchable-conversation-engine`
**Alcance:** features de aprendizaje "a la hora de hablar": temario/currículo, puntos de gramática y expresiones practicadas dentro de la conversación.
**Método:** exploración del árbol actual (TeachingPolicy, LearningEngine, modelos de sesión, motores de conversación, openspec) sobre el estado posterior a `cabling-remediation` (26/26), más `informe-features-2026-09-18.md` (su catálogo K/D/I/B/T y qué se ya se embarcó en `gentle-ai`). Esfuerzos con la misma escala: **S** ≤ 3 días · **M** 1–2 semanas · **L** > 2 semanas (por plataforma, paridad Kotlin/Swift incluida).

---

## 1. Resumen ejecutivo

Hoy Mural enseña **de oído, sin ruta**: el tutor ya "introduce 1–3 expresiones útiles y crea una razón natural para recuperarlas" (`TeachingPolicy.voice()`), corrige suave tras cada pasaje, y cada pasaje del usuario pasa por un bucle de evaluación con evidencia validada que ajusta `challenge`, `nextGoal` y barras de palabras. Lo que **no existe** es ningún objeto de aprendizaje estructurado: no hay lecciones, ni puntos de gramática, ni expresiones retenidas como entidades, ni progreso temático visible. El aprendizaje es emergente; el usuario no puede decir "hoy quiero practicar el pasado" y ver que lo practicó.

La buena noticia: **todos los ganchos para un temario hablado ya existen**. El prompt del tutor se reconstruye por turno en los tres caminos de voz; el assessment por pasaje ya devuelve JSON estricto validado; `session.mode` y `themeID` ya existen; las barras 0–3 con repaso espaciado de `WordState` son un patrón replicable a objetivos y expresiones.

**Propuesta:** tres enfoques de empaquetado (§5) y una recomendación híbrida: primero **B** (capturar y repasar expresiones/gramática que el tutor ya enseña: sin autoría de contenido, valor rápido), después **A** (temario embebido en la conversación con contenido curado, piloto en **un** idioma). **C** (curso completo por niveles) no como primer paso.

---

## 2. Estado actual: los ganchos que ya existen

| Gancho | Dónde | Qué permite |
|---|---|---|
| **Prompt del tutor por turno** | `apps/android/.../core/TeachingPolicy.kt` · `apps/ios/Core/TeachingPolicy.swift` (espejo manual) | Ya adapta a `challenge` (0–5), `nextGoal`, tema/situación, palabras vencidas, ritmo. Ya ordena enseñar 1–3 expresiones y provocar su recuperación. Un "objetivo de lección" es una línea más en este prompt. |
| **Inyección por turno en los 3 caminos de voz** | OpenAI realtime: `command("instructions", …)` (`MuralViewModel.kt:912`); path whisper: `session.instructions.append` (`LocalVoiceTransport.kt:487`, reconstruye el prompt base+contexto cada turno); hosted: mismos helpers vía backend | Diseño **agnóstico del motor**: si el objetivo de lección viaja por `TeachingPolicy` + `TeachingClient`, se comporta igual con openai, deepseek/glm y hospedado. |
| **Bucle de evaluación por pasaje** | `requestAssessment` (`MuralViewModel.kt:1022`) / `ConversationCoordinator.assess` (iOS); schema JSON estricto; validación dura en `LearningEngine.validate` (cita literal del pasaje, confianza ≥ 0.8, downgrade a `assisted`) | Ampliable: el assessment puede devolver también *qué objetivos de la lección activa produjo el usuario*, con cita. El progreso del temario quedaría anclado a evidencia, no a la impresión del tutor. |
| **Barras 0–3 + repaso espaciado** | `WordState` (`LearningEngine.kt:69–85`, escalera `[1, 1, 4, 14]` días) · drill de escritura `core/WordPractice.kt` / `WritingPracticeView.swift` | Patrón replicable: `ExpressionState` (expresiones multi-palabra) y `ObjectiveState` (objetivos de lección) con la misma mecánica y la misma UI de barras. |
| **Temas = situaciones** | `apps/ios/Core/Themes.swift` + espejo Kotlin (24 temas con overrides culturales) | Una lección puede fijar la situación que **obliga** a usar la forma objetivo (pedagogía de tarea: para pedir en pasado, te pongo a contar qué pasó). |
| **`session.mode` y `themeID`** | `SessionRecord` (`core/Models.kt:81–99` / `Core/Models.swift`); hoy `mode = "listening"` | Un `mode = "lesson"` con `lessonID` es un cambio de modelo pequeño y natural. |
| **Archive versionado** | `ArchiveCodec` (`Models.kt:118–203`, schemaVersion 2, migración v1→v2 ya hecha) | Progreso de lecciones y expresiones entran como **v3** (con migración y caps), o sidecar si hay prisa. |
| **Paridad Core con fixtures** | `shared/fixtures/cross-platform` + `CrossPlatformFixtureTest(s)` + `scripts/check_cross_platform.py` | El currículo como **datos** (JSON espejado) y la proyección en `LearningEngine` con fixtures = mismo comportamiento en ambas plataformas por construcción. |

Ideas relacionadas ya catalogadas en `informe-features-2026-09-18.md` (no duplicadas aquí, sinergia directa): **K1** repaso activo SRS, **K2** recap post-sesión, **K7** escenarios propios, **K8** test de nivel inicial. K3, K9, K10, K12 ya se embarcaron en `gentle-ai`.

---

## 3. Principios que condicionan el diseño

1. **La evidencia es sagrada** (posición del producto, CONTRIBUTING): el progreso del temario solo avanza con citas validadas del habla del usuario, nunca con "el tutor cree que lo hizo bien".
2. **Paridad de Core obligatoria**: cada feature toca Kotlin y Swift, y pasa fixtures + `check_cross_platform`. Planificar por fases completas, no a medias.
3. **Agnóstico del motor**: todo debe funcionar igual en openai realtime, en deepseek/glm con whisper+TTS y en hospedado. Regla práctica: nada nuevo fuera de `TeachingPolicy` / `TeachingClient` / `LearningEngine`.
4. **Sin nube**: contenido incluido en la app (datos espejados como `Languages`/`Themes`); sin endpoint nuevo (la voz hospedada ni siquiera está activa comercialmente).
5. **Filosofía gentle**: el modo lección **guía** la conversación, no la convierte en un examen. Corrección suave y micro-explicación a demanda; la conversación libre sigue exactamente igual que hoy.
6. **Disciplina de claims**: no anunciar cobertura CEFR ni eficacia docente sin revisión de hablante competente (issues #10–#13 siguen abiertos).

---

## 4. Catálogo de ideas

### 4.1 Núcleo del temario

**T1 · Lección con objetivo (unidad mínima del temario)**
Una lección = punto de gramática (p. ej. "pasado: narrar qué hiciste ayer") + 5–10 expresiones/frases objetivo + situación sugerida (reusa `Themes`) + criterio de dominio. Picker de lecciones análogo al de temas. Al iniciar, `mode="lesson"` + `lessonID`; el prompt del tutor recibe: "objetivo de HOY: X; provoca situaciones que obliguen a usarlo; recuérdalo si se diluye" (viaja por el mismo canal que `nextGoal` hoy). *Esfuerzo: S–M. Riesgo: bajo. Enganche: `TeachingPolicy.voice()`, `SessionRecord`, picker en `ui/` y `LibraryViews`.*

**T2 · Detección de objetivo en el assessment (progreso con evidencia)**
Extender el schema del assessment por pasaje con un bloque opcional `lesson`: para cada objetivo activo, `produced: bool` + cita literal + calidad (0–3). La validación dura de `LearningEngine.validate` se aplica igual (cita canónica, confianza). Proyecta a `ObjectiveState` (barras 0–3, misma escalera de repaso). Lección "dominada" cuando sus objetivos llegan a 3 con evidencia no asistida. *Esfuerzo: M. Riesgo: moderado (el proveedor puede ignorar campos opcionales; el schema estricto de openai ya resuelve, deepseek/glm ya usan schema-embebido). Enganche: `assessmentSchema`, `LearningEngine.validate`, fixtures.*

**T3 · Expresiones como entidades de primera clase**
Hoy el tutor enseña expresiones y "crea razón para recuperarlas", pero la app no las retiene: al cerrar la sesión, desaparecen. Nuevo evento estructurado (mismo patrón que assessments): cuando el tutor introduce una expresión, emite `{expression, meaning, example, contexto}` → se valida (la expresión debe aparecer literal en el turno del tutor) y crea/actualiza `ExpressionState` con barras y `dueAt`. Las vencidas se inyectan en "palabras a retomar" del prompt y entran al drill (T6). *Esfuerzo: M. Riesgo: bajo. Enganche: `TeachingClient` (nuevo `HelperPurpose` o campo del reply), `LearningEngine`, Archive v3.*

**T4 · Micro-explicación de gramática a demanda**
Botón "¿por qué?" junto a una corrección (o pregunta hablada "¿por qué es así?") → nuevo helper `grammar()` (hermano de `meaning`/`lookup`): explicación breve de la regla + 2 ejemplos contrastivos, en una tarjeta que reutiliza la superficie de `meaningVisible`. Optional: la tarjeta ofrece "practícalo ahora" y el tutor sesga las siguientes respuestas hacia esa forma. *Esfuerzo: S–M. Riesgo: bajo. Enganche: `HelperPurpose`, `APIClient`/`HostedAPIClient` helpers, tarjeta de significado en ambas UIs.*

### 4.2 Refuerzo y ruta

**T5 · Recap post-sesión enfocado a aprendizaje**
Al cerrar sesión (normal o lección): objetivos practicados con evidencia, expresiones nuevas capturadas (T3), correcciones recurrentes, "próximo paso" (`nextGoal`). Pantalla de cierre hoy inexistente (solo "volverá al saludo en 15 s"). Es el **K2** del informe anterior, visto desde el temario. *Esfuerzo: M. Riesgo: bajo (no meter ruido en la UI de conversación, como ya decide el código). Enganche: `endReason`, `Archive`, `LibraryScreens`/`LibraryViews`.*

**T6 · Práctica de recuperación hablada de expresiones**
Variante hablada del drill de escritura K12: "¿cómo le dirías al camarero que te duele la cabeza?" → el usuario responde **hablando** → evaluación con el mismo pipeline de evidencia → barra sube. Cierra el círculo expresión→enseñanza→recuperación→repaso. *Esfuerzo: M. Riesgo: moderado (STT puede penalizar; aceptar también teclado). Enganche: `WordPractice.kt` / `WritingPracticeView.swift`, `LearningEngine`.*

**T7 · Ruta del temario y progreso**
Lista de unidades ordenadas (p. ej. presente → pasado → futuro → condicional → subjuntivo/estilo) con prerrequisitos **suaves** (recomendado, no bloqueante) y pantalla "Mi temario" con barras por unidad. Natural una vez T1–T2 existen. *Esfuerzo: S (sobre T1–T2). Enganche: datos del temario, `LearningProgressView`.*

**T8 · Colocación inicial del temario**
Conversación diagnóstica de 3–5 min que fija `challenge` inicial y **propone** por dónde empezar el temario. Es el K8 del informe anterior con salida hacia la ruta (T7). *Esfuerzo: M. Riesgo: necesita validación con hablantes.*

**T9 · Lecciones AI-generadas a demanda**
"Quiero sobrevivir en un restaurante de Lima" → el motor genera una lección con el mismo esquema de datos de T1 (objetivo + expresiones + situación), se guarda como lección propia (caps del Archive). Sin coste de autoría; calidad depende del motor; no determinista (probar en deepseek/glm también). **Híbrido posterior**, no base. *Esfuerzo: M. Riesgo: moderado (validación de import, claims).*

---

## 5. Enfoques de empaquetado

**A · Temario embebido en la conversación** (T1 + T2 + T4 + T5; luego T3/T6/T7)
Contenido curado embebido en la app (JSON espejado, como `Languages`/`Themes`), piloto de 6–10 lecciones en **un** idioma.
✔ Determinista, testeable con fixtures, offline, igual en los 3 motores, respeta la evidencia. ✘ Coste de autoría pedagógica × idioma; requiere decidir el idioma piloto.

**B · Bottom-up de expresiones y gramática** (T3 + T4 + T5; sin temario)
El tutor sigue enseñando como hoy; la app retiene expresiones y micro-explica gramática a demanda, repasa con espaciado y resume al cerrar.
✔ Sin autoría de contenido, valor rápido, mejora lo que ya ocurre en cada sesión. ✘ No da "ruta" ni sensación de curso; depende de que el tutor introduzca bien las expresiones.

**C · Curso completo por niveles** (T7 + T8 + esqueleto CEFR con desbloqueos y quiz)
✔ Producto "curso" completo. ✘ Autoría grande ×8 idiomas, riesgo de claims, rigidez pedagógica; no recomendado como primer paso.

---

## 6. Recomendación

**Híbrido B → A**, en fases openspec separadas (formato SDD del repo):

- **Fase 1 (B):** T3 (expresiones como entidades) + T4 (micro-explicación) + T5 (recap). Construye las entidades (`ExpressionState`, recap) que A reutiliza, y ya responde a "quiero aprender expresiones hablando".
- **Fase 2 (A piloto):** T1 + T2 + T4 con contenido piloto de 6–10 lecciones **en un idioma** (el que el usuario practique de verdad, para poder validar con uso propio).
- **Fase 3:** T6 + T7 (recuperación hablada + ruta del temario). T8/T9 opcionales después.

Justificación: B es barato y hace el trabajo de datos que A necesita igualmente; A es lo que da la sensación de "temario" que se pide; C es una decisión de producto mayor que solo tiene sentido si A demuestra que la gente sigue lecciones.

---

## 7. Riesgos y consideraciones

- **Autoría pedagógica**: el contenido de lecciones (qué gramática, qué expresiones, qué situaciones) necesita criterio docente y de hablante. Empezar pequeño (6–10 lecciones) y validarlas usándolas, no escalar antes de tiempo.
- **Doble implementación Kotlin/Swift**: cada fase toca Core dos veces + fixtures. Las estimaciones iOS asumen el gate de macOS pendiente (igual que las features previas).
- **Schema del assessment**: los campos nuevos deben ser opcionales y compatibles con `LearningEngine.validate`; no relajar las reglas de evidencia existentes (cita literal, confianza ≥ 0.8, downgrade `assisted`).
- **Archive v3**: migración + caps de tamaño (sobre todo si llegan lecciones AI-generadas de T9). Validación estricta en import, como hoy.
- **No romper gentle**: el modo lección es opt-in; la conversación libre no cambia. El "strict listening" sigue parked (decisión abierta en `cabling-remediation/design.md`) — no mezclar.
- **Paridad de strings**: las UIs nuevas añaden claves a ambos catálogos (Android 252 hoy; iOS sigue en inglés — decidir si la fase 1 localiza o no).

---

## 8. Próximos pasos

1. Elegir enfoque (§5) e idioma piloto → 2. propuesta openspec (`proposal.md` + `design.md` + `tasks.md` + deltas de specs) → 3. implementación por fases con tests por fase y veredictos honestos (flujo habitual del repo).

**Preguntas abiertas para decidir antes del openspec:** ¿enfoque B→A híbrido u otra combinación? ¿idioma piloto (los 8 soportados: noruego —por defecto—, español, inglés, francés, alemán, italiano, portugués, mandarín)? ¿las lecciones se navegan libres (recomendado) o con desbloqueo?
