# Informe de batería manual — emulador Android + navegador (panel admin)

**Fecha:** 2026-09-22 · **Rama:** `feature/rebranding-looro` · **Build:** HEAD local con Fase 1 del roadmap (`9e005ad` temario 18/18 + `3ff7464` cierre T3) instalada en el emulador.
**Evidencia:** 39 capturas en `e2e-evidence/bateria-2026-09-22/` (numeradas y referenciadas abajo).

---

## 1. Batería del emulador (app Palique, motor GLM, datos a cero)

Preparación: `make deploy ENGINE=glm SERIAL=emulator-5554` (build con el código de hoy, RECORD_AUDIO, modelo whisper ya en device, `api-keys.properties` con ZHIPUAI_API_KEY) y `pm clear` para batería desde cero. La instalación es *fresh install* del id `com.jkdc.palique.android` (esperado tras el rebranding).

| # | Prueba | Resultado | Evidencia |
|---|---|---|---|
| 1 | Arranque fresco + onboarding paso 1 (idioma; Norsk por defecto, selección English con saludo reactivo "Hi!") | ✅ | `01`–`02` |
| 2 | Onboarding paso 2 (idioma de significados; dropdown con 8 lenguas; elegí Spanish — el saludo cambia a "¡Hola!") | ✅ | `03`–`04` |
| 3 | Onboarding paso 3 (consentimiento IA: copy Palique, almacenamiento local, Privacy policy, 18+) | ✅ | `05` |
| 4 | Pantalla Talk inicial: branding, tema "A little everyday English", saludo EN + subtítulo, mic off, 4 tabs | ✅ | `06` |
| 5 | Learn: curso Foundations con las 6 unidades × 3 lecciones = 18 (todas las nuevas visibles: Questions & negatives, Introducing yourself, People & things, Everyday actions, Getting around, Food & likes, The past begins) | ✅ | `07`–`09` |
| 6 | Tarjeta de estudio de lección nueva ("Like / love / hate + -ing"): explicación, 6 ejemplos con glosa, 6 expresiones objetivo, situación de práctica, temas extra U5 | ✅ | `10`–`11` |
| 7 | Toggle "Read aloud" ON↔OFF y persistencia por sesión de app (vuelve a ON tras reinicio, según diseño) | ✅ | `10`/`11`/`20` |
| 8 | CTA "Practise speaking" → salto a Talk + petición de micrófono del sistema (tras `pm clear`) | ✅ | `12`–`14` |
| 9 | Sesión de práctica de lección con GLM: apertura con la lección + pregunta 1 del guion ("What's your name?"), subtítulos en español | ✅ | `21` |
| 10 | Respuesta escrita (typed reply): sheet "Say it your way", turno "YOU" registrado en la sesión | ✅ | `22`–`25` |
| 11 | Continuidad del guion: el tutor sigue con preguntas 2-3 de la lección ("Where are you from" → "Are you a student?") | ✅ | `26` |
| 12 | Cierre de sesión ("End" → "Until next time", mic off, New conversation) | ✅ | `27` |
| 13 | Intento registrado: la tarjeta L1 pasa de "New" a "Practised 1×" | ✅ | `28` |
| 14 | Settings: idioma de significados cambiado a Spanish y persistido; selector "Conversation provider" con DeepSeek/Zhipu GLM + campo de clave + "Save key" | ✅ | `15`–`19` |
| 15 | Recuperación de clave GLM por `deploy-local.sh` tras el `pm clear` (error claro de la app indicando ZHIPUAI_API_KEY requerida) | ✅ | ver hallazgo H1 |
| 16 | Tab Themes (buscador, categorías, temas globales con iconos) y tab Words (vacío elegante, "Practise writing (0 due)" deshabilitado, nota honesta de barras) | ✅ | `29`–`30` |
| 17 | Estabilidad: 0 `FATAL EXCEPTION` / crashes en todo el logcat de la batería | ✅ | logcat |

### Hallazgos del emulador

