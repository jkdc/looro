# Análisis de impacto: motor de conversación local vs OpenAI

> **Superseded 2026-09-17:** the on-device LLM was discarded by product
> decision (too slow for voice turns); this analysis is preserved for
> reference. See `docs/conversation-engines.md` ("Local engine (removed)").

> Alcance: qué cambia, qué se degrada, qué se pierde y qué riesgos aparecen en la
> app Android cuando `mural.conversationEngine != openai` (en particular `local`).
> Basado en el código de `feature/switchable-conversation-engine` (incluye fixes de
> las rondas de verificación). Fecha: 2026-09-17.

## 1. Resumen ejecutivo

Con el motor local, **todo el recorrido de aprendizaje (escrito y hablado)
funciona sin red y sin OpenAI**, pero con tres degradaciones de calidad
(modelo 3B cuantizado, STT base, sin búsqueda web), tres cambios de producto
(monetización bypassed, consentimiento con texto de OpenAI, estimaciones de
coste sin sentido) y un conjunto de riesgos técnicos acotados (RAM, térmica,
VAD). Nada de esto bloquea el experimento en Pixel; varios puntos son
decisiones de producto, no de código.

## 2. Conversación hablada (voz on-device)

| Aspecto | WebRTC/OpenAI | Local (Fase 6) | Impacto |
|---|---|---|---|
| Turn-taking | Detección de turno en servidor (Realtime) | VAD por energía en cliente (habla ≥0,3 s, silencio 1,2 s, tope 30 s) | **Medio**: en entornos ruidosos puede cortar frases o tardar en cerrar el turno; umbrales ajustables en `LocalVoiceTransport` |
| Transcripciones parciales | Streaming token a token | Solo enunciado completo (los fragments deduplican por id; sin `correctFragment` parcial) | **Bajo-Medio**: la UI no muestra "está escribiendo…"; aparece la frase entera |
| Barge-in (interrumpir al asistente) | Nativo en Realtime | Micrófono suprimido mientras el TTS habla; sin barge-in | **Medio**: para interrumpir hay que usar End/mute; mejora futura (VAD durante TTS con cancelación de eco) |
| Procesado de audio | AEC/NS/AGC del módulo WebRTC | `VOICE_RECOGNITION` sin procesado extra | **Medio en ruido**: micrófono desprotegido contra eco; en auriculares OK |
| Memoria de conversación | Historial gestionado en servidor | **Rolling context en cliente** (últimos 16 turnos + history inicial en el system prompt) — corregido en esta ronda (antes cada turno iba aislado) | Resuelto; limitar el tamaño del rolling evita prompts crecientes |
| Latencia por turno | ~1 s | STT (0,5–3 s) + prefill+generación 3B (5–60 s según dispositivo) | **Alto**: es el coste principal del modo local; medir en Pixel |
| Delegaciones (`session.delegation.created`) | El servidor puede delegar tareas al cliente (p. ej. temas desde la conversación) | Nunca se emiten | **Bajo**: no llegan sugerencias espontáneas de temas; el botón Meaning manual sigue funcionando |
| `voiceSeconds` / usage | Events del servidor | Emitidos por reloj local (cada ~15 s) | Paridad contable; ver §4 (estimación de coste) |
| Idiomas | Realtime multi-idioma fuerte | Whisper *base* multilingüe (hint de idioma) | **Medio**: noruego/bokmål funciona pero es el nivel "base"; probar `small` para calidad |

## 3. Ayudas de texto (`TeachingClient`: meanings, assessments, typed reply, topics, help)

- **Calidad del modelo**: GPT-5.6-luna → Qwen2.5-3B-Instruct Q4. La forma de las
  respuestas está garantizada por gramática (JSON schema → GBNF), pero el
  **contenido** (correcciones gramaticales, evaluaciones, explicaciones) es
  perceptiblemente más débil. Riesgo pedagógico: correcciones incorrectas
  presentadas con la misma confianza que las de OpenAI.
- **`search=true` se ignora**: los propósitos que usaban búsqueda web
  (LOOKUP/HELP/temas desde conversación) responden sin fuentes;
  `APIResult.sources` llega siempre vacío.
- **Latencia de typed reply**: en emulator medimos minutos; en Pixel esperamos
  5–20 s. Durante `working=true` la UI bloquea acciones simultáneas.
- **Coste por token**: `addUsage` sigue contando tokens (honesto), pero no hay
  coste económico asociado.

## 4. Monetización y negocio (el impacto más grande, y es de producto)

- **Hosted minutes queda bypassed de facto**: `needsMinuteAccess()`,
  `start()`, `teaching()` y `sendTyped()` omiten todas las puertas de hosted
  cuando engine ≠ openai. Un usuario en modo local **no consume minutos ni
  genera ingresos** por uso de IA.
- **Purchases/Stripe/Play**: sin cambios en código, pero pierden sentido en un
  build local puro (el valor que venden — minutos de IA — es infinito gratis).
