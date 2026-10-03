# Proposal: learning-db

## Intent

Mover todo el estado del usuario y su aprendizaje (estado caliente +
historial de conversaciones) de un documento JSON vivo en RAM a SQLite real
en ambas plataformas (Room en Android, GRDB en iOS), con esquema canónico
compartido y verificado. El formato de backup pasa a ser el propio fichero
`.db` (versionado con `PRAGMA user_version`), manteniendo el import de
backups legacy JSON v2 indefinidamente. Habilita por diseño: escrituras
incrementales O(turno), caps y poda por idioma, notebook siempre filtrado por
idioma, y búsqueda FTS de conversaciones como feature nueva.

Decisión de producto de Joaquín (2026-10-03), tras el análisis de storage de
la sesión SDD 2026-10-02 (plan maestro: docs/plans/2026-10-03-learning-db-migration.md,
copia local).

## Problem

El modelo actual (documento JSON único reescrito entero por ráfaga, archive
completo residente en RAM) es correcto para la escala actual pero tiene fecha
de caducidad conocida: escrituras O(historial), muro de 30 MB en Android
(iOS poda, Android falla — divergencia sin razón de producto), caps globales
en vez de por idioma, y hace imposible la búsqueda en conversaciones. La BD
resuelve los cinco a la vez con la misma promesa de privacidad (solo local).

## Scope

- `shared/db/schema.sql` canónico + `scripts/check_db_schema.py` (paridad
  Room/GRDB) + fixtures de migración.
- Android: Room + repos + migración one-way del `learning.json` (retenido
  como `.migrated`) + cutover + backup `.db` + import legacy.
- iOS: GRDB espejo + migración del store SwiftData + backup (gate macOS).
- Los motores de aprendizaje (Learning/Recall/Progress/Mastery/Exercise) y
  los structs de dominio NO cambian; los repos convierten filas ↔ structs.
- Server: sin cambios (progress sync es entry-based; usage events ya
  independientes).
- Nuevas capacidades: búsqueda FTS de conversaciones, caps/poda por idioma,
  delete-all por idioma, telemetría `archive_health`.

## Out of scope

- Room/Core Data para cualquier cosa que no sea aprendizaje (keys: Keystore/
  Keychain como hoy).
- Cifrado adicional de la DB (decisión de producto futura; hoy sandbox).
- Sincronización cloud (el producto sigue siendo solo-local).
- Cambiar los motores o las políticas pedagógicas.

## Delivery

8 fases SDD (A-H, ver plan maestro), cada una con implementador + review +
fix loop + re-review; Android primero (compilable en Linux), iOS espejado con
gate macOS. La migración falla dura si los conteos no cuadran y nunca borra
el JSON original.
