# Plan de pruebas manuales en el móvil — Pixel 9 · build Fase 1+2+3 + velocidad de voz

**Fecha:** 2026-09-23 · **Build:** `feature/rebranding-looro` HEAD (temario 18/18, SRS + libreta, backend billing fuera de este build, velocidad de voz configurable) · **Motor:** GLM (ZHIPUAI_API_KEY) · **Instalación:** fresca (`com.jkdc.palique.android` — los datos del antiguo `chat.mural`/`com.jkdc.looro` no se conservan).
**Qué solo se puede probar aquí** (el emulador no lo cubre): micrófono real → whisper STT, audio del TTS/lector, la diferencia audible de la velocidad de voz, rendimiento y batería.

**Antes de empezar:** activa el sonido del móvil; ten a mano la Wi-Fi (GLM consume red); el primer arranque descarga/inicializa el modelo whisper en local y puede tardar unos segundos más.

---

## A. Instalación y primer arranque (~5 min)

| # | Prueba | Esperado |
|---|---|---|
| A1 | Icono y nombre en el launcher | "Palique", icono propio (no arte upstream renombrado) |
| A2 | Primer arranque: splash y onboarding paso 1 | Saludo en el idioma elegido; al cambiar el idioma el saludo reacciona |
| A3 | Onboarding paso 2 (idioma de significados) | El subtítulo de ejemplo cambia al idioma elegido |
| A4 | Onboarding paso 3 (consentimiento IA) | Copy "Palique"; "Not now" permite entrar sin consentimiento (Words sigue funcionando) |
| A5 | Pantalla Talk inicial | Tema "A little everyday English", saludo + subtítulo, micrófono apagado |

## B. Voz real: STT, TTS y velocidad (núcleo de esta ronda, ~15 min)

| # | Prueba | Esperado |
|---|---|---|
| B1 | Iniciar conversación hablada y **hablar con voz natural** | Whisper transcribe tu turno; el texto de tu burbuja coincide razonablemente (ruido de fondo moderado) |
| B2 | Tutor responde **en voz alta** | Se oye el TTS local con la voz del idioma; no se solapa con tu turno |
| B3 | **Velocidad de voz**: Settings → "Speech rate" → "Very slow", nueva conversación | El tutor suena claramente más lento; repite con "Fast" (1.2×) y "Normal" |
| B4 | Con "Very slow" activa, comprobar el ritmo del **prompt** | El tutor usa frases más cortas/simples (instrucción de ritmo vía prompt) |
| B5 | **Lector de tarjetas**: Learn → lección → altavoz ON | Lee la tarjeta en voz alta **a la velocidad elegida**; el toggle OFF la silencia y permite leer en silencio |
| B6 | Turno largo y silencio | El cierre automático de turno respeta el slider "Silence before the coach replies" |
| B7 | Interrupción: hablar durante el turno del tutor | El VAD corta/gestiona sin colgar la sesión |
| B8 | "Beginner voice mode" (terminar turno con botón) | El turno solo se envía al pulsar el botón |
| B9 | "Listening practice" ON → nueva conversación | Historia sin subtítulos, texto en pantalla, lookup solo bajo demanda |

## C. Conversación libre y temas (~10 min)

| # | Prueba | Esperado |
|---|---|---|
| C1 | Talk libre 3–5 min (varios turnos hablados) | Sin cuelgues; subtítulos de significado en tu idioma; correcciones suaves |
| C2 | Themes → elegir un tema | La conversación arranca anclada al tema; si cambias de tema a mitad, re-ancla |
| C3 | Tocar una palabra de tu turno → lookup | Ficha de significado; **"Save for recall"** guarda en la libreta |
| C4 | "Type instead" | Respuesta escrita llega y el tutor continúa |
| C5 | "A little help" | Sugerencia útil sin romper el turno |
| C6 | End → biblioteca | La sesión aparece guardada con su título/tema |

## D. Lecciones y práctica (~10 min)

| # | Prueba | Esperado |
|---|---|---|
| D1 | Learn → curso Foundations | **6 unidades × 3 lecciones (18)** con estados |
| D2 | Abrir 2–3 tarjetas de estudio (p. ej. "Ordering at a café", "Your weekend") | Explicación, ejemplos, expresiones, situación, temas de unidad |
| D3 | "Practise speaking" → consentimiento → práctica | El tutor sigue el guion numerado de la lección; dice si aciertas o corrige |
| D4 | Terminar la práctica | La tarjeta pasa a "Practised 1×" |

## E. SRS y libreta (~10 min)

| # | Prueba | Esperado |
|---|---|---|
| E1 | Tras la primera práctica, Learn muestra **"Recall — N expressions ready"** (N=6 + guardadas) | La siembra es automática e idempotente |
| E2 | Repaso: responde bien a 2 y mal a 1 | "Correct!" promueve barra; fallo baja a bar 1 y reprograma |
| E3 | Words → "Saved expressions" | Aparecen las sembradas y las guardadas del lookup, con barras y Remove |
| E4 | Quitar una expresión y re-practicar la lección | No se duplica; la quitada NO vuelve (solo se siembra si no existe) |

## F. Persistencia y ajustes (~5 min)

| # | Prueba | Esperado |
|---|---|---|
| F1 | Forzar cierre y reabrir | Onboarding, progreso, expresiones y ajustes intactos (archive local) |
| F2 | Cambiar idioma de aprendizaje | Cada idioma conserva sus palabras/progreso propio |
| F3 | Rachas/estadísticas en Words tras 2+ sesiones | Datos coherentes con lo practicado |
| F4 | Informe IA al cerrar sesión (si aparece) | Se genera y guarda sin error |

## G. Riesgos conocidos a vigilar (no son fallos nuevos)

- **GLM free tier**: 429 intermitente ("service may be temporarily overloaded") — reintenta; si persiste, cambia motor en Settings (DeepSeek) si tienes clave.
- **Significados fallidos puntuales** ("We couldn't get the meaning") — degradación conocida cuando el helper falla; "Try meaning again".
- **Modelo whisper**: primera sesión puede tardar en arrancar mientras carga el modelo local.
- El backend/billing NO está desplegado: nada de compras ni voz hospedada en este build (funcionalidad local únicamente).
- Nota terminológica: "Speech rate" controla la **voz que te habla** (TTS); la transcripción (STT) no es configurable.

**Criterio de pase:** A/B/C sin crashes ni bloqueos; B3 perceptible de oído; E completo. Cualquier fallo: captura de pantalla + hora aproximada + qué tocaste justo antes.
