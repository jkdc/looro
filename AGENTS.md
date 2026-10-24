# AGENTS.md — Palique (Looro)

Guía para agentes de IA que trabajan en este repositorio. Léela entera antes
de tocar código.

## Qué es este proyecto

Palique ayuda a practicar idiomas mediante conversación. Es un monorepo con
tres aplicaciones principales:

| Directorio | Qué es | Stack |
|---|---|---|
| `apps/android/` | App Android nativa | Kotlin + Jetpack Compose, minSdk 26 |
| `apps/ios/` | App iOS nativa | SwiftUI, iOS 26.1+ (requiere macOS/Xcode) |
| `services/api/` | Backend (cuentas, billing, level-tests) | Node.js 22+, TypeScript, Fastify, PostgreSQL 17 |

Contenido compartido entre plataformas en `shared/` (JSON), scripts de
generación en `scripts/` (Python), especificaciones de cambios en
`openspec/changes/`.

## Comandos esenciales

### Servidor (todo por Docker, vía Make)

```bash
make up            # levanta todo: BD, migraciones, API, panel admin, Caddy
make down          # para el stack (conserva volúmenes)
make restart       # reinicia api + caddy
make logs          # sigue los logs de la API
make status        # estado de los contenedores
make health        # curl a /healthz y /readyz
make obs-check     # valida configs de observabilidad (compose, promtool, amtool)
make migrate       # aplica migraciones pendientes
make psql          # consola psql contra la BD local

# Pruebas de la API (stack aislado, sin tocar tus datos):
make test-api      # suite de integración completa en stack efímero
make smoke-up      # solo PostgreSQL + API efímera
make smoke-down    # para el stack de humo y borra volúmenes

# Contratos multiplataforma (lo que corre CI):
make check         # exporters --check, cross-platform, db schema

# Panel admin:
make admin-add USER=<nombre> EMAIL=<email>   # crea operador
make admin-cli ARGS="list"                  # CLI de admins
make admin-open                             # abre el panel en el navegador
```

**Dentro del contenedor** (si necesitas los CLIs directamente):
```bash
make admin-cli ARGS="..."        # admins-admin
docker compose -f services/api/compose.yaml exec api node dist/src/leveltests-generate.js
```

**Type-check local** (fuera de Docker, rápido):
```bash
cd services/api && npx tsc --noEmit
```

### Android (nativo; SDK 36 + Java 17 en el host)

```bash
make android-build     # tests unitarios + lint + APK debug
make android-install   # instala el APK en el dispositivo/emulador conectado
make android-run       # lanza la app
make android-logcat    # logs de la app en el dispositivo
```

El motor de conversación se fija al compilar con `-Papp.conversationEngine=glm`
(motores válidos: openai, deepseek, glm, glm-4.5). Si usas `make` directamente,
compila con el motor por defecto; para un motor específico:
```bash
cd apps/android && ./gradlew :app:assembleDebug -Papp.conversationEngine=glm
```

Claves de API (NO commitear — ya está en .gitignore):
```bash
# apps/android/api-keys.properties → ZHIPUAI_API_KEY=...
adb push apps/android/api-keys.properties \
  /storage/emulated/0/Android/data/com.jkdc.palique.android/files/api_keys.properties
```

### iOS (nativo; Xcode 26+ en macOS)

```bash
make ios-build    # compila para simulador sin firmar
make ios-test     # tests core + UI en el simulador "iPhone 17"
make ios-device   # compila e instala en el iPhone conectado
# El target App no compila en Linux; solo el paquete Core con:
swift test --package-path apps/ios
```

### Scripts de contenido (`scripts/`)

```bash
python3 scripts/export_courses.py --check      # valida + genera Kotlin y Swift
python3 scripts/export_listening.py --check
python3 scripts/export_placement.py --check    # bancos de placement
python3 scripts/export_leveltests.py --check   # pools de level-tests
python3 scripts/check_cross_platform.py        # paridad iOS/Android
python3 scripts/check_db_schema.py             # schema SQLite de la app
python3 scripts/build_leveltests.py            # cosecha pools desde contenido
```

