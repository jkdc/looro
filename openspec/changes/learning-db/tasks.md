# Tasks: learning-db

El detalle de cada fase está en el plan maestro (docs/plans/
2026-10-03-learning-db-migration.md). Cada fase se ejecuta como una sesión
SDD con su propio plan, implementador, review y fix loop; esta lista se
marca al cierre de cada fase.

- [ ] A Fundaciones: `shared/db/schema.sql` canónico + `scripts/check_db_schema.py`
      (paridad Room/GRDB) + fixtures de migración con caso edge (legacy
      languageID=null, sesión sin terminar, scenario review).
- [ ] B Android persistencia: Room entities/DAOs/repos (sin rewiring) +
      tests de roundtrip/merge/caps/pods contra los fixtures.
- [ ] C Android cutover: migración one-way del learning.json (conteos
      verificados, `.migrated`, hash anti-re-migración) + rewire
      AppViewModel/recovery/outbox/tickets + e2e emulador.
- [ ] D Android backup: export/import `.db` + import legacy JSON v2 +
      delete-all por idioma + strings EN/ES + e2e de backup/restauración.
- [ ] E iOS GRDB: espejo de B+C (migración del store SwiftData + rewire) —
      gate macOS.
- [ ] F iOS backup: espejo de D — gate macOS.
- [ ] G Capacidades: búsqueda FTS de conversaciones (Android+iOS) +
      telemetría `archive_health`.
- [ ] H Cierre: retirar export JSON v2, limpieza del canal full-archive,
      docs, verify-report honesto + informe.

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
