# Informe — Cierre multilanguage-parity

**Fecha:** 2026-10-02 · **SDD:** `openspec/changes/multilanguage-parity/` · Commits de contenido: `d098876` (ola F5 transporte+salud), `a88f757`/`abf2379`/`1a5a9a2` (olas de relleno y cierre), `f2ba4c7` (pronunciación)

## Qué se cerró

Verificación y bookkeeping, sin autoría nueva: el marcador **F5** quedó
huérfano — su contenido ya estaba shipped y verificado (Verify F10), pero la
casilla seguía abierta. Lo mismo con F6/F7 `[~]` y F9 `[~]`. Este pase
recontó el JSON canónico, cerró los cuatro marcadores con evidencia y dejó
el verify-report del change.

## Cifras verificadas (recontadas hoy, no heredadas)

- **A1 = 14 unidades / 40 lecciones en los seis foundations**
  (en/es/fr/de/it/pt).
- **Listenings A1**: en 15, resto 16 (objetivo ≥15). Escalera completa
  no-EN 16/12/7/7/5 (fr B1=8) sobre objetivos 15/12/7/7/3; en 15/12/7/7/3
  exacto.
- **Temas F5 en los 5 no-EN**: transporte (En movimiento/Unterwegs/…),
  tiendas (mercado/feira + ropa y tallas), salud (farmacia + vida sana ×5),
  ocio con `vocab:hobbies` taggeado en los 5 + celebraciones.
- **Paridad de forma**: no-EN = en 40/18/14/5/5 lecciones (B1 11+3 track),
  12/7/7/5 listenings A2+, 4 readings A2, 5 checkpoints, 1 track —
  PARITY_GAP = 0.
- Catálogo: 16 cursos, 280 listenings, 122 readings, 30 checkpoints.

## Honestidad de proceso

F5 no tuvo commit "ola 3" que cerrara su casilla: el contenido llegó por
`d098876` (transporte + salud) y las olas de relleno/cierre
(`a88f757`, `abf2379`, `1a5a9a2`). El marcador se cierra **después del
hecho**, por conteo — queda documentado en tasks.md y verify-report.

## Verificación (real, esta sesión — solo lectura)

- `content_health.py` y `--parity`: "no issues found" ✓.
- Exportadores `--check` ×5 (courses/listening/readings/assessments/
  android_content): in sync.
- Gradle no re-ejecutado: `shared/` sin cambios desde `88de326`
  (494/494 de esta mañana).

## Pendientes

- Nada del SDD: F0-F10 todos `[x]`. El gate macOS de iOS sigue abierto por
  política de repo (fuera de este change).