**Regla**: los ficheros `*Data.kt` y `*Data.swift` son **generados** — nunca
editarlos a mano. Se editan los JSON en `shared/` y se regeneran con el
exporter correspondiente. `--check` detecta drift en CI.

## Estructura del contenido

```
shared/
  courses/        # temarios por idioma (en/es/fr/de/it/pt: foundations + tracks)
  listening/      # textos de comprensión con preguntas
  reading/        # lecturas
  skills/         # catálogo de skills (grammar:, vocab:, listening:, ...)
  assessments/    # checkpoints por nivel
  placement/      # bancos del test de nivel (en, es)
  leveltests/     # pools de verificación semanal + exam-models.json
  db/             # schema SQLite de la app (user_version 2)
  contracts/      # contratos de API
```

## Arquitectura de level-tests (3 capas)

1. **Determinista** (bundled): pools cosechados de contenido autorizado,
   exportados a `LevelTestsData.kt` + `LevelTestsData.swift`. Composición
   semanal con sha256 estable — idéntica en app y server (golden test).
   Endpoint: `GET /v1/level-tests/current?language=&level=`

2. **LLM generada** (server): `npm run leveltests-generate` llama a un
   proveedor LLM (GLM/OpenAI/opencode), valida por 3 puertas (validador
   determinista → juez LLM → revisión humana), compone 3 variantes.
   Endpoint: `GET /v1/level-tests/generated?language=&level=` (404 si no hay
   datos) y `GET /v1/level-tests/hybrid` (fallback a determinista).

3. **Pantallas** (app): `LevelTestScreen` en Compose, entrada desde la barra
   CEFR de la pestaña Aprender. Grading siempre en dispositivo.

## Política de pruebas (obligatoria)

**Una suite verde no demuestra que el requisito esté cubierto.** Demuestra que
lo que ya estaba probado sigue funcionando. Ningún cambio se considera
terminado por ese hecho.

Reglas no negociables:

1. **Test-first en cada bugfix y feature.** Escribe primero la prueba que
   reproduce el fallo, córrela y compruébalo: debe fallar por la razón correcta
   (comportamiento ausente, no un error tipográfico). Si pasa antes del cambio,
   esa prueba no prueba el cambio.
2. **Cobertura por flujo, no por línea.** Cada cambio cubre, cuando aplique:
   camino feliz, entrada vacía o inválida, error de red y error HTTP, respuesta
   tardía o fuera de orden, cancelación o navegación durante una petición,
   reintento, y estado vacío. Si hay UI, cubre también foco, teclado y el aviso
   persistente al usuario.
3. **Regresión obligatoria por hallazgo.** Todo defecto reportado en una
   revisión añade una prueba que falla sin la corrección y pasa con ella. El
   nombre de la prueba debe describir el defecto original.
4. **Extrae y prueba la lógica pura.** La lógica de estado o de contrato que
   pueda aislarse de `admin-ui/app.js` vive en `admin-ui/features/*.js` y se
   cubre con pruebas Node en `services/api/tests/*.test.ts`. El E2E no sustituye
   esta cobertura: un fallo de normalización debe fallar rápido, sin Docker.
5. **E2E para los flujos reales del panel.** Navegación, respuestas tardías,
   errores persistentes y reintentos se añaden a
   `services/api/tests/browser/admin-e2e.mjs`. Una prueba unitaria no demuestra
   que la vista montada se comporte bien.
6. **No declares verde sin ejecutar.** Reporta qué se ejecutó y qué no. Si la
   suite de API o el E2E no se pudieron correr, dilo explícitamente y no cierres
   el change como verificado.
7. **Pruebas deterministas.** No dependas del reloj, de red externa ni de
   proveedores reales: inyecta o mockea el límite del sistema y usa fixtures
   estables.

## Convenciones del repositorio

- **Idioma**: código y comentarios en inglés; documentación de producto e
  informes en español. Strings de la app en `values/strings.xml` (EN) y
  `values-es/strings.xml` (ES) — siempre ambas.
