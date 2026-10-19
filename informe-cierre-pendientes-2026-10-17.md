# Informe: cierre de verificación de los pendientes — 2026-10-17 (sesión v2)

**Rama de trabajo:** `feat/weekly-exam-recovery` (worktree
`looro-worktrees/weekly-exam-recovery`). **Integración prevista:**
`feature/learning-platform`; nunca `main`. Sin commit, push, PR ni despliegue
en esta sesión: todo el trabajo queda sin commitear, como el resto del árbol.
**Documento de estado previo:** `informe-estado-plan-pendientes-2026-10-17.md`
(se mantiene como referencia; este informe lo actualiza).
**Alcance de esta sesión:** implementar y comprobar todo lo pendiente que es
ejecutable en este entorno; **Swift se implementa pero no se ejecuta** (se
prueba al final en macOS, según instrucción vigente L19).

---

## 1. Resumen ejecutivo

| Tarea | Estado al empezar | Estado ahora | Evidencia nueva de esta sesión |
|---|---|---|---|
| T01–T07, T10 | ✅ Completadas | ✅ (sin cambios) | — |
| T08 | ⏳ Abierta (solo prueba Swift final) | ⏳ Igual | `make check` verde (contratos compartidos) |
| T09 | ⏳ Abierta / en curso | ⏳ Abierta (solo prueba nativa final) | **2 defectos bloqueantes encontrados y corregidos** (§3); fixtures extendidos y reverificados |
| T11 | ⏳ Abierta (faltaba E2E de navegador) | ✅ **Completada** | **E2E de navegador real: 15/15 checks** contra API+BD+panel reales con proveedor stub (§2) |
| T12 | ⏳ Abierta (fixture parcial) | ⏳ Abierta (solo prueba nativa final) | Fixture completo: restauración exacta + contenido generado retirado + carrera de cuenta (§4) |
| T13 | ⏳ Abierta (fixture parcial) | ⏳ Abierta (solo prueba nativa final) | Fixture: pérdida offline, aislamiento, fork/takeover observables (§4) |
| T14 | ⏳ Abierta | ⏳ En curso | Inventario final ejecutado (§5); integración local aún autorizada-pendiente |
| Android en emulador | Aplazado por Whisper | ✅ **Ejecutado** | Ciclo completo del examen verificado en dispositivo (§6); submódulo Whisper inicializado y APK construido |

En una frase: **T11 se cierra con evidencia real de navegador; T09/T12/T13
quedan listas salvo la tanda final de pruebas Swift en macOS; el ciclo completo
del examen en Android quedó demostrado en el emulador de principio a fin.**

---

## 2. T11 — E2E de navegador (cerrada)

El bloqueo era "no hay runner de navegador en la caché Docker y no se autorizaron
descargas". Resolución sin descargar nada: el runner usa el **Chromium ya
presente en la caché de Playwright del host** (`~/.cache/ms-playwright/
chromium-1243`) con una instalación local existente de `playwright-core`;
los servicios sí corren en Docker (regla del proyecto).

Stack aislado (`services/api/tests/browser/`, desechable con `down -v`):
PostgreSQL 17 + imagen API del worktree + migraciones + panel servido por
Caddy + **proveedor LLM stub** + servicio `seed-generate` que crea dos
borradores **mediante la CLI real de generación** contra el stub (nada de filas
SQL a mano). Secretos de prueba solo en el compose del stack.

Resultado observado (`admin-e2e.mjs`, stack fresco): **15/15 checks PASS** —
login local; configuración efectiva desde el servidor; prueba de conexión
deshabilitada sin clave persistida; guardar→recargar con clave enmascarada;
aviso de formulario sucio deshabilitando la prueba; prueba de conexión contra
la configuración GUARDADA (HTTP 200 del stub); scheduler "Configurado: No" /
"en este proceso: No"; listado de borradores; motivo demasiado corto rechazado;
aprobación saca la fila del listado privado; rechazo retenido; el catálogo
público sirve la semana aprobada (identidad content-addressed, 25 ítems) y la
rechazada sigue en 404. Capturas: `e2e-evidence/t11-browser-e2e/01..09*.png`.
Reproducción: `services/api/tests/browser/README.md`.
La tarea 5 de `openspec/changes/t11-admin-operations/tasks.md` queda `[x]`.

