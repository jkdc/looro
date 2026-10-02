# Informe de verificación completa — Mural / looro

**Fecha:** 2026-09-18 · **Rama:** `feature/switchable-conversation-engine` (46 commits por delante de `origin/main`, sin push — deliberado)
**Objeto:** verificación de TODO lo implementado en la remediación del 2026-09-17 (commits `8f87596…754517e`), verificación del proyecto en su conjunto, y **resolución de todos los bugs encontrados** (commits `1f58d92…d0eb807`).
**Método:** (1) re-ejecución completa de todas las suites ejecutables sobre el HEAD final; (2) dos auditorías independientes "de ojos frescos" — una adversarial sobre el código remediado, otra de consistencia global del proyecto; (3) resolución de cada hallazgo por agentes especializados por área; (4) re-review verificada de los 19 hallazgos + batería final.

---

## 1. Resultado ejecutivo

| Área | Estado | Evidencia |
|------|--------|-----------|
| Tests Android (unit, forzados) | ✅ **358 tests, 0 fallos** (353 `:app` + 5 `:core:engine-local`) | `./gradlew :app:testDebugUnitTest --rerun :core:engine-local:testDebugUnitTest --rerun` |
| Build Android (APK + native whisper + androidTest) | ✅ BUILD SUCCESSFUL | `:app:assembleDebug :app:assembleUiTestAndroidTest` |
| Tests backend con PostgreSQL 17 real (docker) | ✅ **358/358, 0 skips** | `TEST_DATABASE_URL=… npm test` (Node 20.19.4) |
| Tests de scripts + contracts | ✅ 54 tests OK · `check_cross_platform.py` OK | `python3 -m unittest discover -s scripts/tests -t .` |
| Workflows YAML + sintaxis shell | ✅ 5 YAML parsean · `bash -n` OK | validación programática |
| Higiene git (secreto, tracking, submódulos) | ✅ limpio | detalles §2 |
| gitleaks (empírico, binario 8.30.1 del CI, historial completo) | ✅ "no leaks found" (90 commits) | ejecutado por el auditor |
| Bugs encontrados en la verificación | 🔧 **19 hallazgos → 19 resueltos** (verificación re-review: todos ADDRESSED) | §3-§4 |
| iOS compilación | ⚠️ **gate pendiente del usuario en macOS** (sin toolchain en esta máquina) | §6 |

**Veredicto:** la remediación es sólida y el proyecto está coherente. La verificación encontró 7 bugs de código reales y 12 inconsistencias de proyecto; **todos fueron resueltos y re-verificados**. Las únicas acciones restantes son las 4 puertas humanas de §6.

---

## 2. Verificación de lo implementado (remediación 2026-09-17)

**Seguridad**
- Prefijo de la clave z.ai ausente en **todas** las revisiones alcanzables (`git rev-list --all` × `git grep` = 0 resultados); `api-keys.properties` sin trackear, presente en disco, ignorado por git; solo el `.example` trackeado.
- gitleaks 8.30.1 (el binario exacto pineado en `secrets.yml`) sobre historial completo: sin fugas. El placeholder del `.example` **no** dispara la regla `generic-api-key` (entropía 3.46 < 3.7), verificado empíricamente, y una clave con forma real sí se detecta.
- `check_android_release.py` endurecido (patrón z.ai + `*.properties`) con tests de regresión que prueban no-divulgación del valor.

**Eliminación del motor local** — cero referencias vivas a llama en código (solo notas de eliminación intencionadas y el verbo español "llamarse" en un prompt de enseñanza); submódulo whisper.cpp único y pineado (v1.9.4); `libmural_whisper.so` compila (arm64+x86_64); fábrica válida sobre `openai|deepseek|glm`.

**Voz, parity, CI, iOS, backend** — verificados por el auditor adversarial (§3) y por las suites; los bugs que la verificación destapó se listan y resuelven en §3-§4.

---

## 3. Hallazgos de la verificación (todos resueltos)

### 3.1 Bugs de código (7)

| # | Sev | Hallazgo | Fix |
|---|-----|----------|-----|
| A1 | Media | **Fuga de corrutinas entre sesiones**: el saludo/comentario sobrevivían a `end()` → contaminaban la sesión siguiente (deltas viejos, micrófono silenciado por el "fantasma", contexto de saludo robado) | `sessionJobs` con cancelación en `stopLoop()`; test end→start en la misma instancia (`1f58d92`) |
| A2 | Media | **Cap de 30 s ausente en modo manual** (principiante): buffer sin límite → OOM potencial en `flatMap` tras minutos grabando | `turnMs` acumula en ambos modos; el modo manual auto-procesa al llegar al cap; KDoc + test de re-armado (`1f58d92`) |
| A3 | Media | **Cancelar no detenía el micrófono**: `end()` quedaba bloqueado detrás de llamadas JNI/OkHttp (hasta ~120 s de micrófono abierto tras "End") | `stopLoop()` para el recorder inmediatamente + `cancelInFlight()` en `TeachingClient` (implementado en `OpenAICompatibleEngine` con `Call.cancel()`); tests; limitación JNI documentada (`1f58d92`) |
| A4 | Baja | **`start()` sin gate de clave** en motores no-OpenAI: la sesión arrancaba y moría en el primer turno | helper `engineKeyReady()` compartido con `cloudReady()`; error antes de crear sesión (`e1c3e1a`) |
| A5 | Baja | **`turnEndRequested` obsoleto**: un "He terminado" en sesión automática cerraba instantly el primer turno de una sesión manual posterior | reset en `connect()` + test de regresión (`1f58d92`) |
| B1 | Media | **iOS — caps solo en lectura**: una respuesta LLM desbocada (>10k/20k chars) envenenaba el archive y la única recuperación era borrarlo todo | clamps en los 3 puntos de escritura con las constantes públicas de `Models.swift` (`ad9a14b`) |
| C1 | Media | **Backend — bucket compartido global**: las rutas exentas de proxy seguían consumiendo el único bucket de 120 req/min por IP-del-proxy → 429 colateral para todos | exentas también del limiter (públicas/baratas; webhooks por firma); test de tormenta 150 req + control negativo (`752c096`) |

