# Informe de features — Mural / looro

**Fecha:** 2026-09-18 · **Rama:** `feature/switchable-conversation-engine`
**Alcance:** posibles features para las apps (iOS y Android), el backend (`services/api`) y el producto en general.
**Método:** cuatro exploraciones en profundidad en paralelo (iOS, Android, backend, docs/openspec/release) sobre el árbol actual de la rama, más los informes previos (`informe-analisis-2026-09-17.md`, `informe-verificacion-2026-09-18.md`). Cada feature indica dónde engancha en el código actual. Esfuerzo estimado para una persona: **S** ≤ 3 días · **M** 1–2 semanas · **L** > 2 semanas.

---

## 1. Resumen ejecutivo

Mural hoy es una app de práctica conversacional muy sólida en su núcleo (voz en tiempo real, evidencia de aprendizaje validada, 8 idiomas, registros locales), pero casi todo su valor se concentra en **un único bucle**: hablar → ver subtítulos → seguir. Las mayores oportunidades, ordenadas por relación valor/esfuerzo:

1. **Cerrar el bucle de aprendizaje fuera de la conversación** (paquete de quick wins): la fecha de repaso (`dueAt`) ya se calcula y se inyecta al tutor, pero no existe ninguna UI de repaso activo; tampoco hay resumen post-sesión, estadísticas, rachas ni export a Anki. Todo eso se construye sobre datos que **ya se calculan y se guardan hoy**.
2. **Selección de motor en runtime en Android** — la rama se llama así pero el motor se fija en compilación. El registro `ConversationEngineFactory` ya existe; falta persistir la elección y reconstruir el transporte.
3. **Paridad Android → iOS**: reporte de IA, localización en español, flujo de minutos/cartera, control de turno manual. iOS tiene código de cartera (`wallet()`) sin ningún llamador.
4. **Backend: preparar la activación comercial con piezas que faltan y son pequeñas**: refresh tokens, rate-limit durable (ya parked), métricas mínimas, tooling de reconciliación. La voz hospedada y el comercio ya están construidos; lo que falta es verificación real y soporte operativo, no código nuevo grande.
5. **Apuestas grandes opcionales** (requieren decisión de producto): sync cifrado opt-in, motores no-OpenAI en iOS, suscripciones, multi-proveedor hospedado (palanca de coste grande vía el seam `AI_PROVIDER` ya documentado).

**Recomendación de secuenciación:** cerrar primero las gates de release ya abiertas (Android BYOK, gates humanas del informe de verificación) y no apilar features encima de una rama aún sin pushear. Los quick wins del paquete 1 son compatibles con ese cierre.

---

## 2. Estado actual (snapshot)

| Área | Qué existe hoy |
|---|---|
| **iOS** | Voz WebRTC OpenAI-only (BYOK), subtítulos de significado (11 lenguas), lookup por palabra, pinyin, 24 temas + tema de actualidad con fuentes, evidencia de aprendizaje validada con barras de recuerdo (0–3), historial con corrección de pasajes, backup JSON compatible entre plataformas, cuentas gestionadas **compiladas pero apagadas** (`ManagedAccounts.xcconfig=NO`), `wallet()` implementado sin UI. iPhone-only, portrait, light, iOS 26.1, strings en inglés hard-codeados. |
| **Android** | Lo anterior + **motor seleccionable en build time** (openai / deepseek / glm), whisper.cpp STT + TTS del sistema para motores no-OpenAI, turno manual y slider de silencio, **reporte de IA** (`/v1/feedback`), UI en inglés y **español** (222 claves), flujo de minutos de invitado + traspaso a miembro, compras Play/Stripe cableadas pero deshabilitadas. |
| **Backend** | Identidad Google/Apple, ledgers inmutables de dinero y minutos (~40 triggers), comercio AI-value completo (Stripe Managed Payments + Play) apagado, **voz hospedada con reserva→liquidación apagada**, gateway de helpers (meaning/assessment/lookup/delegation/typed_reply/topic/help) con presupuesto, feedback de IA live, waitlist live. Sin admin HTTP (solo CLIs SSH), sin refresh tokens, sin email/push, sin métricas. |
| **Parkeds documentados** | Rate-limit durable, meanings keyless UX, `.so` condicional, transcripciones parciales, diarización, voz hospedada multi-proveedor (seam `AI_PROVIDER`), paridad iOS de motores, commit manual WebRTC, split pt-PT, sin cloud sync planificado. |