- **SDD (Spec-Driven Development)**: los cambios significativos se documentan
  en `openspec/changes/<nombre>/` con `proposal.md`, `design.md`, `tasks.md`
  y `specs/<capability>/spec.md` antes o durante la implementación.
- **Informes de análisis** en la raíz: `informe-*.md` con fecha en el nombre.
- **Observabilidad**: métricas de dominio con prefijo `palique_`, labels de
  cardinalidad cerrada (plantilla de ruta, nunca URL con IDs ni datos
  personales) y nunca PII en telemetría. Las rutas nuevas ya emiten RED
  automáticamente; los jobs asíncronos nuevos sí necesitan contadores de
  dominio. Contrato completo en `services/api/docs/observability.md`.
- **Never edit generated files** (ver arriba).
- **Kotlin**: los comentarios KDoc no aceptan `/*` anidado dentro de `/**`
  (los comentarios Kotlin se anidan — evita `*.json` dentro de KDoc).
- **Migraciones SQL**: numeradas secuencialmente (`NNN_nombre.sql`), una vez
  aplicadas nunca se editan — se añade una nueva.
- **PostgreSQL**: tablas financieras/auditables usan triggers de inmutabilidad
  (`immutable_ledger()`). Dinero siempre en BigInt nanoUSD.
- **testTags**: las pantallas Compose llevan `Modifier.testTag("...")` para
  los androidTest — mantenerlos al refactorizar UI.

## Entorno de desarrollo

- **Linux**: Android + servidor funcionan completo. iOS no compila (solo se
  puede editar el código, no verificarlo).
- **macOS**: todo (requerido para iOS).
- **Docker**: necesario para todo el backend (stack completo vía `make up`;
  stack efímero para tests vía `make test-api`).
- **Emulador Android**: `emulator-5554` está disponible si está arrancado;
  los dispositivos físicos aparecen con `adb devices`.
- **API key de GLM**: `apps/android/api-keys.properties` (gitignored) —
  funciona tanto para el motor de conversación como para la generación
  de tests (plan de coding de z.ai, endpoint
  `https://api.z.ai/api/coding/paas/v4/`).

## CI (`.github/workflows/`)

| Workflow | Qué valida |
|---|---|
| `checks.yml` | Swift Core (macOS) + server con PG 17 y Node 22 |
| `android.yml` | Tests + lint + AAB + emulador con connectedUiTest |
| `contracts.yml` | Checkers Python (exporters `--check`, cross-platform, db schema) |
| `secrets.yml` | gitleaks sobre toda la historia |

Todas las actions están pineadas por SHA.

## Seguridad

- **Cero secretos en git**: `.env`, `api-keys.properties`, xcconfigs están
  gitignored. Gitleaks escanea toda la historia.
- Las API keys del panel de LLM se cifran con AES-256-GCM en BD (clave
  maestra en `LEVELTEST_LLM_MASTER_KEY`).
- No loguear request bodies, tokens, ni claves (regla existente del server).
- Los endpoints de level-tests son proxy-exempt (contenido público sin datos
  de usuario); el resto de rutas de cliente requieren trusted-proxy headers.

## Errores comunes a evitar

1. **Editar `*Data.kt`/`*Data.swift` a mano** — regenerar con el exporter.
2. **Ejecutar tests del server con Node 18** — usar Node 20+ (nvm).
3. **Olvidar la variante ES de un string** nuevo en Android.
4. **Métodos JVM > 64KB**: los ficheros generados grandes deben trocearse en
   funciones (un `listOf()` gigante en un solo método revienta el límite).
5. **`localeCompare` en TypeScript** para orden determinista — usar
   comparación por codepoint (`a < b ? -1 : a > b ? 1 : 0`); ICU varía.
6. **Timeout de undici (300s)**: las llamadas LLM largas necesitan el
   dispatcher custom (`llmAgent` en `llm-config.ts`).