- **H1 (informativo, no bug):** tras `pm clear`, la clave GLM (estado de usuario en Keystore/prefs) se pierde y la app muestra un diálogo preciso ("The selected conversation engine needs the ZHIPUAI_API_KEY…"). La recuperación es fácil (Settings→clave o re-push del deploy). Comportamiento correcto y bien comunicado.
- **H2 (P3, copy):** el texto de consentimiento del onboarding dice "…sends audio and selected text to **OpenAI**" aunque el motor configurado sea GLM/DeepSeek/hospedado. Debería decir "your AI provider" o dinamicizarse.
- **H3 (P3, pedagogía):** al responder solo el nombre sin el verbo (watch de la pregunta 1: recast con "I'm …"), el tutor GLM aceptó sin recast. La política de rigor depende del proveedor; con L1 authored el guion se sigue, pero la corrección estricta no es determinista. A revisar en la F3 (evidencia).
- **Limitación conocida:** STT no verificable en emulador (sin micrófono real); la práctica se probó por teclado. El TTS read-aloud no es audible en captura (toggle verificado; audio del emulador sin aserto). Quedan para el gate de Pixel 9.
- Nota de automatización: el teclado flotante de GBoard (overlay "stylus") interfirió la escritura automatizada; no es bug de la app.

## 2. Batería del navegador (panel admin + backend)

**Contexto de entorno:** `https://admin.localhost` (Caddy, TLS interno) no es cargable por el navegador embebido sin instalar la CA de Caddy en el almacén del Chromium en caliente (requiere reiniciar el host ZCode). Alternativa de prueba equivalente: proxy HTTP local efímero (`palique-admin-proxy`, ya eliminado) que sirvió el `admin-ui` estático y reenvió `/v1/admin/*` a la API con las cabeceras de trusted-proxy y el `Origin` del panel — exactamente lo que hace Caddy en producción.

| # | Prueba | Resultado | Evidencia |
|---|---|---|---|
| B1 | Fail-closed sin sesión: `GET /v1/admin/metrics` → **401 `admin_sign_in_required`** | ✅ | curl |
| B2 | Pantalla de acceso: "Palique · Admin", "Inicio de sesión de operador", aviso de auditoría, enlace Google OIDC completo (client_id fake + state + nonce) y vía "Sesión existente (token bearer)" | ✅ | snapshot |
| B3 | Login por token: POST `/v1/admin/auth/session` → cookie → entrada al panel como operador | ✅ | sesión activa |
| B4 | Vista Resumen: 12 métricas con datos reales del entorno (5 cuentas, 45 min wallets, 1 orden $10, 8 acciones admin) + 4 series temporales | ✅ | `31` |
| B5 | Vistas Crecimiento, Voz, Economía, Tablas, Política bienvenida, Campañas, Auditoría — las 8 cargan con título y layout Tabler correctos | ✅ | `32`–`38` |
| B6 | Explorador de tablas: tabla `accounts` con 5 filas reales, filtros por columna, paginador y tamaño de página | ✅ | `35` |
| B7 | Interacción de explorador: cambio a `minute_wallets` → 4 carteras reales (15/15/10/5 min, una con 2 min reservados) | ✅ | `39` |
| B8 | Auditoría: `admin_actions` con 8 entradas históricas con razón y detalle JSON | ✅ | `38` |

### Hallazgos del panel

- **H4 (P3, UI):** al cambiar de tabla en el Explorador, el chip de cabecera de la tarjeta sigue mostrando el nombre de la tabla anterior (`accounts` con datos de `minute_wallets`). Solo copy, los datos son correctos.
- **Bloqueo documentado (no nuevo):** el login Google completo requiere el cliente OAuth real (`GOOGLE_ADMIN_CLIENT_ID` fake) y el dominio TLS del panel; la vía token-bearer del panel cubre el acceso local no interactivo.
- Los contenedores/datos de prueba (admin `bateria-e2e-local`, sesión, proxy) fueron revocados y eliminados al terminar.

## 3. Veredicto

**Batería superada: 25/25 pruebas ejecutadas con resultado esperado (17 en emulador + 8 en navegador), 0 crashes, 3 hallazgos menores (H2, H3, H4 — ninguno bloqueante) y 1 hallazgo informativo (H1).** El flujo estreno de esta Fase 1 — curso completo de 18 lecciones → tarjeta de estudio → práctica guiada con tutor GLM → intento registrado — funciona de punta a punta en emulador. El panel admin sigue operativo y fail-closed. Pendientes que este entorno no puede cubrir: STT/read-aloud audibles (gate de dispositivo real), gate macOS, login Google real y dominio TLS de producción.
