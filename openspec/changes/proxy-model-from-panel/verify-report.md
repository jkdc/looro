# Verify report: proxy-model-from-panel

Fecha: 2026-11-03 · Rama: `develop` · Alcance del change: `9ba7403..40e98ed`
(7 commits de servidor/Android/documentación).

## Veredicto: **BLOCKED**

Verde en typecheck, contratos multiplataforma y suite Android. La suite de
integración del servidor (`make test-api`) está **en rojo**: 7 fallos reales
(6 del mismo defecto + 1 de otro) y una prueba posterior quedó colgada, por lo
que la ejecución se abortó antes del final. El E2E de laboratorio no se
ejecutó (no hay dispositivo). Detalle completo abajo; ningún código se tocó
en esta verificación.

## Qué se ejecutó y pasó

| Verificación | Resultado |
|---|---|
| `cd services/api && npx tsc --noEmit` | **Verde** (exit 0, sin errores) |
| `make check` (exporters `--check`, cross-platform, db schema) | **Verde**: 150 tests Python OK en 2,4 s; schema SQLite sincronizado (152 statements, `user_version 7`) |
| `cd apps/android && ./gradlew :app:testDebugUnitTest :app:lintDebug` | **Verde**: `BUILD SUCCESSFUL in 4m 21s`; **865 tests unitarios de `:app`, 0 fallidos, 0 omitidos**; lint 0 errores (75 warnings y 22 hints preexistentes) |

Notas de ejecución: `make test-api` y la build Android se lanzaron en
paralelo con salida a log y sondeo (`nohup`); la suite de integración corre
sobre el stack efímero `looro-smoke` (PG 17 aislado), que se destruyó al
terminar. El stack de laboratorio del usuario (`api-api-1`, API publicada en
0.0.0.0:8080) quedó en marcha y **no** se tocó ni se reconstruyó.

## Qué falló: `make test-api` (primera ejecución de la suite completa)

Las tareas 3–5 aplazaron explícitamente `make test-api` a esta tarea, así que
esta es la **primera pasada de la suite completa** del change: los dos
defectos siguientes fueron introducidos por el change y no se habían
manifestado en ninguna verificación previa.

Recuento antes del aborto: **832 tests OK / 7 fallidos** (las líneas finales
`# tests/# pass/# fail` de TAP no llegaron a imprimirse porque la ejecución
se abortó colgada; recuento derivado de las líneas `ok`/`not ok` del log).

### Defecto 1 — stubs de main-wiring sin `resolveLlmConfig` (6 fallos, bloqueante)

`main-wiring.test.js`: los 6 tests (581–586: "main composition starts
configured workers…", "…closes initialized resources…", "…stops metrics
refresh…", "…registers signal handlers…") fallan igual al lanzar la sonda:

```
SyntaxError: The requested module './llm-config.js' does not provide an
export named 'resolveLlmConfig'
    at file:///app/dist/src/main.js:15
```

Causa raíz confirmada (no es un artefacto de build: se reprodujo
determinísticamente en un contenedor efímero de la imagen actual, y el
import dinámico directo de `dist/src/llm-config.js` resuelve la exportación
correctamente):

- `src/main.ts:15` importa `resolveLlmConfig` de `./llm-config.js` (añadido
  por el commit `01225a3` de este change).
- `tests/support/main-wiring-loader.mjs` intercepta **todas** las importaciones
  relativas del entry principal y las redirige a un único módulo,
  `tests/support/main-wiring-stubs.mjs`, cuyas exportaciones deben cubrir cada
  import nombrado de `main.ts`.
- `main-wiring-stubs.mjs` **no exporta `resolveLlmConfig`** → la
  instanciación ESM del hijo revienta y los 6 tests fallan por la misma razón.

Dirección de corrección (no aplicada): añadir a los stubs una exportación
`resolveLlmConfig` coherente con lo que la sonda espera registrar (la sonda
debe seguir sin tocar BD: los tests usan `DATABASE_URL=postgres://probe.invalid/test`).

### Defecto 2 — política de petición vs. expectativa de saldo a cero (1 fallo, bloqueante)

`token-proxy.test.js`, test 829 "relay meters provider usage at the active
rate version and debits the wallet":

```
The input did not match the regular expression /token_balance_exhausted/.
Input: 'Error: request_policy_violation'
  rejectField (dist/src/proxy-policy.js:17)
  canonicalProxyRequest (dist/src/proxy-policy.js:32)
  TokenProxy.relay (dist/src/token-proxy.js:87)
```

La aserción en `tests/token-proxy.test.ts:114`:

```ts
await assert.rejects(proxy.relay(account, { messages: [] }), /token_balance_exhausted/);
```

Con la nueva política de petición, `canonicalProxyRequest` rechaza
`messages` vacío con `400 request_policy_violation` **antes** de cualquier
trabajo de wallet/admisión, así que el test ya no ve
`token_balance_exhausted`. Es un desacuerdo test/código introducido por el
change; la corrección es decidir cuál de los dos lados manda (cuerpo del test
con `messages` no vacío, u orden saldo-antes-de-política en `relay`) y
versionar la prueba que falle sin el ajuste. No se decidió ni aplicó nada.

A raíz de este fallo, el propio fichero `token-proxy.test.js` quedó **colgado
más de 12 minutos** (el test abortado deja su proveedor stub sin cerrar;
runner secuencial `--test-concurrency=1`) y la ejecución se detuvo. Como
consecuencia no llegaron a ejecutarse: los tests restantes de
`token-proxy.test.js` y los 5 ficheros posteriores del orden alfabético
(`tokens-admin.test.js`, `trial.test.js`, `usage-http.test.js`,
`usage.test.js`, `welcome-funding.test.js`). El veredicto de la suite no
puede ser verde hasta reejecutarla completa tras corregir.

Descartado: imagen Docker desfasada. La imagen del stack efímero se construyó
(22:21) antes del commit `893b939` (22:44, solo migraciones), pero ambos
defectos se reprodujeron/verificaron contra fuente actual (`tsc` verde,
`main.ts`/`llm-config.ts` coherentes en host y contenedor, reproducción en
contenedor efímero limpio), y `893b939` no toca `src/` ni `tests/`.

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

1. Corregir el defecto 1 (exportar `resolveLlmConfig` en
   `tests/support/main-wiring-stubs.mjs`) con prueba que falle sin el fix.
2. Resolver el defecto 2 (orden política/saldo o cuerpo del test en
   `tests/token-proxy.test.ts:114`) igualmente test-first.
3. Reejecutar `make test-api` completo hasta verde con recuento final, y
   confirmar que `token-proxy.test.js` ya no cuelga.
4. Ejecutar el E2E de laboratorio (comandos de arriba) con dispositivo y
   adjuntar evidencia.