7. **Comentarios Kotlin anidados**: `/** ... /* ... */ ... */` rompe — no
   usar globs con `*` dentro de KDoc.
8. **Confundir suite verde con requisito cubierto**: si el flujo nuevo
   (error, respuesta tardía, navegación, reintento) no tiene una prueba que
   falle sin el cambio, el requisito no está verificado.

## Flujo de trabajo recomendado para un agente

### Requisitos para cambios asistidos por IA

- **Superpowers es el flujo obligatorio** para planificar, especificar, implementar y verificar cambios. No usar Gentle AI ni sus flujos de trabajo.
- Implementar y hacer los commits de trabajo en la **rama activa**. No crear worktrees ni cambiar de rama durante una tarea salvo petición explícita.
- Para cambios significativos, mantener SDD en `openspec/changes/` y trabajar con pruebas-first cuando exista un test determinista aplicable.
- No preguntar cómo encadenar PR ni proponer una estrategia de ramas como condición para continuar. No hacer push, abrir PR ni mergear salvo petición explícita.
- Usar commits convencionales, pequeños y verificables; nunca añadir atribución de IA o `Co-Authored-By`.
- En E2E, guardar capturas en `E2E_EVIDENCE_DIR` dentro del directorio ignorado del SDD; no sobrescribir las evidencias versionadas salvo que la actualización sea intencional.

1. **Lee primero**: `README.md`, el `proposal.md` del change relevante si
   existe, y los ficheros vecinos del que vas a tocar.
2. **Type-check temprano**: `npx tsc --noEmit` (server) o
   `./gradlew :app:compileDebugKotlin` (Android) antes de escribir tests.
3. **Test-first siempre**: primero la prueba que falla por el motivo correcto,
   después la corrección mínima. Los motores puros (PlacementEngine,
   LevelTestEngine, ExerciseEngine) y la lógica de UI extraída son deterministas
   y testeables en JVM/Node.
4. **Regenera, no edites**: si tocas `shared/*.json`, ejecuta el exporter.
5. **Verifica todo lo que puedas**: suite del server con PG efímero, tests
   Android, `--check` de exporters. Reporta qué no pudiste verificar.
6. **Documenta el cambio**: si es significativo, crea/mantén el change en
   `openspec/changes/` y actualiza el `verify-report.md`.
7. **Commits**: mensajes descriptivos en inglés, presente tense, formato
   `tipo(ámbito): descripción` (ej: `feat(level-tests): chunked generation`).

## Mapa rápido de ficheros clave

| Fichero | Qué hace |
|---|---|
| `services/api/src/app.ts` | Todas las rutas HTTP del backend |
| `services/api/src/observability.ts` | Init/shutdown OTel y gauges del pool de BD |
| `services/api/src/metrics.ts` | Instrumentos de dominio `palique_*` |
| `services/api/src/log.ts` | Logger JSON con campos escalares (allow-list) |
| `services/api/telemetry/` | Configs del stack (collector, Prometheus, alerts, Tempo, Grafana) |
| `services/api/docs/observability.md` | Contrato de observabilidad, alertas y runbooks |
| `services/api/src/level-tests.ts` | Compositor semanal determinista |
| `services/api/src/level-tests-llm.ts` | Pipeline de generación LLM (4 secciones) |
| `services/api/src/level-tests-validate.ts` | Puerta 1: validador determinista |
| `services/api/src/llm-config.ts` | Proveedores LLM + cifrado AES-GCM |
| `apps/android/.../core/LevelTests.kt` | Motor de level-tests (Kotlin) |
| `apps/android/.../core/Placement.kt` | Motor de placement (Kotlin) |
| `apps/android/.../ui/LevelTestScreen.kt` | Pantalla del examen |
| `apps/android/.../ui/LearnScreen.kt` | Pestaña Aprender (barra CEFR) |
| `apps/android/.../core/ExerciseEngine.kt` | Corrección determinista de ítems |
| `shared/leveltests/exam-models.json` | Plantillas de examen por nivel |
| `openspec/changes/` | Especificaciones SDD de todos los cambios |