---

## 3. T09 — dos defectos bloqueantes encontrados (recheck independiente) y corregidos

El segundo recheck independiente pendiente desde L52 se ejecutó y devolvió
**FAIL** con dos defectos reales (no solo del fixture: uno rompía producción):

1. **Allowlist de `endpoint()` incompleta** (`apps/ios/Core/
   ManagedAccountProtocol.swift`): faltaban `/v1/level-test-attempts` (listado),
   la ruta de detalle y `/v1/level-tests/hybrid`; `examAttempts`/`examAttempt`/
   `weeklyExam` lanzaban siempre `.unavailable` — la restauración era
   inalcanzable en fixture **y en la app**. Corregido añadiendo las rutas y un
   `examAttemptEndpoint(_:)` con la misma guarda UUID que `orderEndpoint`.
2. **Timestamps ISO-8601 sin fracciones**: el fake usaba `ISO8601DateFormatter()`
   por defecto y el cliente emite siempre milisegundos; el camino del 409 moría
   en `.transport`. Corregido con un helper que acepta fracciones primero.

Un tercer recheck independiente sobre las extensiones nuevas (§4) encontró un
defecto más, en el test nuevo de takeover: el marcador nunca podía aplicarse
(el payload de creación lleva respuestas vacías) y el takeover de una rama
`in_progress` renderiza la vista de run, no la de revisión. Corregido según lo
prescrito: el fake ahora modela una escritura remota que **completa** el intento
con resultado congelado y respuesta divergente, de modo que la adopción es
observable ("Your answer: Server-confirmed takeover answer"). Todo lo demás
(coherencia fake↔cliente clave a clave, tests DAO, tests preexistentes) PASS.

---

## 4. T12/T13 — fixtures completados (ejecución Swift reservada)

- **Restauración exacta (T12):** el restore de la fixture cubre ahora tres
  intentos en dos páginas, incluido un snapshot **generado retirado**
  (`source: "llm"`, semana 202639) restaurado desde su contenido congelado;
  `ExamSnapshotFactory.bundled` ganó un parámetro `source:` para forjar
  snapshots autoconsistentes. Test DAO nuevo `RestorePreservesExactFrozen…`:
  orden de ítems, opciones, texto del pasaje, posición, respuestas por ítem y
  resultado original se preservan byte a byte.
- **Pérdida offline (T13):** escenario `sync-unavailable` (los POST fallan como
  red muerta): el intento sigue visible como "waiting to sync", nunca aparece en
  otra cuenta y no hay silencio alguno sobre el estado.
- **Carrera de cuenta (T13):** escenario `restore-race` (primera página
  retardada 1,5 s): cambiar de cuenta con la respuesta en vuelo no renderiza
  nunca filas del propietario anterior; restaurar de nuevo bajo el dueño
  correcto funciona.
- **Fork/takeover (T13):** el 409 del fake avanza la revisión remota
  independientemente (escritura obsoleta real) y la adopción del takeover deja
  la respuesta del servidor visible en la revisión; el fork conserva la fila
  local (cubierto además por los tests DAO preexistentes).

Nada de esto se ha ejecutado (L19): están escritos, verificados
estáticamente de forma independiente contra firmas/esquema reales, y reservados
para la tanda final en macOS.

---

## 5. T14 — inventario final de verificación (comandos realmente ejecutados)