### 3.2 Inconsistencias de proyecto (12)

| # | Sev | Hallazgo | Fix |
|---|-----|----------|-----|
| D1 | Alta | README raíz y apps/android/README afirmaban "solo OpenAI" y "claves en Android Keystore" (falso para deepseek/glm — plaintext en external storage) | claims reescritos con selección de motor + almacén real de cada clave (`e218b1d`) |
| D2 | Alta | `post-analysis-remediation/verify-report.md` citaba informes en `.zuperpowers/` inexistentes en el repo | evidencia durable: hashes verificados + conteos de suites (`66198d2`) |
| D3 | Media | Future work sin la "inclusión condicional del módulo nativo" (prometida pre-merge) | añadida + 2 parked más (`66198d2`) |
| D4 | Media | Registro de release hablaba de v4 (code 4) con versionCode 7 en el árbol | nota histórica fechada (`f0348dc`) |
| D5-D13 | Baja | `.gitignore` sin artefactos locales de análisis · `proposal.md` citaba doc no trackeado · `deploy-local.sh` no validaba `ENGINE` con `DEPLOY_NO_BUILD=1` · comentario ambiguo en `gitleaks.toml` · comandos de build sin `:core:engine-local` · `run-on-android.md` 100% OpenAI · `design.md` sin motores/ApiKeysStore · `repository-layout.md` incompleto · `declarations.md` sin nota de claves deepseek/glm | los 9 resueltos (`e218b1d`, `f239150`, `66198d2`, `f0348dc`) |

### 3.3 Nits de la re-review (2, también resueltos)

- `stopLoop()` paraba el recorder desde el hilo llamador concurrentemente con lecturas nativas → `captureLock` en `MicRecorder` serializa read/stop/pause/drain/resume (regiones acotadas, ≤1 frame de espera) (`9da3530`).
- README no decía que el fichero a pushear se llama `api_keys.properties` (underscore) → precisado (`d0eb807`). Además se eliminó la cita de un blob SHA volátil del fichero local de claves en el verify-report (`d0eb807`).

---

## 4. Re-verificación tras los fixes

- **Re-review dedicada de la ola**: **19/19 hallazgos ADDRESSED** con evidencia file:line; sin breakage nuevo Critical/Important; spot-check de las dos clases tocadas (LocalVoiceTransportTest 19/19, OpenAICompatibleEngineTest 9/9).
- **Batería final sobre el HEAD (`d0eb807`)**: backend **358/358** (0 skips, PostgreSQL real) · Android **358 tests, 0 fallos, 0 skips** (forzados) · scripts 54 OK · `bash -n`/YAML OK.
- Árbol limpio: los artefactos locales de análisis ahora están gitignored; nada interno trackeado.

---

## 5. Estado del proyecto

- **Coherencia**: la rama cuenta una sola historia — 3 motores (openai/deepseek/glm) seleccionados en build, pipeline de voz on-device para los no-OpenAI, docs/README/openspec alineados con el código, CI capaz de compilar la rama (submódulos recursivos, tests del módulo nativo, guard de modelo whisper en el emulador).
- **Calidad por área**: backend notable (SQL parametrizado, idempotencia, candades; suites reales con PostgreSQL); iOS bien diseñada pero **pendiente de compilar**; Android con la voz ahora endurecida (caps, cancelación, serialización, sin fugas entre sesiones).
- **Deuda documentada, no oculta**: `openspec/changes/post-analysis-remediation/tasks.md` — rate-limit durable por cliente, inclusión condicional del módulo nativo (pre-merge), clamp-on-load iOS, teardown no aislado del transporte, re-afinado whisper (dotprod/i8mm), verificación en dispositivo real.

## 6. Puertas humanas restantes (ninguna bloqueada por código)

1. **Rotar la clave z.ai** en la consola de Zhipu (la purga del histórico no invalida el valor expuesto; el blob local cambió — señal de que quizá ya se hizo — pero confírmalo).
2. **Compilar y testear iOS en macOS**: `cd apps/ios && swift build && swift test` + build Xcode del target app (los cambios iOS nunca se han compilado; comandos exactos en el verify-report).
3. **Primera ejecución real de CI** como parte del checklist de merge (timeout de 25 min compilando whisper; NDK/CMake en el runner).
4. **Push/PR**: la rama (46 commits) sigue local a la espera de tu decisión.

---

*Generado por la fase de verificación del 2026-09-18: batería completa re-ejecutada por el controlador + 2 auditorías independientes + 4 fixers por área + re-review verificada de los 19 hallazgos. Informe previo: `informe-analisis-2026-09-17.md` (con su sección de resolución).*
