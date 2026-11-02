# Verify report: proxy-model-from-panel

Fecha: 2026-11-03 (rev. 2, tras la ronda de corrección `abf62e2`) · Rama:
`develop` · Alcance del change: `9ba7403..abf62e2` (8 commits de
servidor/Android/documentación/pruebas).

## Veredicto: **pass en suites** (server, contratos, Android) — **E2E de
laboratorio pendiente** (sin dispositivo en esta sesión)

Todas las suites automatizadas están en verde, incluida la de integración
completa del servidor tras corregir los dos defectos que su primera pasada
destapó (commit `abf62e2`). Lo único que queda para cerrar el change es el
E2E manual de laboratorio con dispositivo (comandos pendientes más abajo).

## Qué se ejecutó y pasó

| Verificación | Resultado |
|---|---|
| `cd services/api && npx tsc --noEmit` | **Verde** (exit 0, sin errores) |
| `make test-api` (stack efímero PG aislado) | **Verde tras el fix `abf62e2`**: **871 tests / 871 pass / 0 fail / 0 skipped / 0 todo**, 0 líneas `not ok`, teardown limpio del stack de humo |
| `make check` (exporters `--check`, cross-platform, db schema) | **Verde**: 150 tests Python OK en 2,4 s; schema SQLite sincronizado (152 statements, `user_version 7`) |
| `cd apps/android && ./gradlew :app:testDebugUnitTest :app:lintDebug` | **Verde**: `BUILD SUCCESSFUL in 4m 21s`; **865 tests unitarios de `:app`, 0 fallidos, 0 omitidos**; lint 0 errores (75 warnings y 22 hints preexistentes) |

Evidencia del re-run de `make test-api`: registro completo en
`/tmp/task3-fix1-test-api.log` (tally final re-verificado: `# tests 871 /
# pass 871 / # fail 0 / # cancelled 0 / # skipped 0 / # todo 0`, cero `not
ok`, y el `tail` del log muestra la destrucción del volumen y red de
`looro-smoke` al terminar). Resumen de la ronda de corrección y de los
pasos de verificación intermedios: `.zuperpowers/sdd/2026-11-02-proxy-model-from-panel/task-3-report.md`
(sección "Fix round 1 (Task 8 full-suite verification reopen)").

Notas de ejecución: la primera pasada de `make test-api` y la build Android
se lanzaron en paralelo con salida a log y sondeo (`nohup`); la suite de
integración corre sobre el stack efímero `looro-smoke` (PG 17 aislado), que
se destruye al terminar. El stack de laboratorio del usuario (`api-api-1`,
API publicada en 0.0.0.0:8080) quedó en marcha y **no** se tocó ni se
reconstruyó.

## Ronda de corrección `abf62e2` — defectos que destapó la primera pasada

Las tareas 3–5 aplazaron explícitamente `make test-api` a la verificación
final, así que la primera pasada de la suite completa (832 OK / 7 fallos +
colgado, abortada) fue el primer contacto real con las integration cases.
Los dos defectos, ambos de pruebas y corregidos test-first en
`abf62e2` (`test(proxy): fix main-wiring stubs and admission-ordering
expectations`):

1. **Stubs de main-wiring sin `resolveLlmConfig`** (6 fallos): `src/main.ts:15`
   importa `resolveLlmConfig`, y el loader
   (`tests/support/main-wiring-loader.mjs`) redirige todas las importaciones
   relativas del entry principal a un único módulo stub
   (`tests/support/main-wiring-stubs.mjs`) que no lo exportaba → el hijo
   moría en la instanciación ESM
   (`SyntaxError: ... does not provide an export named 'resolveLlmConfig'`).
   Corrección: los stubs ganan `export async function resolveLlmConfig() {
   return undefined; }` (inerte, convención del fichero); auditoría mecánica
   del resto de imports de `main.ts` (solo quedaban `Database` y
   `AdminAuthServices`, tipos inline borrados en runtime).