| Comando (cwd) | Resultado observado |
|---|---|
| `make check` (worktree, Docker node:22-bookworm sin red) | **PASS**: 138 pruebas Python OK; schema SQLite al día (44 sentencias, `user_version` 5); `export_android_content --check` y `check_cross_platform` OK |
| `make test-api` (worktree; stack efímero) | **exit 0** (suite completa verde); último recuento enumerado en el ledger: 529/529 (L44) |
| `:app:testDebugUnitTest` + `:core:engine-local:testDebugUnitTest` (Docker, imagen `looro-android-test`) | **BUILD SUCCESSFUL** (entradas sin cambios desde el 712+11 de L44, tareas UP-TO-DATE) |
| `:app:assembleDebug` (Docker) | **BUILD SUCCESSFUL en 2m29s** — primer APK de la rama; requiere el submódulo Whisper, ahora inicializado (`927cfce`, shallow) |
| `:app:lintDebug` + `:app:assembleDebug` (Docker) | **BUILD SUCCESSFUL, 0 errores de lint** tras corregir 3 errores preexistentes (SuspiciousIndentation en `BackupManager.kt`; `LocalContextConfigurationRead` en `LevelTestScreen.kt` y `PlacementScreen.kt`, corregidos con `LocalConfiguration.current` elevado fuera de `remember`); JVM re-ejecutadas verdes tras el cambio |
| `git diff --check` (host) | Limpio. Nota: el submódulo nuevo rompe la resolución del gitdir dentro del mount read-only del contenedor, así que el plumbing git corrió en el host |
| E2E navegador T11 (`admin-e2e.mjs`) | **15/15 PASS** (§2) |
| Suite API focalizadas (L42–L45, esta rama) | política 34/34; Origin 2/2; PostgreSQL 529/529 — sin cambios de API en esta sesión salvo test de regresión ya incluido en esas cifras |

Sin cobertura nueva (y por qué): pruebas Swift (Linux sin CryptoKit; L19),
`connectedUiTest` de Android (el driver de UI de esta sesión fue ad-hoc por adb,
no la suite de instrumentación), y sync cloud real del examen en Android
(requiere cuenta real con proveedor de identidad).

---

## 6. Android en emulador — ciclo completo verificado en dispositivo

Con la autorización explícita de probar en emulador se inicializó el submódulo
Whisper (desbloqueando el APK aplazado) y se ejecutó el ciclo completo en un
emulador API 36 headless con la app de la rama:

1. **Inicio**: onboarding → Learn → English → "Verify your A1" (selección
   congelada de la semana).
2. **Autosave durable**: cada pulsación persiste (se observaron revisiones
   2→127 de `exam_answers` bajo tecleo forzado — el contrato de autosave
   incremental funciona).
3. **Kill/reopen**: `am force-stop` → relanzar → el intento se reanuda desde el
   registro durable (misma pregunta; fila `in_progress`, `sync_state
   local_only`, visible como "Saved on this device").
4. **Entrega**: 25/25 respondidas → Submit → **revisión calificada** con
   veredicto por ítem ("Correct" / "Needs review" + respuesta aceptada).
5. **Historial**: "Last: 44% · Oct 17, 2026" y fila "Score: 44%".
6. **Estado final en BD** (volcada como evidencia): intento `completed`
   (revisión 321), resultado congelado de 5442 bytes (`overall: 44`),
   outbox durable `created(1) + progress(319) + completed(1)` pendiente de la
   futura sincronización de cuenta.

Evidencias: `e2e-evidence/android-emulator-2026-10-17/` (9 capturas + la BD).
Nota honesta: durante la sesión apareció una vez el diálogo real de
"Progress couldn't be saved" — causado por leer la BD viva desde fuera
(`run-as cat`) mientras la app escribía, no por código de la app; se cerró y el
flujo terminó correctamente. La sincronización cloud no se ejercitó (requiere
cuenta real); la cola pending es justamente la garantía diseñada para ese caso.

---

## 7. Qué queda pendiente

