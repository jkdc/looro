# Design: voice-turn-settings-themes

## 1. Configurable silence window

`VoiceManualTurnStore` (ya existe, SharedPreferences `voice_prefs`) pasa a
guardar también `silence_end_ms` (Long, default 1 200). API ampliada:
`getSilenceEndMs()/setSilenceEndMs(ms)` con validación 600..3 000.

`LocalVoiceTransport` recibe `@Volatile var silenceEndMs: Long = 1_200` (lo lee
el bucle en modo automático: `trailingSilenceMs >= silenceEndMs`). El
ViewModel lo sincroniza en `start()` y en el setter.

UI: fila nueva en Settings ("JUST YOUR PACE", visible siempre; nota "applies
to on-device voice"): Slider 0,6–3,0 s paso 0,1 s, valor mostrado en
segundos. No pasa por `updatePreferences` (no toca el archive cross-platform).

## 2. Apertura temática de la sesión de voz local

`LocalVoiceTransport` añade:

- `var openOnStart: Boolean = true` — si false, no genera apertura.
- En `connect()`, tras emitir `session.started`: si `openOnStart`, lanza la
  apertura como un turno con `user = "(Begin the conversation as instructed.)"`.
  El contexto ya incluye la instrucción de saludo (`session.instructions.append`
  enviada por el ViewModel al procesar `session.started`, en el mismo hilo y de
  forma síncrona antes de que `connect` retorne).
- `speakAndEmit(reply, startMs)` — refactor compartido por turnos y apertura:
  emite `session.output_transcript.delta` y habla por TTS con micrófono
  suprimido.

El ViewModel pasa `openOnStart = true` siempre en voz no-OpenAI; el greeting
instruction ("Say 'Hei' in Norwegian and ask one short natural question")
produce una apertura temática en el idioma de aprendizaje.

Ajuste de log: `open turn done in Xms` para diagnóstico.

## 3. Settings UI

`SettingsScreen` (grupo "JUST YOUR PACE", tras el idioma):

```
Silence before reply (on-device voice)      [ 1,2 s ]
[========|——————————————]  0,6 … 3,0 s
```

Slider M3: `value = silenceEndMs`, `valueRange = 600f..3000f`, `steps = 23`.
Estado: `remember(store)` + escritura directa al store con debounce implícito
(onValueChangeFinished). `enabled = !vm.isRunning` no aplica (el bucle lee el
valor en caliente); el valor se aplica al vuelo.

## 4. Testing

- JVM `LocalVoiceTransportTest`:
  - `custom silence window gates the auto turn`: `silenceEndMs = 3000`,frames
    con ~1,3 s de silencio → sin turno; al superar 3 s → turno.
  - `opening turn generates and speaks the themed greeting`: `openOnStart`,
    fake engine + fake tts → asistente emite output transcript + TTS sin
    input del usuario.
- Existente: auto-turn por defecto, manual mode, ruido sin transcripción.
- En dispositivo: `make deploy ENGINE=glm` + hablar con tema elegido.

## 5. Riesgos

- Apertura automática añade una generación al inicio de sesión (~2 s GLM API,
  ~10-60 s motor local CPU). Mitigación: es la misma coste que cualquier turno
  y se ejecuta en "connecting"→"active" con la UI ya mostrando el tema.
- El slider aplica en caliente al bucle (volatile): sin riesgo de carrera más
  allá de un valor de umbral ligeramente retardado.