- **Estimaciones de coste engañosas**: Ajustes muestra "Recorded voice time" y
  "Voice estimate: $X USD" calculado con la tarifa de OpenAI
  (`UsageSummary.VOICE_USD_PER_MINUTE`); con motor local esos dólares no
  corresponden a nada. Pendiente de producto: ocultar la sección o etiquetarla
  "estimación si usaras OpenAI".
- **Contenido guest/welcome**: `GuestMinuteController` sigue activo si
  `mural.apiOrigin` está configurado (su UI de estado puede mencionar minutos);
  no bloquea el flujo local, pero conviene silenciar sus avisos en local.

## 5. Consentimiento, privacidad y legal

- **Texto de consentimiento desalineado**: dice "Mural envía audio y texto
  seleccionados a OpenAI". Con motor local eso es falso (mejora de privacidad
  real: cero red), pero hoy el gate exige aceptar ESE texto igualmente.
  Pendiente de producto: variante de consentimiento por motor.
- **Mejora de privacidad**: el motor local no realiza ninguna conexión; con el
  permiso INTERNET presente (lo necesita el resto de la app), el flujo local
  es verificable con `adb shell cmd netpolicy` / capturas.
- Almacenamiento: sin cambios (transcripciones y sesiones siguen en el
  dispositivo; el audio nunca se guarda).

## 6. Recursos del dispositivo

- **RAM**: ~2,3 GB (LLM) + ~0,3 GB (Whisper) + base de la app → requisito
  práctico de ≥6 GB totales. En dispositivos de 4 GB el sistema matará el
  proceso. No hay gestión de `onTrimMemory`: los modelos cargados persisten
  hasta que el proceso muere.
- **Térmica/batería**: generación a 4 hilos satura los big cores; tras rondas
  seguidas el throttling baja los tok/s (visible en el log). Recomendado medir.
- **Almacenamiento**: 2,2 GB de modelos en external-files (empujados por adb;
  no hay descarga in-app todavía).
- **Arranque**: los modelos cargan perezosos en el primer uso (no al abrir la
  app); primera respuesta más lenta.

## 7. Fiabilidad y concurrencia

- El motor serializa generaciones (Mutex): un mensaje escrito mientras hay un
  turno de voz en curso espera a que termine (en Pixel es corto; en emulador
  eran minutos).
- `LocalEngineException` tiene mensajes accionables (modelo no encontrado con
  ruta, prompt excedido, fallo de generación); aparecen como error genérico en
  la UI — suficiente para el experimento.
- El VAD/STT no bloquea la UI; el bucle corre en `Dispatchers.IO`.

## 8. Qué NO cambia

Onboarding, temas, vocabulario, motor de aprendizaje, persistencia de sesiones,
traducciones de significado (ahora vía motor local), flujos de cuenta Google/Apple,
compras, y todo el backend `services/api` (hosted sigue OpenAI; su propia capa
multi-proveedor es un cambio futuro independiente).

## 9. Riesgos abiertos y siguiente pasos recomendados

| # | Riesgo/tarea | Tipo | Prioridad |
|---|---|---|---|
| 1 | Medición real en Pixel (latencia, tok/s, RAM, térmica, calidad STT voz humana) | Manual | **P0** — decide el fork |
| 2 | Consentimiento por variante de motor (texto no-OpenAI) | Producto | P1 |
| 3 | Ocultar/etiquetar estimaciones de coste en local | Producto | P1 |
| 4 | Silenciar UI de guest minutes en local | Producto | P2 |
| 5 | Inclusión condicional del módulo nativo antes de fusionar a main | Técnico | P1 (si se fusiona) |
| 6 | VAD mejorado (ej. WebRTC VAD o silero-onnx) + barge-in | Técnico | P2 |
| 7 | Prefill caching entre turnos (TTFT) | Técnico | P2 |
| 8 | A/B de modelos (Qwen3-4B-2507, whisper small) | Producto | P2 |
| 9 | Descarga de modelos in-app (hoy solo adb) | Técnico | P2 |
| 10 | `services/api` multi-proveedor (AI_PROVIDER) — solo si hosted también migrará | Técnico | P3 |

## 10. Fixeo realizado durante este análisis

- **[F-01] Contexto de conversación en voz local**: `LocalVoiceTransport`
  ignoraba el `history` recibido y cada turno iba aislado (el asistente no
  recordaba nada). Ahora mantiene un *rolling context* (últimos 16 turnos) más
  el historial inicial renderizado, con tests (`LocalVoiceTransportTest`).
- **[F-02] `sendTyped` bloqueado con HOSTED_MINUTES persistido**: en local no
  se puede iniciar conversación escrita nueva (`hosted_typed_start`); ahora el
  gate es `!localOnly`. También su `cloudReady()` ya no exige clave OpenAI en
  modo local.
