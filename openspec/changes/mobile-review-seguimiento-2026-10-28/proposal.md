# Proposal: Mobile review follow-up round 2 (S01–S08)

**Fecha:** 28 de octubre de 2026.
**Autoridad:** `informe-revision-seguimiento-app-movil-2026-10-28.md` (secciones 3 y 7).
**Alcance:** corregir los siete defectos concretos (S01–S07) y cercar el riesgo de
orden (S08) identificados por la revisión del seguimiento, manteniendo los
contratos existentes (v2 de progreso, cuarentena del respaldo, cercado por
identidad/generación) y sin tocar la API.

## Por qué

La revisión del 28/10 demostró (sondas reproducibles) que:

1. **S01** una migración fallida entre la importación y el marcador deja filas
   sin atribución que bloquean el reintento de CUALQUIER identidad (verificación
   de tablas completas) y pueden reasignar el respaldo al último usuario.
2. **S02** el decoder de descarga iOS valida el orden con claves `kind|itemID|
   language` concatenadas: una página válida `(a, a-1)` se rechaza y el
   bootstrap muere.
3. **S03** saturar el buffer del saludo fija el bloqueo de captura del turno
   (`captureLimitNotified`) y descarta TODO el prefijo retenido al drenarlo.
4. **S04** el recorte de STT a 18 s al finalizar un turno de 19,2 s pierde el
   final sin aviso visible (y el aviso existente promete «se conserva todo»).
5. **S05** la regresión iOS de 1.001 descargas persiste filas con propietario
   vacío que el writer rechaza: la prueba nunca llegaría a probar el reinicio.
6. **S06** una limpieza de audio fallida en la conversación no registra
   recuperación global: pronunciación encuentra el token ocupado sin closure
   que reintentar.
7. **S07** `LearnScreen` congela `progressStats()/completedLessonIDs()` en un
   `remember` sin claves: un bootstrap diferido no refresca las marcas.
8. **S08 (riesgo)** una notificación tardía de cambio de sesión puede cancelar
   el bootstrap que la adopción de la vista acaba de crear.

## Qué cambia

- **S01 (Android):** la migración pasa a una única transacción (limpieza +
  importación + tickets + verificación + `migrated_at` + `migration_hash` +
  retirada del marcador) con `ArchiveMapper.importInTransaction`; la
  verificación de conteos se acota al propietario importador; un marcador
  pendiente (`migration_pending_owner`/`migration_pending_hash`) nombra a la
  PRIMERA identidad que reclama la migración de un solo uso, y otra identidad
  con la puerta abierta ya no adopta (ni limpia) el respaldo ajeno.
- **S02 (iOS):** validación de orden por componentes con collation C
  (bytes UTF-8, NULL de idioma primero, estrictamente creciente = rechaza
  duplicados); sin claves concatenadas.
- **S03 (Android):** la saturación del buffer de saludo solo marca
  `openingCaptureFull` (emite el aviso sin fijar el gate del turno); el drenaje
  del prefijo retenido usa un flag por iteración que impide cerrar el turno a
  la primera voz y aplica la intención de Finalizar SOBRE EL PREFIJO COMPLETO.
- **S04 (Android):** `processTurn` emite `session.capture.limit_reached` cuando
  el recorte se decide al finalizar, y los strings EN/ES ya no prometen que se
  conserva todo («solo el inicio»).
- **S05 (iOS):** la prueba reasigna el propietario autenticado con el helper
  `assigning(owner:)` (mismo contrato que el adaptador productivo).
- **S06 (iOS):** limpieza síncrona compartida (`deactivateAndRestoreSession`),
  control de propiedad ANTES de tocar la sesión en `disconnect`, y registro de
  `retainCleanup` con token+configuración capturados cuando la limpieza falla,
  para que el siguiente `acquire` (conversación o pronunciación) la reintente.
- **S07 (Android):** `ledgerRevision` e `loadedLearningIdentity` son estado
  observable del ViewModel y las claves del `remember` de Aprender.
- **S08 (iOS):** la notificación de sesión solo invalida la sincronización
  cuando NO hay tarea viva sirviendo ya la cuenta actual del keychain
  (`progressSyncTaskAccountID`); una credencial sustituida sigue deteniendo el
  bearer antiguo por los re-checks `owns(liveIdentity)` de la propia tarea.

## Límites declarados

- Swift/Xcode no están disponibles en Linux: S02/S05/S06/S08 quedan
  **implementados en fuente y revisados estáticamente**; su ejecución
  (AppTests/XCTest) queda diferida a macOS y se registra como tal.
- S08 es un riesgo por confirmar: el cerco elimina el daño aunque el orden
  adverso llegue a producirse; la prueba de scheduling controlado queda
  diferida con el resto.
- La validación montada de S07 se cubre en esta sesión con la pasada manual del
  emulador (no hay test Compose versionado de ese flujo).

## Verificación mínima (Linux)

1. Regresiones nuevas en rojo por el motivo correcto antes del cambio
   (LearningDbStoreTest ×3, LocalVoiceTransportTest ×2).
2. Suite completa de app + engine-local en verde tras el cambio.
3. `make check` (148 pruebas Python, esquema generado al día).
4. Bootstrap, arranque y recorrido de la app en el emulador contra la API en
   Docker, con logcat limpio de errores internos (fase final de la sesión).