2. **`token_balance_exhausted` vs. política canónica + fuga del servidor
   stub** (1 fallo + colgado de >12 min): el test esperaba que
   `{messages: []}` con saldo a cero rechazara por saldo, pero la política
   canónica valida `messages` antes de la admisión
   (`request_policy_violation`, 400). Además el test abortado dejaba su
   proveedor stub sin cerrar y el fichero colgaba el runner secuencial
   (5 ficheros posteriores sin ejecutar). Corrección: la aserción de
   mensajes vacíos ahora espera `request_policy_violation` y se añadió una
   petición bien formada desde la cartera drenada que mantiene cubierta la
   denegación por saldo (`token_balance_exhausted`, 402, sin llamada al
   proveedor); todo el cuerpo post-listen queda envuelto en
   `try/finally { provider.close(...) }`.

Tras el fix, el re-run completo (`make test-api`) llegó a término verde con
el tally de arriba y teardown limpio.

## Qué no se ejecutó (explícito)

**Step 3 del brief — E2E de laboratorio con dispositivo: no ejecutado.** No
hay teléfono ni emulador conectado en esta sesión. El stack de laboratorio
sí está en marcha (`api-api-1`, sano, 9 h; API publicada en 0.0.0.0:8080) y
se respetó: no se paró ni se reconstruyó — por eso la lista de comandos
pendientes empieza por la reconstrucción, que además es necesaria para que
el contenedor del lab incluya los commits del change.

Comandos pendientes (según el brief y `docs/local-android-lab.md`):

1. Reconstruir y relanzar el lab:
   ```sh
   docker compose --project-directory services/api -f services/api/compose.yaml \
     -f services/api/compose.lab.yaml build api migrate && make lab-up && make migrate
   ```
2. En el panel (`make admin-open`, operador previo con
   `make admin-add USER=<nombre> EMAIL=<email>`), configurar
   "Configuración de IA" con provider `opencode` y un modelo gratuito
   (p. ej. `glm-5.3-flash`).
3. `make lab-account` → adoptar la sesión en el dispositivo con el comando
   `adb shell am start …` que imprime; `make lab-install`.
4. Seleccionar el motor **Palique API** y ejecutar un turno de conversación.
5. Confirmar en el servidor:
   ```sh
   docker compose --project-directory services/api -f services/api/compose.yaml \
     -f services/api/compose.lab.yaml exec api node dist/src/tokens-admin.js show <LAB_ACCOUNT_ID>
   ```
   → el intent queda settled y el stub es el modelo del panel.

Las capturas de esa sesión deben ir a `E2E_EVIDENCE_DIR` (directorio
ignorado del SDD), sin sobrescribir las evidencias versionadas.

## Nota de reconciliación con `proxy-api-provider`

Los dos deltas de especificación de `proxy-api-provider` que dependían del
enrutado por propósito quedan **invalidados (superseded) por este change**,
que los marca como REMOVED en `specs/proxy-routing/spec.md`:

- **"Server-owned model per purpose"** — la tabla de rutas por propósito, el
  header de propósito, el rechazo `unknown_purpose` y la ruta legacy por
  defecto desaparecen; el modelo lo decide por llamada la configuración LLM
  del panel (nuevo requisito "Server-owned model", sin propósito).
- **"Policy endpoint is display-only"** — `GET /v1/conversation-policy` y su
  plumbing cliente están eliminados; el motor `api` no hace round-trip de
  política (requisito MODIFIED "First-party api provider in the app").

Lo demás de `proxy-api-provider` **sigue en vigor** y este change no lo
revoca: reserva antes de dispatch y liquidación exactamente-una-vez
("Reservation before dispatch"), allowlist de política de petición (ahora
MODIFICADA en la práctica: `model`/`max_tokens`/`stream` del cliente se
aceptan y sobreescriben en vez de rechazarse — ver `src/proxy-policy.ts`),
cuota diaria de suscripción vía ledger ("Daily subscription quota"),
proveedor first-party en la app sin material de claves, y proveedores de
debug retenidos con gate de release.

## Nota sobre el lab y trabajo sin commitear del usuario

