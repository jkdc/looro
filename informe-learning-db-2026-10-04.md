# Informe: learning-db — migración completa A-H — 2026-10-04

Ejecución SDD de la migración del almacenamiento de aprendizaje a SQLite
canónico (`shared/db/schema.sql` → Room/GRDB), en 8 fases secuenciales con
revisión y fix loop por fase. Rama `feature/learning-platform`; **29 commits**
(A-H); HEAD al cierre: `4e1c750`. El JSON deja de ser el runtime store para
siempre: queda solo como ruta de IMPORT legacy y como red de seguridad
(`jsonMode`).

## Fase A — Fundaciones (e9f9d7f, 48c3dbf)

`shared/db/schema.sql` canónico (v1: 10 tablas, CHECK `preferences.id = 1`,
FK `fragments.session_id` CASCADE) como ÚNICA fuente de verdad;
`scripts/check_db_schema.py` que fija inventario, constraints y (en v2) la
tabla virtual, y falla alto ante cualquier delta de Room/GRDB; 4 fixtures de
migración con casos borde (languageID=null legacy, sesión sin terminar,
scenario review) con conteos esperados. Fix round: constraints pineadas y
fixtures merge-safe.

## Fase B — Persistencia Android (06b0c9d, 4f5ef7a, 15faad0, 7e75bf9, 811d16b, 23ab80e)

`export_db_schema.py` genera `SchemaData.kt` (y espejo Swift) desde el SQL
canónico; capa de acceso `SqlDatabase` con contratos de transacción/update/
null-args; `ArchiveMapper` (filas↔structs, rowid = orden de lista);
`LearningDbDao` con tests de roundtrip/merge/caps/pods contra los fixtures;
`LearningDbWriter` con merge en DB a paridad del codec y APIs de lectura;
`ArchiveMerger` acotado a restore-only con KDoc corregido.

## Fase C — Cutover Android (6ea2da4, 2bb77be, 01c730f, b3d4d7e)

`LearningDbStore`: migración one-way de `learning.json` con conteos
verificados, marcador `.migrated` y hash anti-re-migración, reintentos
recuperables; rewire del AppViewModel a escrituras por-mutación en la DB
(recuperación, outbox, tickets). E2E en emulador verificado con capturas
commiteadas (b3d4d7e). Fix round: reintentos y contratos fail-safe honestos.

## Fase D — Backup Android (98d0b0b, 09252b5, 71b7159, eb8045a)

`BackupManager.exportTo`/`readValidatedBackup`/`installDatabase`: backup del
fichero SQLite completo con checkpoint WAL, verificación byte a byte e
instalación stage-then-swap; `deleteLearningDataForLanguage` en UNA
transacción; UI de backup en Settings (contrato SAF `.db`) con strings
EN/ES; el import JSON legacy se conserva. E2E de backup/restauración en
emulador con capturas (eb8045a). Fix round: ventana de restore sin pérdidas
y guardias de borrado ruidosos.

## Fase E — iOS GRDB (4b43eae, 438b360, eae758b)

Espejo de B+C declaración por declaración: `LearningDatabase` (GRDB), DAO,
mapper, writer y `LearningDbStore` con migración SwiftData→SQLite y cutover
del ConversationCoordinator. Fix round: boxing de inserts, seguridad ante
conexión cerrada y unwrap de transacciones. **Nunca compilado: gate macOS.**

## Fase F — Backup iOS (065d2a2, 7b5f636)

Espejo de D: `BackupManager` Swift, `DatabaseBackupDocument`, sección de
backup `.db` en Settings y borrado por idioma. Fix round: quiesce de writers
diferidos antes del restore (sincroniza con el checkpoint WAL de Android).
**Gate macOS.**

## Fase G — Búsqueda FTS + archive_health (a80fd3d, 1381af6, 1c008e2, 2844532, b307d29)

Schema v2: `fragments_fts` external-content + triggers de sincronización +
rebuild indexando todo lo existente; evento de uso `archive_health` en el
catálogo del server con emisión honesta (cadencia 24 h) desde el cutover;
UI de búsqueda de conversaciones en Android e iOS (lista con debounce,
snippet → transcripción). E2E de búsqueda en emulador con capturas
(b307d29).

### La historia FTS5→FTS4 (el catch de portabilidad del review)

