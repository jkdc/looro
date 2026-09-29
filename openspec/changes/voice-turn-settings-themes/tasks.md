# Tasks: voice-turn-settings-themes

## Phase 1 — Silence window configurable

- [x] 1.1 `VoiceManualTurnStore`: `silence_end_ms` (Long, default 1200) con validación 600..3000.
- [x] 1.2 `LocalVoiceTransport.silenceEndMs` (volatile) usado por el auto-VAD; sincronizado desde el ViewModel.
- [x] 1.3 Settings: slider 0,6–3,0 s (visible siempre; nota "on-device voice").
- [x] 1.4 Test JVM: ventana personalizada gating el turno automático.

## Phase 2 — Apertura temática de la sesión de voz

- [x] 2.1 `LocalVoiceTransport.openOnStart` + apertura generada tras `session.started` (usa context con el greeting appendado) → `speakAndEmit`.
- [x] 2.2 Test JVM: apertura emite output transcript + habla sin input del usuario.

## Phase 3 — Verificación

- [x] 3.1 Suites completas en verde (ambos módulos).
- [x] 3.2 Despliegue `ENGINE=glm` en Pixel + verificación UI: tema elegido → apertura hablada, silencio ajustable en caliente.
- [x] 3.3 `verify-report.md` + commit + Engram.
