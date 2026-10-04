# Tasks: learning-db

El detalle de cada fase está en el plan maestro (docs/plans/
2026-10-03-learning-db-migration.md). Cada fase se ejecuta como una sesión
SDD con su propio plan, implementador, review y fix loop; esta lista se
marca al cierre de cada fase.

- [x] A Fundaciones: `shared/db/schema.sql` canónico + `scripts/check_db_schema.py`
      (paridad Room/GRDB) + fixtures de migración con caso edge (legacy
      languageID=null, sesión sin terminar, scenario review).
      — e9f9d7f, 48c3dbf; checker sin issues, 4 fixtures con expected.
- [x] B Android persistencia: Room entities/DAOs/repos (sin rewiring) +
      tests de roundtrip/merge/caps/pods contra los fixtures.
      — 06b0c9d, 4f5ef7a, 15faad0, 7e75bf9, 811d16b, 23ab80e; suite JVM verde.
- [x] C Android cutover: migración one-way del learning.json (conteos
      verificados, `.migrated`, hash anti-re-migración) + rewire
      AppViewModel/recovery/outbox/tickets + e2e emulador.
      — 6ea2da4, 2bb77be, 01c730f, b3d4d7e; e2e en emulador verificado.
- [x] D Android backup: export/import `.db` + import legacy JSON v2 +
      delete-all por idioma + strings EN/ES + e2e de backup/restauración.
      — 98d0b0b, 09252b5, 71b7159, eb8045a; e2e backup/restauración en emulador.
- [x] E iOS GRDB: espejo de B+C (migración del store SwiftData + rewire) —
      gate macOS.
      — 4b43eae, 438b360, eae758b; espejo declaración por declaración del
      camino Kotlin compilado; `swift test` pendiente de macOS.
- [x] F iOS backup: espejo de D — gate macOS.
      — 065d2a2, 7b5f636; quiesce de writers diferidos antes del restore.
- [x] G Capacidades: búsqueda FTS de conversaciones (Android+iOS) +
      telemetría `archive_health`.
      — a80fd3d, 1381af6, 1c008e2, 2844532, b307d29; fix de portabilidad
      FTS5→FTS4 en review; e2e de búsqueda en emulador.
- [x] H Cierre: retirar export JSON v2, limpieza del canal full-archive,
      docs, verify-report honesto + informe.
      — 47c2884, cf69ff2 + fix ronda 1 (atribución del break androidTest
      corregida a b391e8b, import añadido, shadow tables del checker);
      JSON export retirado de la UI en ambas plataformas (import legacy
      conservado); wording FTS5→FTS4 barrido; verify-report + informe con
      la batería final de la sesión.

## Notas de diseño (resumen; el detalle completo vive en el plan maestro)

- El esquema canónico de `shared/db/schema.sql` es la ÚNICA fuente de verdad
  del esquema; Room y GRDB solo lo implementan, nunca lo divergen
  (`check_db_schema.py` falla si hay delta).
- Los structs de dominio y los motores no cambian; los repos hacen filas↔structs.
- `ArchiveCodec` sobrevive como codec de IMPORT legacy + validador de merge;
  deja de ser el runtime store.
- Backup nuevo = fichero `.db` (user_version); import JSON v2 se mantiene.
- Semántica de merge: paridad obligatoria con `ArchiveCodec.merge` sobre los
  fixtures (upsert por claves, más-reciente-gana).
- Caps y poda POR idioma (expresiones 2.000/idioma; sesiones por idioma);
  notebook y todas las lecturas de UI siempre `WHERE language = ?`.
- Sync al server: sin cambios. Privacidad: solo-local, sin cloud (igual que hoy).
- La migración falla dura si los conteos no cuadran; nunca borra el JSON
  original; ofrece export previo en UI la primera vez.
- Desviación consciente del plan maestro antiguo: el fallback `jsonMode` se
  CONSERVA deliberadamente como red de seguridad en producción; su retiro se
  decidirá con datos (ver verify-report.md).