La primera ronda de G pineó **FTS5**. El review la RECHAZÓ con un defecto
Critical de portabilidad: **el SQLite de framework de Android solo habilita
el módulo FTS3/FTS4 — AOSP no trae FTS5.** La suite JVM pasaba igual porque
sqlite-jdbc incluye FTS5 y el checker corre sobre el SQLite de CPython: ambos
ciegos ante el dispositivo. `1c008e2` portó el índice a FTS4 external content
con los cuatro triggers del patrón documentado (BEFORE DELETE/UPDATE dan de
baja mientras la fila still readable; AFTER INSERT/UPDATE indexan) y sondeó
el SQLite del dispositivo empíricamente. En H se barrió el último wording
"FTS5" residual de los docstrings del checker y del comentario del test (los
pins ya eran correctos).

## Fase H — Cierre (47c2884, cf69ff2; fix ronda 4e1c750)

- **Retiro del export JSON en ambas plataformas**: fila "Export learning
  backup" fuera de Settings en Android (con su launcher SAF
  `CreateDocument("application/json")` y `writeBoundedUtf8`) y botón +
  `BackupDocument`/`fileExporter` fuera en iOS. El **import JSON legacy se
  conserva indefinidamente** (ruta de restore vía ArchiveMerger).
  `exportData()` se retira de Android (su único test construye el archivo
  directamente); en iOS se conserva porque el harness de verificación de
  idiomas aún lo llama.
- **Limpieza**: wording FTS5→FTS4 en `check_db_schema.py` y su test.
- **Cierre honesto**: `tasks.md` con las 8 fases [x] + `verify-report.md`
  nuevo siguiendo la convención del repo.

## Batería final (2026-10-04, tras los cambios de H)

- Android: `./gradlew :app:testDebugUnitTest` — **632 tests, 0 fallos,
  0 saltados** (82 informes de suite), BUILD SUCCESSFUL.
- Python: `unittest discover -s scripts/tests` — **132 tests OK**;
  `check_db_schema.py` — sin issues (user_version 2, 10 tablas, 4 índices,
  1 tabla virtual, 4 triggers, 1 FK, 4 fixtures); `export_db_schema.py
  --check` — generado al día (22 statements); `check_cross_platform.py` —
  exit 0.
- Server: `npx tsc --noEmit` limpio; `npm test` contra PG 17 local —
  **419 tests, 414 pass, 5 fallos = los 5 ambientales conocidos** (accounts,
  auth, apple-revocation, service-account OAuth, usage-timeseries).
- Strings: paridad EN/ES **465 = 465** simétrica — estado final (465 → 467
  tras las 2 claves de búsqueda de G por locale → 465 tras retirar en H 2
  claves huérfanas por locale).
- E2E de sesión (fases C/D/G, capturas commiteadas): cutover, backup/
  restauración y búsqueda verificados en emulador Android.

## Pendientes (registrados, no bloqueantes de código)

- **Gate macOS**: TODO el Swift de este cambio (capa GRDB, backup, búsqueda,
  retiro del export) nunca ha compilado — `swift test`/xcodebuild en Mac.
- **iOS e2e en dispositivo**: migración, backup/restore `.db`, búsqueda.
- **`archive_health` positivo en dispositivo**: la emisión honesta requiere
  build de origen gestionado.
- **Follow-ups conscientes**: drenaje durable del `usage_outbox` (feature
  futura); estrechamiento del PK de `completions` (schema v3, someday);
  fallback `jsonMode` CONSERVADO deliberadamente como red de seguridad en
  producción (el plan evoluciona: su retiro se decidirá con datos);
  acoplamiento position-diff de ProgressSync en `mergeRemote`; decisión
  abierta sobre el remove cross-language del notebook.
- **Fix ronda 1 (review del cierre)**: corregida la atribución del break
  androidTest — el origen real es `b391e8b` ("refactor(android): brand-free
  structure"), que dejó `AccountSheetTest` referenciando las constantes
  `DELETION_SUPPORT_*` sin import (08cb1ad solo cambió dos strings de
  pantalla; el mensaje de b391e8b afirmaba falsamente "androidTest compile
  green"). El break queda ARREGLADO en la misma ronda: los tres imports
  añadidos y `:app:compileUiTestAndroidTestSources` compila verde. También
  corregido el wording de shadow tables del checker (las reales, verificadas
  contra un esquema vivo: `fragments_fts_segments/_segdir/_docsize/_stat`).
