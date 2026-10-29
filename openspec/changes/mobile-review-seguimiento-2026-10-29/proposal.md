# Proposal: Mobile review follow-up round 3 (N01–N06)

**Fecha:** 29 de octubre de 2026.
**Autoridad:** `informe-revision-seguimiento-app-movil-2026-10-29.md` (secciones 3 y 6).
**Alcance:** resolver los seis hallazgos residuales (N01–N06) sin cambiar la
política local-first ni tocar la API, preservando las protecciones introducidas
en la ronda S01–S08.

## Por qué

1. **N01 (P1, Android):** la atomicidad nueva protege los fallos futuros, pero
   una migración parcial creada por la versión ANTIGUA (filas sin hash y sin
   claim) era reclamada por la siguiente cuenta: se borraban sus datos
   legítimos y se le atribuía el respaldo ya usado por otro. Reproducido por
   el informe con sondas.
2. **N02 (P1, iOS):** el store iOS mantenía importación y confirmación
   separadas, limpieza por cuenta y conteos globales, sin claim pendiente: la
   misma clase de fallo que S01/N01.
3. **N03 (P2, Android):** durante la carga de B, `remoteProgressForLanguage`
   consultaba el propietario aún activo en BD (A) aunque la proyección ya
   estuviera vaciada para B.
4. **N04 (P2, iOS):** la adopción de B no invalidaba la operación de A antes
   de intentar `begin(B)`; el rechazo de admisión perdía la solicitud y el
   handler tardío solo cancelaba, sin programar el sustituto.
5. **N05 (P2, Android):** un mute/unmute con el buffer del saludo lleno
   cambiaba el epoch de captura sin invalidar `openingFrames`/capacidad:
   la frase posterior al unmute se perdía (reproducido por sonda).
6. **N06 (P2, iOS):** tras un borrado remoto exitoso, un fallo de
   `keychain.remove()` dejaba la credencial cargable por un consumidor
   independiente o el siguiente proceso.

## Qué cambia

- **N01 (Android):** `migrate()` clasifica la procedencia del contenido
  preexistente cuando la puerta está abierta sin claim
  (`legacyResidueScope`): FRESH (sin filas) o OWN (todo el contenido es del
  propietario que abre) pueden reclamar; AMBIGUOUS (filas de otro
  propietario o sin atribuir, incluida cualquier fila de mastery no
  atribuida) RECHAZA la migración → la apertura queda en cuarentena con la
  BD y el respaldo intactos y el mensaje nombra la sonda que falló. El claim
  y la transacción atómica de la ronda anterior se conservan intactos.
- **N02 (iOS):** `ArchiveMapper.importInTransaction` + migración en UNA
  transacción (limpieza + importación + verificación por propietario +
  metadatos + retirada del claim), claim pendiente
  (`migration_pending_owner`/`..._hash`) con salto de claim ajeno y la misma
  cautela histórica de N01; `verifyCounts` acotado al propietario (con bind
  explícito para el patrón LIKE de mastery) y `markMigrated` solo tras el
  commit. Tests file-backed equivalentes añadidos a `Tests/LearningDbStoreTests.swift`
  (el test legacy de conteo se re-fija al rollback total). Ejecución XCTest
  pendiente de macOS (limitación declarada, no bloqueo).
- **N03 (Android):** `ProgressProjection.identityConsistent(loaded,
  requested, consulted)` — helper puro con test — y `remoteProgressForLanguage`
  solo consulta rollups remotos cuando identidad solicitada, identidad
  cargada y propietario activo en BD coinciden; si no, proyección vacía/en
  carga.
- **N04 (iOS):** `activateAccountAndSync` es la transición única
  (adoptar → invalidar → sincronizar) y un `begin()` rechazado registra
  `pendingSyncSubstituteAccountID`, reprogrameado por
  `schedulePendingSyncSubstitute()` al liberarse la operación activa
  (auto-cercado: `syncProgress` relee la sesión y `begin` rechaza admisiones
  obsoletas). `invalidateProgressSync` limpia la clave del cerco.
- **N05 (Android):** el cambio de epoch de captura invalida conjuntamente
  `openingFrames`, `openingBufferedSamples` y `openingCaptureFull` además del
  buffer ordinario; la intención de Finalizar y el límite de memoria se
  conservan y no se reproduce audio anterior al mute.
- **N06 (iOS):** tras el DELETE remoto exitoso y ANTES de anunciar la
  transición, `keychain.markCredentialRejected()` (el mismo mecanismo
  fail-closed del 401); el fallo de `remove()` mantiene la sesión vacía, el
  marcador activo y el aviso de limpieza pendiente. El marcador solo se
  limpia con una nueva autenticación guardada con éxito (F13, sin cambios).

## Limitaciones declaradas

- Swift/Xcode no disponibles en Linux: N02/N04/N06 quedan implementados y
  revisados por bytes; su ejecución (swift test / XCTest) queda diferida a
  macOS y registrada como pendiente.
- El test de reinicio file-backed de N02 usa la reapertura en memoria del
  mismo handler de tests; la persistencia real de fichero ya está cubierta
  en Android y por el esquema compartido.

## Verificación mínima (Linux)

1. Regresiones nuevas en rojo por el motivo correcto (N01 y N05) y test del
   helper N03; suites enfocadas en verde tras cada corrección.
2. Suite completa de app + engine-local en verde; `make check`; lint y APK.
3. Recorrido del emulador repetido contra la API en Docker con logcat limpio.