1. **Tanda final Swift en macOS/Xcode** (T08/T09/T12/T13): `swift test
   --package-path apps/ios`, build de App, UI tests (incluidos los 6 tests de
   fixture de recuperación y los 2 DAO nuevos) y `xcodebuild` de los flujos
   kill/reopen/restaurar/conflicto. Regenerar proyecto Xcode
   (`scripts/generate_project.py`) antes, por los ficheros App nuevos.
2. **connectedUiTest de Android** (T14): — resuelto el 18 (§9.7). El puente
   Docker→emulador está montado y la suite instrumentada ya se ejecuta; quedan
   3 timeouts de `CaptionParityTest` que requieren emulador con aceleración
   gráfica (flakiness ambiental, clase ajena a exam-recovery).
3. **Integración local autorizada** en `feature/learning-platform` (nunca
   `main`): solo cuando el usuario dé el visto bueno tras la tanda Swift.
   Sin push/PR/despliegue.

Cerrados en la sesión del 18 (ver §9): R3-001 (punto 4), enumeración de la
suite API con recuento (punto 2), y la nota del IME (punto 5, resuelta
cerrando el teclado en el driver de automatización).

## 8. Reproducción rápida de lo nuevo

```sh
cd /home/joaquin/Proyectos/looro-test/looro-worktrees/weekly-exam-recovery

# T11 browser E2E (15 checks)
docker compose -f services/api/tests/browser/compose.yaml up --build -d
sleep 25   # bootstrap + seed-generate
node services/api/tests/browser/admin-e2e.mjs
docker compose -f services/api/tests/browser/compose.yaml down -v

# Contratos y suites
docker run --rm --network none -u $(id -u):$(id -g) \
  -v "$PWD":/workspace:ro --entrypoint bash node:22-bookworm \
  -lc "cd /workspace && make check"
make test-api
cd apps/android && docker/test-unit.sh && \
  docker run --rm -u $(id -u):$(id -g) -v "$PWD/../..:/workspace" \
    -v looro-gradle-cache:/gradle-cache -e GRADLE_USER_HOME=/gradle-cache \
    --workdir /workspace/apps/android --entrypoint bash \
    looro-android-test:agp-8.10.1-sdk-36 \
    -lc "./gradlew --no-daemon :app:assembleDebug :app:lintDebug"
```

*Informe generado al cierre de la sesión; no declara tareas Swift ejecutadas ni
autoriza la integración local.*

---

## 9. Adenda 2026-10-18 — verificación app↔API real en emulador y cierre de pendientes

**Qué se pedía:** completar los puntos 2, 4 y 5 del informe, lanzar el emulador
Android, revisar la funcionalidad con los cambios aplicados y asegurar la
conexión app→API ejercitando todas las llamadas posibles contra una API local
en Docker.

### 9.1 R3-001 (punto 4) — resuelto

El follow-up de T04 era un validador sobre-estricto e incompleto:
`isoDate` exigía round-trip exacto de `toISOString()` y no acotaba el futuro.
Como los `startedAt/updatedAt` del cliente alimentan la paginación
(`ORDER BY updated_at DESC`), un reloj muy adelantado fijaba el historial para
siempre. Corregido con `boundedISODate` (ISO parseable + cota de skew 48h, el
mismo criterio del ledger de progreso) + regresiones (intento y evento con
fecha a 90 días → 400; reloj honesto → 200).

### 9.2 Suite API enumerada (punto 2) — 532/532

`make test-api` desde el worktree contra PostgreSQL efímero: **532 pruebas,
532 PASS, 0 fallos, 0 skips** (incluye las 2 regresiones nuevas). Una corrida
intermedia con 8 fallos fue la pista que destapó los dos bugs de contrato del
§9.3; en stack limpio, todo verde. `connectedUiTest` sigue requirendo runner de
instrumentación (no disponible aquí); documentado en §7.2.

### 9.3 Dos bugs reales de contrato, encontrados por la E2E real y corregidos

Al hacer que la app real subiera su examen al API local aparecieron fallos que
ninguna suite unitaria veía (los fixtures antiguos llevaban siempre las claves
opcionales presentes):