---

## 3. Principios que condicionan las features

Cualquier feature nueva debe respetar la posición del producto (CONTRIBUTING.md, README):

- **Registros locales, sin nube**: el archive es un JSON local con caps estrictos y validación en import. Toda feature de datos nuevos debe caber en `ArchiveCodec` (esquema versionado v1→v2 ya existe) o en claves sidecar como `androidFinalAssessments`.
- **La evidencia es sagrada**: todo hecho de aprendizaje pasa por `LearningEngine.validate` (cita literal, confianza ≥ 0.8, downgrade "assisted"). No meter atajos.
- **Paridad de Core obligatoria**: cambios en `apps/ios/Core/` se replican en el core Kotlin y pasan `scripts/check_cross_platform.py`.
- **Sin analytics ni tracking**: `NSPrivacyTracking=false`. Añadir telemetría es un cambio de posición, no una feature más.
- **Disciplina de claims**: nada de promesas de pronunciación/eficacia docente sin revisión de hablante competente (issues #10–#13 abiertos).
- **iOS solo se puede verificar en macOS** (`swift test` + `xcodebuild`); estimaciones iOS no incluyen ese gate.

---

## 4. Catálogo de features

### 4.1 Producto de aprendizaje (multiplataforma)

**K1 · Modo de repaso activo (SRS con UI)**
`WordState.dueAt` se calcula en `LearningEngine` (Swift y Kotlin) con la escalera [1, 1, 4, 14] días y se inyecta al tutor (hasta 3 palabras vencidas por instrucción "thinking"), pero **no existe ninguna pantalla de repaso**: la pestaña Words es solo navegable. Añadir un modo flashcard audio-primero: "¿Cómo dirías X?" → el usuario responde hablando/tecleando → el modelo evalúa con el mismo pipeline de evidencia y actualiza barras. Es la feature pedagógica de mayor valor pendiente y reutiliza todo el sistema existente. *Esfuerzo: M. Riesgo: bajo. Depende: nada.*

**K2 · Resumen post-sesión (recap)**
Tras cada conversación, mostrar una pantalla de cierre: palabras nuevas evidenciadas, correcciones frecuentes, objetivo de enseñanza siguiente, nivel de desafío. Los datos **ya existen**: `FinalAssessmentQueue` produce la assessment final y el backend ya permite helpers (`meaning|assessment|lookup`) tras cerrar la sesión hospedada. Hoy el fin de sesión solo muestra "volverá al saludo en 15 s". *Esfuerzo: M. Riesgo: bajo (no meter ruido en la UI de conversación, como ya decide el código hoy).*

**K3 · Progreso y rachas locales**
Pantalla de estadísticas: minutos practicados, sesiones por semana, palabras por nivel de barra, timeline de capacidades ("finding your voice" hoy es texto pasivo en Words). Todo proyectable del `Archive` local sin datos nuevos. *Esfuerzo: S–M. Riesgo: bajo.*

**K4 · Objetivo diario + recordatorio local (sin servidor)**
Notificación local (UNUserNotificationCenter / WorkManager) a hora elegida, con objetivo de minutos/día. Sin push, sin servidor, coherente con la posición de privacidad. *Esfuerzo: S–M. Riesgo: bajo (pedir permiso con contexto).*

**K5 · Export de vocabulario a CSV/Anki**
Los words con cita, traducción y barras ya viven en el Archive; un export CSV/Anki-compatible es trivial en ambas plataformas y muy pedido en este espacio de producto. *Esfuerzo: S. Riesgo: nulo.*

**K6 · "Continuar conversación anterior"**
El backend ya acepta `history` en `POST /v1/live/sessions`; el transporte local de Android ya renderiza historial real (bug corregido en la remediación). iOS pasa deliberadamente `[]`. Añadir "retomar" desde el historial, con aviso claro de que el contexto viaja al proveedor. *Esfuerzo: M. Riesgo: coste de tokens mayor; copia clara.*

**K7 · Escenarios propios del usuario ("Mis escenarios")**
Los 24 temas son fijos con overrides culturales. Permitir al usuario crear escenarios (texto libre → instrucciones del tutor), guardados en el Archive (requiere versión de esquema o sidecar; respetar caps de texto). *Esfuerzo: M. Riesgo: moderado (validación de import, caps).*

**K8 · Test de nivel inicial (placement)**
Conversación diagnóstica de 3–5 minutos que fija el `challengeLevel` inicial y las capacidades de partida, en lugar de empezar de cero. Engancha en `TeachingPolicy` (prompt nuevo) + `LearningEngine` (validar resultado) + mirror Kotlin. *Esfuerzo: M. Riesgo: necesita validación con hablantes para no sobre/subestimar.*

**K9 · Modo comprensión auditiva**
Variante de sesión: el tutor narra una historia corta y hace preguntas de comprensión; el usuario no ve subtítulos hasta el final. Solo es prompt-engineering en `TeachingPolicy` + un toggle; cero infra nueva. *Esfuerzo: S–M. Riesgo: bajo.*

**K10 · Control de velocidad del tutor**
Ajuste explícito "habla más despacio / más rápido" que se traduzca a instrucción del tutor (hoy la velocidad solo se infiere del challenge level). También serviría a mayores. *Esfuerzo: S. Riesgo: el proveedor puede no honrarlo al 100%; medir.*

**K11 · Noveno idioma (y más)**
El camino está documentado (`docs/add-language.md`): módulo Swift + `export_android_content.py` + fixtures + **revisión obligatoria de hablante competente**. Candidatos naturales: japonés, coreano, neerlandés. Además el split pt-PT ya está anticipado en `docs/language-architecture.md` (no reinterpretar progreso bajo `pt`). *Esfuerzo: M por idioma + revisión humana. Riesgo: claims.*

**K12 · Práctica de escritura dirigida**
Dictado de palabras (teclado o voz) sobre las palabras vencidas de K1, con corrección ortográfica basada en el módulo del idioma. Complementa K1. *Esfuerzo: S–M. Riesgo: bajo.*

### 4.2 App Android

**D1 · Selección de motor en runtime**
El gran pendiente epónimo de la rama: `ConversationEngineFactory` ya tiene registro y lista `available`; hoy la elección es `BuildConfig.CONVERSATION_ENGINE` en compilación. Persistir la elección en Settings, reconstruir el transporte al cambiar, gatear por presencia de clave/whisper model. Ojo: los branch `engine != "openai"` de `MuralViewModel` (cloudReady, start, sendTyped, updatePreferences, startDurationChecks, needsMinuteAccess) deben moverse a estado, no a BuildConfig. *Esfuerzo: M. Impacto: alto — elimina la recompilación como barrera de entrada.*

**D2 · Descarga in-app del modelo whisper**
El modelo (~base q5_1) hoy solo se instala por `adb push` (`make deploy`) — inviable para distribución real. Descarga desde un origen estático con verificación por hash SHA-256 al directorio de ficheros externos, con UI de progreso y gestión de espacio. Decisión necesaria: dónde se hospeda el fichero (releases de GitHub es lo más barato). *Esfuerzo: M. Riesgo: bajo.*

**D3 · Transcripciones parciales y barge-in en el path whisper**
Parkeds documentados (`openspec/changes/on-device-voice`): mostrar texto parcial mientras el usuario habla y permitir interrumpir al TTS. Mejora masiva de la sensación de "conversación" en deepseek/glm. Incluye la mejora de VAD prevista (silero/WebRTC VAD en vez del umbral fijo rms≥0.012). *Esfuerzo: L. Riesgo: latencia STT en gama baja.*

**D4 · Transcripción parcial / commit manual en WebRTC (OpenAI)**
Ya previsto en `voice-turn-settings-themes` (proposal): llevar el turno manual y el slider de silencio también al motor OpenAI. *Esfuerzo: M.*

**D5 · Inclusión condicional de `:core:engine-local`**
Comprometido "before merging to main": el `.so` de whisper (~12 MB) viaja en todos los APKs incluso con `ENGINE=openai`. No es una feature de usuario, pero libera tamaño y desacopla builds. *Esfuerzo: S–M.*

**D6 · Widgets / atajos de "práctica rápida"**
Quick tile o shortcut directo a nueva conversación con el último tema. El pipeline ya arranca en segundos. *Esfuerzo: S–M.*

### 4.3 App iOS

**I1 · Paridad: reporte de IA**
Android ya reporta respuestas de IA a `/v1/feedback` (`ReportController` → `POST /v1/feedback/ai`, live desde el 14/09). iOS no tiene esa superficie. Es la pieza de confianza-usuario más barata de portar y da señal de calidad real por idioma. *Esfuerzo: S–M.*

**I2 · Paridad: localización de UI (español mínimo)**
Android tiene 222 claves EN/ES; iOS tiene strings hard-codeados en Swift. Extraer a String Catalogs y traducir al español al menos (los significados ya soportan 11 lenguas, la UI no). *Esfuerzo: M–L (muchas vistas). Riesgo: bajo pero laborioso.*

**I3 · Paridad: cartera y minutos en la cuenta**
`ManagedAccountClient.wallet()` está implementado y validado sin ningún llamador; el backend sirve `/v1/minutes` para miembro e invitado. Añadir saldo a la hoja de cuenta (tras el mismo flag de build) es el último cable suelto del flujo de minutos en iOS. *Esfuerzo: S.*

**I4 · Paridad: control de turno manual y silencio**
Mismo objetivo que D4 en el transporte WebRTC de iOS (`LiveTransport.swift`). *Esfuerzo: M.*

**I5 · Motores no-OpenAI en iOS (BYOK barato)**
Réplica del trabajo Android: capa OpenAI-compatible (chat-completions) + STT (Speech framework on-device o whisper.cpp) + `AVSpeechSynthesizer`. Rompe la dependencia exclusiva de OpenAI (la queja #1 de coste de entrada). El núcleo ya es cross-platform vía fixtures; el transporte es lo que hay que construir. *Esfuerzo: L. Riesgo: calidad de voz muy inferior a GPT-Live; posicionarlo como modo "práctica económica".*

**I6 · Widget, App Intents, dark mode, iPad**
Expansión de plataforma. El archive es un único blob SwiftData (CloudKit `.none` explícito); un widget necesita App Group + lectura del archive o un snapshot dedicado. Dark mode y iPad chocan con decisiones de diseño actuales (light/portrait "by design") — reabrir solo si hay demanda. *Esfuerzo: M–L cada uno.*

### 4.4 Backend (`services/api`)

**B1 · Refresh tokens y sesiones de larga duración**
Hoy: bearer opaco de 24 h, máximo 10 sesiones, **sin refresh**. Inviable para un servicio de consumo con compras. `auth_sessions` ya existe; añadir refresh rotatorio con reutilización-detectada→revocación. *Esfuerzo: M. Es prerequisito de casi todo lo comercial.*

**B2 · Rate-limit durable por cliente**
Único parked más viejo: generalizar el modelo de `auth-admission.ts` (contadores Postgres por red HMAC) al bucket compartido de 120 req/min que hoy es in-memory y por IP de proxy. *Esfuerzo: M. Riesgo: bajo.*

**B3 · Métricas mínimas y alertas**
Hoy solo eventos JSON por consola. Añadir `/metrics` (Prometheus) sin PII en labels: sesiones hospedadas unresolved por antigüedad, holds abiertos, backlog de `minute_provider_jobs`, tasa de errores por stage, coste proveedor acumulado/día. El propio README del servicio lo exige antes de cobrar. *Esfuerzo: M.*

**B4 · Tooling de reconciliación de sesiones incompletas**
Los holds de sesiones `incomplete` se retienen por diseño hasta reconciliación manual, pero solo existe la función `recoverHostedStartup` — sin flujo de operador. Un CLI/panel que liste, audite y cierre sesiones con su exposición económica es requisito del runbook antes de dinero real. *Esfuerzo: M–L. Bloqueante comercial conocido.*

**B5 · Attestation real (App Attest / DeviceCheck / Play Integrity)**
`TrialAttestor` y el attestor de minutos son interfaces que rechazan todo (`UnconfiguredAttestor` → 503). Implementar los adaptadores reales + persistencia de trial por dispositivo (`trial_devices` ya existe) cierra el agujero de abuso del trial de invitados. *Esfuerzo: M–L.*

**B6 · Motor de campañas de minutos → superficie de producto**
`minute_campaigns` + `prepare/apply` por CLI ya existen (regalos a ≤200 destinatarios por transacción, journal inmutable). Falta: subida de destinatarios, banner/superficie en la app para reclamar, telemetría de claim. Es el mecanismo de marketing de lanzamiento ya medio construido. *Esfuerzo: M.*

**B7 · Notificaciones: email transaccional + (opcional) push**
No existe ningún canal de salida: la waitlist "no envía nada", no hay emails de cuenta ni de soporte. Email transaccional (verificación, recibo de compra, borrado de datos) es prerequisito de comercio real; push de recordatorios es opcional y **choca con la preferencia local** de K4 (recomendación: local primero, push nunca para aprendizaje). *Esfuerzo: M (email) / L (push).*

**B8 · Panel de administración autenticado**
Todas las operaciones son CLIs sobre SSH con `DATABASE_URL` privilegiado. Un panel mínimo (allowlist de IPs + rol `mural_admin` de solo-lectura + acciones acotadas: reconciliation B4, campañas B6, allowlist de voz, consultas de cuenta para soporte) reduce el riesgo operativo a medida que crece el servicio. *Esfuerzo: L (bien hecho). Alternativa: quedarse en CLIs + B3/B4.*

**B9 · Hosted multi-proveedor (seam `AI_PROVIDER`)**
El proposal `switchable-conversation-engine` deja documentado el seam para enrutar voz hospedada a DeepSeek/GLM. Es la palanca de coste más grande del modelo AI-value: la voz GPT-Live es lo más caro del stack. Requiere traducir el protocolo de data-channel propio a chat-completions + TTS streaming del proveedor o del cliente. *Esfuerzo: L. Riesgo: alto; hacerlo después de verificar el transporte OpenAI real.*

**B10 · Endpoint de transparencia de gasto (`GET /v1/usage`)**
El cliente ya muestra estimación local; para cuentas hospedadas, un endpoint de gasto real por periodo (agregado, sin contenido) cierra la brecha "estimate ≠ billing cap". *Esfuerzo: S–M.*

**B11 · Multi-divisa**
El catálogo v2 ya reserva campos `exchangeRate`; activar no-US requiere el gate de impuestos del runbook. *Esfuerzo: M (tras gates de pagos).*

### 4.5 Transversal

**T1 · Cloud sync opt-in cifrado end-to-end** ⚠️ *decisión de producto*
`docs/android/design.md` y el README lo excluyen explícitamente hoy. Pero es la feature #1 de retención del espacio (cambio de teléfono = pérdida total). Diseño compatible con la posición: blob del Archive cifrado en cliente (clave derivada en dispositivo, el servidor nunca ve texto), tabla por cuenta, conflictos por merge-by-new-IDs (el importador ya hace exactamente eso), esquema v3. **Solo si** se decide cambiar la posición "local-first" a "local-first con backup cifrado opt-in". *Esfuerzo: L.*

**T2 · Retención opcional de audio para replay** ⚠️ *decisión de producto*
"No se guarda audio crudo" es claim actual de privacidad. Un replay de fragmentos (escuchar de nuevo lo dicho) requeriría retención local opt-in con borrado automático. Valor de aprendizaje real; coste: cambios en claims de privacidad y App Store. *Esfuerzo: M + decisión.*

**T3 · Telemetría de producto** ⚠️ *decisión de producto*
Sin analytics es posición declarada. El sustituto barato: el canal de feedback AI ya live (B: `/v1/feedback`) + K-reportes; para métricas de funnel, un contador opt-in agregado sería un cambio de posición. No recomendado a corto plazo.

**T4 · Diarización (conversaciones a varias voces)** — parked en `on-device-voice`. Entretenimiento alto, utilidad de aprendizaje dudosa. *Esfuerzo: L.*

---

## 5. Priorización

### Matriz valor × esfuerzo

| | **S** | **M** | **L** |
|---|---|---|---|
| **Valor alto** | K5 export Anki · K3 stats/rachas · I3 cartera iOS · K10 velocidad | **K1 repaso SRS** · **K2 recap** · **D1 motor runtime** · I1 reporte IA · B1 refresh · B2 rate-limit · B6 campañas | D3 parciales/barge-in · I5 iOS multi-motor · B9 hosted multi-proveedor · T1 sync |
| **Valor medio** | K9 modo escucha · K4 recordatorios · D5 `.so` condicional | K6 continuar · K7 escenarios propios · K8 placement · D2 whisper download · I2 localización iOS · B3 métricas · B4 reconciliación · B5 attestation · B10 usage · K12 escritura | B7 email/push · B8 admin panel · I6 plataforma · K11 idiomas · T2 audio replay |
| **Valor menor** | | D4/D6 · B11 · K10 en no-OpenAI | T4 diarización |

### Roadmap sugerido en tres paquetes

**Paquete 1 — Cerrar el bucle (quick wins, ~3–4 semanas):**
K5 → K3 → K2 → K1 → I3 → I1 (+ D5 que ya es compromiso de la rama). Todo se apoya en datos y endpoints existentes; ninguna decisión de posición; cada pieza es shippable por separado. K1 es la feature con mejor ratio valor/esfuerzo de toda la lista.

**Paquete 2 — Acceso y coste (~1–2 meses, en paralelo con gates de release):**
D1 (motor en runtime) + D2 (whisper download) en Android; B1 + B2 + B3 + B10 en backend (prerequisitos baratos de lo comercial); K4, K7, K9 como relleno de producto.

**Paquete 3 — Apuestas (tras la activación comercial o como decisión estratégica):**
B4 + B5 para desbloquear voz hospedada real → B9 (multi-proveedor = coste) → I5 (iOS multi-motor) → T1 (sync, solo con decisión explícita de posición) → K11 (idiomas nuevos, cada uno con su revisión humana).

### No recomendado ahora

- **B8 (admin panel)** antes de tener tráfico: los CLIs auditable ya cubren el volumen actual; B3+B4 dan el 80% del valor por la mitad del coste.
- **T3 (telemetría)** y **T4 (diarización)**: cambian posición o no sirven al núcleo pedagógico.
- **B7 push de recordatorios**: K4 local lo cubre sin servidor y sin posición de privacidad nueva.

---

## 6. Notas de implementación

- **Core compartido:** K1, K2, K3, K8, K9, K12 tocan `Core/` → mirror Kotlin obligatorio + `check_cross_platform.py` verde + fixtures actualizados si cambian contratos.
- **Esquema del Archive:** K7 (escenarios) y cualquier dato nuevo de usuario debe pasar por versión de esquema (el patrón v1→v2 existe en ambas plataformas) o sidecar Android-only; los caps de 30 MB / 10k sesiones aplican también en import.
- **Consent:** K6 (historial al proveedor) y D2 (descarga de modelo) deben pasar por el gate de `AIProcessingConsent` / copia clara; bump de versión de consentimiento si el tratamiento cambia.
- **Claims:** K1/K8/K12 (evaluación de respuestas) y K11 (idiomas nuevos) requieren la revisión de hablante competente antes de cualquier claim público; el pipeline de verificación (`verification/`) está preparado para registrarla.
- **iOS:** todo lo iOS necesita el gate macOS (`swift test` + `xcodebuild`) que sigue pendiente en esta máquina; no estimar merge sin él.
- **Sequencia con release:** las gates abiertas (Android BYOK, informe de verificación §6: rotación de clave z.ai, primer CI real, decisión de push/PR) tienen prioridad sobre cualquier feature de este informe.

---

*Informe generado a partir de cuatro exploraciones en paralelo del árbol actual de la rama (iOS, Android, backend, docs/openspec/release) contrastadas con los informes de análisis (2026-09-17) y verificación (2026-09-18). Los puntos de enganche citados fueron confirmados en código por las exploraciones; las estimaciones de esfuerzo son órdenes de magnitud para una persona.*