`docs/local-android-lab.md` está commiteado y referencia los targets
`make lab-up / lab-install / lab-account / lab-down`, pero su implementación
vive **en el árbol de trabajo sin commitear del usuario**: `Makefile`
(modificado, targets `lab-*`), `services/api/compose.lab.yaml`,
`scripts/lab_env_keys.py` y `services/api/lab-account.sql` (sin trackear).
Hasta que ese trabajo se commitee, un checkout limpio no tiene los targets
que el documento describe. Esta verificación se limitó a constatarlo; los
ficheros sucios existentes no se han tocado ni staged.

## Para cerrar el change

1. ~~Corregir el defecto 1 (stubs de main-wiring)~~ — hecho en `abf62e2`.
2. ~~Resolver el defecto 2 (política vs. saldo + fuga del stub)~~ — hecho en
   `abf62e2`, cubriendo también la denegación por saldo con petición bien
   formada.
3. ~~Reejecutar `make test-api` completo hasta verde con recuento final~~ —
   hecho: 871/871, teardown limpio.
4. Ejecutar el E2E de laboratorio (comandos de arriba) con dispositivo y
   adjuntar evidencia — **único pendiente**.

Nota residual de la ronda de fix (para una futura pasada de pruebas):
`per-account cap refuses a second concurrent call…` en
`tests/token-proxy.test.ts` conserva el patrón sin `try/finally` alrededor
de `provider.close()`; quedó fuera del alcance de `abf62e2` y es el único
test del fichero que podría colgar la suite si su aserción falla (ver
task-3-report.md).

## Final review (2026-11-03)

La revisión final del change destapó cuatro defectos residuales, corregidos
en el commit único de esta ronda
(`fix(proxy): final-review fixes — test teardown, cap validation, ios
mirror contract`):

1. **Teardown del test de cap por cuenta** (`tests/token-proxy.test.ts`):
   el cuerpo posterior a `provider.listen` de `per-account cap refuses a
   second concurrent call…` no estaba envuelto en
   `try/finally { provider.close() }` — una aserción fallida habría dejado
   el proveedor stub abierto y colgado el runner secuencial (>12 min
   demostrados). Envuelto con el mismo patrón aplicado al test de metering
   en `abf62e2`; la nota residual de la sección anterior queda resuelta.
2. **`PROXY_MAX_CONCURRENT` vuelve a exigir entero seguro**
   (`src/token-proxy.ts`): la validación había quedado relajada a
   `Number.isInteger` en `01225a3`, aceptando `1e21`. Restaurado el
   `Number.isSafeInteger` original (revert exacto de la relajación) y el
   test de validación cubre ahora `PROXY_MAX_CONCURRENT: '1e21'` → rechazo
   (también verificado directo: `2^53+1` rechazado, `3` y el default `2`
   intactos).
3. **Aserción UUID exacta** (`tests/provider-headers.test.ts`):
   `x-opencode-session` se valida contra la forma completa
   `/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/` en
   vez del laxo `^[0-9a-f-]{36}$`.
4. **Espejo iOS** (mecánico, sin compilación posible en Linux):
   `apps/ios/Core/ApiProxyEngine.swift` deja de exigir el closure `modelFor`
   (y su caso `Failure.missingModel`), deja de enviar el header
   `x-palique-purpose` y deja de enviar el campo `model` — el servidor
   resuelve proveedor, modelo y credenciales por llamada desde la
   configuración LLM del panel, espejando el motor `api` de Android tras
   `831cd87`; `apps/ios/Core/HostedProxyEngine.swift` corrige en comentario
   la referencia caducada a `PROXY_PROVIDER_URL` (solo comentario).

Verificación de esta ronda: `cd services/api && npx tsc --noEmit` verde;
`npx tsx --test tests/token-proxy.test.ts tests/provider-headers.test.ts`
verde (15 tests: 4 pass, 11 SKIP por falta de `TEST_DATABASE_URL`, 0 fail —
los casos de integración se omiten en local, como es de esperar). No se
re-ejecutan `make test-api` ni las suites Android: esta ronda solo toca
`token-proxy.ts` y dos ficheros de test, y ambas suites siguen verdes tal
como quedaron registradas en `7020182`.

**Seguimiento explícito**: el espejo iOS se actualizó solo en código y
sigue **sin verificar** — pendiente de una pasada en macOS
(`swift test --package-path apps/ios`) antes de dar el change por cerrado
en plataforma Apple.