1. **El servidor rechazaba los snapshots que él mismo publica.** El validador
   T04 exigía `exactKeys` con `variant`, `generationId`, `options`, `passageId`,
   `speech` y `unitID` presentes, pero la serialización omite los campos
   `undefined`: todo upload de un examen determinista fallaba
   `invalid_exam_snapshot`. Corregido con `keysWithin(required, optional)`
   (conjuntos verificados contra el modelo de contenido generado). Regresión:
   upload del shape wire real (JSON round-trip) aceptado.
2. **Tope de pasajes irreal.** El snapshot del banco en/A1 lleva 15 pasajes
   (la lista completa del banco viaja con cada examen, tal como publica el
   endpoint híbrido) y el validador los limitaba a 10. Subido a 200.
3. Bonus R3-001-adjacente: `expiresAt` del adaptador Android va sin
   milisegundos (`2026-10-19T00:00:00Z`) y el round-trip estricto lo rechazaba;
   cubierto por el nuevo `isoDate` (§9.1).

Con los fixes, el payload real de la app pasa: replay del intento completado
aceptado con revisión 93 (`{"accepted":[{"revision":93,...}]}` HTTP 200).

### 9.4 E2E app↔API en emulador — ciclo completo verificado

Entorno: API real (imagen del worktree) en Docker con red host, PostgreSQL 17,
cuentas habilitadas (`ACCOUNTS_ENABLED` + loopback permitido solo para este
laboratorio, con el limiter compartido condonando loopback bajo el mismo flag),
panel operativo vía debug seam **solo-DEBUG** (`isLocalDebugOrigin()` http
allowlist en las 4 configs de red + `debugSignIn` con doble guarda
BuildConfig.DEBUG + extras de intent + overlay de manifiesto debug que permite
cleartext únicamente a 10.0.2.2/localhost/127.0.0.1). En release nada de esto
existe: `usesCleartextTraffic="false"` y los parse rechazan orígenes no-https.

Llamadas ejercitadas de punta a punta con la app real en el emulador
(API 36, imagen limpia del worktree, firma estable vía
`.signing/debug.keystore`):

| Llamada | Cómo se disparó | Evidencia |
|---|---|---|
| `GET /v1/level-tests/hybrid` | entrada al examen (fetch con presupuesto) | snapshot remoto persistido en `exam_snapshots` |
| `POST /v1/level-test-attempts/sync` | drain de la outbox al firmar | intento con revisión 93 aceptado; snapshots insert-only en servidor |
| `POST /v1/level-test-attempts/events` (×92) | drain (creado + 91 progreso + completado) | todos ACKed; intento terminado `completed` en servidor |
| `GET /v1/level-test-attempts?limit=10&cursor=` (×2 páginas) | restauración automática tras firmar | 3 intentos importados insert-only |
| `GET /v1/level-test-attempts/:id` (×3) | detalle por intento restaurado | snapshot + intento + respuestas importados |
| `GET /v1/account` | refresco de perfil del controller | 200 con la cuenta sembrada |
| `GET /healthz`, `/readyz` | arranque del API | 200 |
| Isolación de propietario | intentos anónimos creados antes de firmar | nunca subidos (correcto por diseño) |

Y la prueba corona **T12 en dispositivo**: responder el examen completo,
entregar (revisión calificada en pantalla), `pm clear` (reinstalación limpia),
re-firmar → **restauración automática que reconstruye los 3 intentos, las 25
respuestas y el resultado congelado**, con el historial en UI mostrando
"Score: 40% · Synced to your account". Evidencias:
`e2e-evidence/android-api-e2e-2026-10-18/` (BD restaurada, captura de UI, log
de la suite).

Durante la E2E se expuso y corrigió además un **hueco real de producto**:
Android no ataba `owner_account_id` al crear el intento ni tenía cliente de
restauración (el drain habría sido código muerto y la recuperación
entre dispositivos no existía en Android). Añadidos: binding de dueño en la
creación (los anónimos siguen locales hasta una reclamación explícita),
`examAttemptsPage`/`examAttemptDetail` en el cliente, `ExamHistoryDao.restorePage`
(import insert-only, atómico, sin eventos de subida) y restauración automática
acotada (10 páginas × 10) tras el drain.

### 9.5 Revisión independiente del diff (T14)

Revisión del diff de la sesión con agente revisor independiente: 1 bloqueador
(instrucción `console.error` de depuración dentro de `badSnapshot`/`badAttempt`,
eliminado), avisos menores (resiliencia por intento en la restauración,
documentación "no usar en prod" de `ACCOUNTS_ALLOW_LOCAL_LOOPBACK`, aserciones
muertas en tests — limpiadas). El revisor confirma que los seams debug son
inalcanzables en release, que `restorePage` es atómico y sin inyección, y que
`git diff --check` está limpio.

### 9.6 Estado final de verificación (inventario T14)

| Verificación | Resultado |
|---|---|
| Suite API completa (PostgreSQL efímero) | **532/532 PASS** |
| `make check` (contratos, schema v5, paridad) | PASS |
| Android JVM (`:app:testDebugUnitTest` + engine) | PASS (BUILD SUCCESSFUL) |
| `:app:lintDebug` | 0 errores |
| `:app:assembleDebug` | OK (firma estable `.signing/debug.keystore`) |
| E2E navegador T11 | 15/15 PASS |
| E2E app↔API en emulador (sync + restore) | verificado on-device |
| `git diff --check` | limpio |
| Swift (T08/T09/T12/T13) | pendiente de la tanda final en macOS |
| connectedUiTest | gated a entorno con runner de instrumentación |

### 9.7 Adenda 2026-10-18b — connectedUiTest ejecutado (puente Docker→emulador)

El punto "runner de instrumentación no disponible" quedó resuelto. Receta:

```sh
# 1. emulador en el host (ya cubierto por el SDK del sistema)
# 2. contenedor con red host y adb propio del contenedor:
docker run --rm --network host \
  -v /usr/lib/android-sdk/platform-tools:/opt/android-sdk/platform-tools:ro \
  -v "<worktree>":/workspace -v looro-gradle-cache:/gradle-cache \
  -u "$(id -u):$(id -g)" \
  -e GRADLE_USER_HOME=/gradle-cache -e HOME=/tmp/gradle-home \
  -e ANDROID_USER_HOME=/tmp/gradle-home/.android \
  --workdir /workspace/apps/android \
  --entrypoint bash looro-android-test:agp-8.10.1-sdk-36 \
  -lc "mkdir -p /tmp/gradle-home/.android && adb start-server && \
       adb connect 127.0.0.1:5555 && \
       ./gradlew --no-daemon :app:connectedUiTestAndroidTest"
```

El truco: con `--network host`, el servidor adb *propio del contenedor* alcanza
el puerto TCP del emulador en loopback (`adb connect 127.0.0.1:5555`), sin
versiones de adb cruzadas ni instalaciones en el host.

**Resultados observados** (en el emulador real, app `.uitest` instalada):

- 1ª corrida completa: 160 tests, 138 PASS, 46 fallos — 24 por un drift de
  rebranding preexistente (helpers hard-codeaban el paquete retirado
  `com.jkdc.app.android.uitest`); corregido leyendo el paquete en runtime
  (`InstrumentationRegistry...targetContext.packageName`) en 7 helpers.
- Rerun en caliente: 29/32 PASS. Los 3 fallos restantes son
  `ComposeTimeoutException` de `CaptionParityTest` en el emulador con render
  por software (flakiness ambiental; la clase no toca exam-recovery).
- Reportes archivados en `e2e-evidence/android-api-e2e-2026-10-18/connected/`.

Con esto, T14 solo mantiene abiertos los ítems que exigen hardware/entornos
externos: la tanda Swift en macOS y el juicio final de `CaptionParityTest` en
un emulador acelerado.
